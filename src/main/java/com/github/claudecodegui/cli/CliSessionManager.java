package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.claude.ClaudeCliSession;
import com.github.claudecodegui.cli.codex.CodexCliSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 模式统一入口。每个 Tab 拥有独立的 ClaudeCliSession / CodexCliSession。
 * 完全不依赖 SDK / ai-bridge。
 * <p>
 * 面向 {@link CliSession} 接口容器，按 (tabId, provider) 解析。
 */
public class CliSessionManager {

    private static final Logger LOG = Logger.getInstance(CliSessionManager.class);

    /**
     * 统一容器：tabId → (provider → CliSession)。
     * 替代原先 claudeSessions / codexSessions 双 Map。
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CliSession>> sessions = new ConcurrentHashMap<>();

    // 每个 tab 当前进行中的 send future,用于 per-tab 串行化,避免并发竞态。
    private final ConcurrentHashMap<String, CompletableFuture<SDKResult>> inFlight = new ConcurrentHashMap<>();

    public CompletableFuture<SDKResult> send(CliSendRequest request, MessageCallback callback) {
        String tabId = request.tabId();
        // per-tab 串行:同一 tab 的 send 必须排队执行(前一个完成或异常后才轮到下一个),
        // 避免并发落到同一非线程安全的 ClaudeCliSession/CodexCliSession 实例
        // (activeHandle 被覆盖致孤儿进程、userInterrupted 被清零致中断失效、
        // Codex 的 HashMap/HashSet 并发损坏)。compute 保证后到的 send 必然链在前一个之后。
        return inFlight.compute(tabId, (k, prev) -> {
            // 等前一个 send 完成(吞掉异常以放行后续),再开始当前 send。
            CompletableFuture<SDKResult> waitChain = (prev != null)
                    ? prev.exceptionally(ex -> null)
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<SDKResult> next = waitChain.thenComposeAsync(
                    v -> dispatchSend(request, callback), CliSessionExecutor.executor());
            next.whenComplete((r, ex) -> inFlight.remove(tabId, next));
            return next;
        });
    }

    private CompletableFuture<SDKResult> dispatchSend(CliSendRequest request, MessageCallback callback) {
        String tabId = request.tabId();
        String provider = request.provider();
        CliSession session = resolveSession(tabId, provider);
        return sendToSession(request, callback, session);
    }

    /**
     * 按 (tabId, provider) 解析 CliSession 实例。
     * 不存在时按 provider 类型创建新实例。
     */
    private CliSession resolveSession(String tabId, String provider) {
        ConcurrentHashMap<String, CliSession> providerMap =
                sessions.computeIfAbsent(tabId, k -> new ConcurrentHashMap<>());
        return providerMap.computeIfAbsent(provider, k -> createSession(provider, tabId));
    }

    private static CliSession createSession(String provider, String tabId) {
        return switch (provider) {
            case CliConstants.PROVIDER_CLAUDE -> new ClaudeCliSession(tabId);
            case CliConstants.PROVIDER_CODEX -> new CodexCliSession(tabId);
            default -> throw new IllegalArgumentException("Unknown CLI provider: " + provider);
        };
    }

    public void interrupt(String tabId, String provider) {
        String normalizedProvider = normalizeInterruptProvider(provider);
        ConcurrentHashMap<String, CliSession> providerMap = sessions.get(tabId);
        if (providerMap != null) {
            CliSession session = providerMap.get(normalizedProvider);
            if (session != null) {
                session.interrupt();
            }
        }
    }

    public void disposeTab(String tabId) {
        long startNanos = System.nanoTime();
        ConcurrentHashMap<String, CliSession> providerMap = sessions.remove(tabId);
        if (providerMap != null) {
            for (Map.Entry<String, CliSession> entry : providerMap.entrySet()) {
                long disposeStartNanos = System.nanoTime();
                entry.getValue().dispose();
                LOG.info("[TabPerf] CLI session dispose returned in "
                        + TabPerformanceLogger.elapsedMillis(disposeStartNanos) + "ms: tab=" + tabId
                        + ", provider=" + entry.getKey());
            }
        }
        LOG.info("[TabPerf] CliSessionManager.disposeTab returned in "
                + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private CompletableFuture<SDKResult> sendToSession(
            CliSendRequest request,
            MessageCallback callback,
            CliSession session
    ) {
        return session.send(request, adapt(callback))
                .thenApply(v -> SDKResult.success(null))
                .exceptionally(ex -> {
                    SDKResult r = SDKResult.error(ex.getMessage());
                    callback.onError(ex.getMessage());
                    callback.onComplete(r);
                    return r;
                });
    }

    static String normalizeInterruptProvider(String provider) {
        if (provider == null) {
            return CliConstants.PROVIDER_CLAUDE;
        }
        return switch (provider) {
            case CliConstants.PROVIDER_CODEX -> CliConstants.PROVIDER_CODEX;
            default -> CliConstants.PROVIDER_CLAUDE;
        };
    }

    /** 将 CliSessionCallback 适配为 MessageCallback，统一回调格式。 */
    private static CliSessionCallback adapt(MessageCallback callback) {
        return new CliSessionCallback() {
            @Override
            public void onMessage(String type, String content) {
                callback.onMessage(type, content);
            }
            @Override
            public void onError(String error) {
                callback.onError(error);
            }
            @Override
            public void onComplete(boolean success, String finalResult, String error) {
                callback.onComplete(SDKResult.completed(success, finalResult, error, false));
            }

            @Override
            public void onInterrupted(String finalResult, String message) {
                callback.onComplete(SDKResult.completed(false, finalResult, message, true));
            }
        };
    }
}
