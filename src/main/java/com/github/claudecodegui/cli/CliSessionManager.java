package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.claude.ClaudeCliSessionFactory;
import com.github.claudecodegui.cli.codex.CodexCliSessionFactory;
import com.github.claudecodegui.cli.opencode.OpenCodeCliSessionFactory;
import com.github.claudecodegui.cli.grok.GrokCliSessionFactory;
import com.github.claudecodegui.cli.kimi.KimiCliSessionFactory;
import com.github.claudecodegui.cli.pi.PiCliSessionFactory;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 模式统一入口。每个 Tab 拥有独立的 ClaudeCliSession / CodexCliSession。
 * 完全不依赖 SDK / ai-bridge。
 * <p>
 * 面向 {@link CliSession} 接口容器，按 (tabId, provider) 解析。
 * <p>
 * 会话创建经 {@link CliSessionFactory} 注册表路由(总则五·开闭 / E1):
 * 新增 CLI provider 只需新增一个工厂实现 + 装配注册一行,createSession 路由主体不变,
 * 取代原先 createSession 内的 provider switch。
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

    /**
     * 已销毁的 tabId 集合:拦截 {@link #disposeTab} 之后迟到的 send。
     * <p>
     * 竞态场景:disposeTab 清空 inFlight/sessions 与迟到 send 的 computeIfAbsent 非原子,
     * 无防护时迟到 send 会经 {@code sessions.computeIfAbsent} 重建 CliSession 并重启 CLI 子进程。
     * 标记后 {@link #send} 入口直接拒绝,杜绝"已关闭 tab 的迟到请求复活会话"。
     * <p>
     * tabId 一经销毁不复用(开新 tab 用新 tabId),故无需清理。
     */
    private final Set<String> disposedTabs = ConcurrentHashMap.newKeySet();

    /**
     * CLI 会话工厂注册表:provider → factory。装配期填充(fail-fast 校验重复),
     * 运行期只读查表(E1·开闭路由)。
     */
    private final Map<String, CliSessionFactory> factories;

    /**
     * 默认装配 Claude + Codex + OpenCode + Grok + Kimi + Pi 六个工厂。
     */
    public CliSessionManager() {
        this(List.of(new ClaudeCliSessionFactory(), new CodexCliSessionFactory(),
                new OpenCodeCliSessionFactory(), new GrokCliSessionFactory(),
                new KimiCliSessionFactory(), new PiCliSessionFactory()));
    }

    /**
     * Project-aware装配:CLI sessions 可获取 Project-scoped MCP Gateway。
     */
    public CliSessionManager(Project project) {
        this(List.of(
                new ClaudeCliSessionFactory(McpGatewayService.getInstance(project)),
                new CodexCliSessionFactory(McpGatewayService.getInstance(project)),
                new OpenCodeCliSessionFactory(McpGatewayService.getInstance(project)),
                new GrokCliSessionFactory(),
                new KimiCliSessionFactory(),
                new PiCliSessionFactory()
        ));
    }

    /**
     * 显式注入工厂列表(测试 / 自定义装配用)。重复 provider fail-fast 抛异常。
     */
    public CliSessionManager(List<CliSessionFactory> factories) {
        Map<String, CliSessionFactory> map = new HashMap<>();
        for (CliSessionFactory factory : factories) {
            if (map.putIfAbsent(factory.provider(), factory) != null) {
                throw new IllegalArgumentException("Duplicate CLI session factory: " + factory.provider());
            }
        }
        this.factories = map;
    }

    public CompletableFuture<SDKResult> send(CliSendRequest request, MessageCallback callback) {
        String tabId = request.tabId();
        // 已销毁的 tab:拒绝迟到 send,避免经 resolveSession 重建 CliSession / 重启 CLI 子进程。
        if (disposedTabs.contains(tabId)) {
            String error = "Session disposed, send rejected: tab=" + tabId;
            SDKResult errorResult = SDKResult.error(error);
            callback.onError(error);
            callback.onComplete(errorResult);
            return CompletableFuture.completedFuture(errorResult);
        }
        // per-tab 串行:同一 tab 的 send 必须排队执行(前一个完成或异常后才轮到下一个),
        // 避免并发落到同一非线程安全的 ClaudeCliSession/CodexCliSession 实例
        // (activeHandle 被覆盖致孤儿进程、userInterrupted 被清零致中断失效、
        // Codex 的 HashMap/HashSet 并发损坏)。compute 保证后到的 send 必然链在前一个之后。
        return inFlight.compute(tabId, (k, prev) -> {
            // STREAM-01:锁区内重检 disposedTabs。send 入口检查(:96)与此 compute 非原子:disposeTab 可在两步
            // 之间完整执行(标记+清 inFlight),此时 prev 已为 null,串行链不会复活会话;但若放行,dispatchSend
            // 异步仍会经 resolveSession→computeIfAbsent 重建 CliSession 并重启 CLI 子进程→孤儿。重检后直接拒绝,
            // 不调度 dispatchSend。(dispatchSend 另有末道守卫,覆盖 compute 通过后 disposeTab 才执行的窗口。)
            if (disposedTabs.contains(tabId)) {
                String error = "Session disposed, send rejected: tab=" + tabId;
                SDKResult errorResult = SDKResult.error(error);
                callback.onError(error);
                callback.onComplete(errorResult);
                CompletableFuture<SDKResult> rejected = CompletableFuture.completedFuture(errorResult);
                rejected.whenComplete((r, ex) -> inFlight.remove(tabId, rejected));
                return rejected;
            }
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
        // STREAM-01 末道守卫:dispatchSend 经 thenComposeAsync 异步执行,可能在 send 入口/锁区检查通过之后、
        // disposeTab 完整执行(sessions 已清)之后才到达。此时 resolveSession→computeIfAbsent 会重建 CliSession
        // 并重启 CLI 子进程→孤儿。重检 disposedTabs 直接拒绝。
        if (disposedTabs.contains(tabId)) {
            String error = "Session disposed, send rejected (async dispatch): tab=" + tabId;
            SDKResult errorResult = SDKResult.error(error);
            callback.onError(error);
            callback.onComplete(errorResult);
            return CompletableFuture.completedFuture(errorResult);
        }
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

    /**
     * 经工厂注册表创建 CliSession 实例(E1·开闭路由)。
     * 未知 provider fail-fast 抛异常(取代原先 switch 的 default 分支)。
     */
    private CliSession createSession(String provider, String tabId) {
        CliSessionFactory factory = factories.get(provider);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown CLI provider: " + provider);
        }
        return factory.create(tabId);
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
        // 标记已销毁:拦截本方法返回后迟到的 send(见 send 入口检查)。
        disposedTabs.add(tabId);
        // 先取消该 tab 进行中的 send future:防止 dispose 后队列里残留的串行 send 再次启动 CLI 子进程,
        // 也避免 dispose() 释放的 CliSession 被正在运行的 send 继续写入(并发损坏/孤儿进程)。
        CompletableFuture<SDKResult> inflight = inFlight.remove(tabId);
        if (inflight != null) {
            inflight.cancel(true);
        }
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

    /**
     * 归一化 interrupt 的 provider 字符串到合法的 claude/codex。
     * <p>
     * 委托 {@link ProviderType#fromString}(null/未知→CLAUDE, codex→CODEX)消除手写 switch(E1),
     * 语义与原 switch 完全一致:CliSessionManagerTest 4 断言逐项等价。
     */
    static String normalizeInterruptProvider(String provider) {
        return ProviderType.fromString(provider).value();
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
