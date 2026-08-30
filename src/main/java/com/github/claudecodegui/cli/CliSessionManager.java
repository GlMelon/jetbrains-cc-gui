package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.cli.claude.ClaudeCliSessionFactory;
import com.github.claudecodegui.cli.codex.CodexCliSessionFactory;
import com.github.claudecodegui.cli.opencode.OpenCodeCliSessionFactory;
import com.github.claudecodegui.cli.grok.GrokCliSessionFactory;
import com.github.claudecodegui.cli.kimi.KimiCliSessionFactory;
import com.github.claudecodegui.cli.pi.PiCliSessionFactory;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CLI 模式统一入口。每个 Tab 拥有独立的 ClaudeCliSession / CodexCliSession。
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
    private final ConcurrentHashMap<String, CompletableFuture<CliResult>> inFlight = new ConcurrentHashMap<>();

    /**
     * 已销毁的 tabId 集合:拦截 {@link #disposeTab} 之后迟到的 send。
     * <p>
     * 竞态场景:disposeTab 清空 inFlight/sessions 与迟到 send 的 computeIfAbsent 非原子,
     * 无防护时迟到 send 会经 {@code sessions.computeIfAbsent} 重建 CliSession 并重启 CLI 子进程。
     * 标记后 {@link #send} 入口直接拒绝,杜绝"已关闭 tab 的迟到请求复活会话"。
     * <p>
     * 标记会在 manager 生命周期结束时清空；运行期也会定期清理过期/超量标记，
     * 避免异常的 tab 创建/销毁风暴把 tombstone 集合无限扩大。
     */
    private static final long DISPOSED_TAB_TTL_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final int MAX_DISPOSED_TAB_MARKERS = 2048;
    private static final AtomicLong DISPOSED_TAB_CLEANUP_TICK = new AtomicLong();

    private final ConcurrentHashMap<String, Long> disposedTabs = new ConcurrentHashMap<>();
    private volatile boolean disposed;

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
                new KimiCliSessionFactory(), new PiCliSessionFactory(),
                new com.github.claudecodegui.cli.omp.OmpCliSessionFactory(),
                new com.github.claudecodegui.cli.dsh.DshCliSessionFactory()));
    }

    /**
     * Project-aware装配:CLI sessions 可获取 Project-scoped MCP Gateway;
     * claude 额外注入 Project-scoped 长驻进程注册表(Phase 1 仅 claude)。
     */
    public CliSessionManager(Project project) {
        this(List.of(
                new ClaudeCliSessionFactory(McpGatewayService.getInstance(project),
                        CliPersistentProcessRegistry.getInstance(project)),
                new CodexCliSessionFactory(McpGatewayService.getInstance(project)),
                new OpenCodeCliSessionFactory(McpGatewayService.getInstance(project)),
                new GrokCliSessionFactory(),
                new KimiCliSessionFactory(McpGatewayService.getInstance(project)),
                new PiCliSessionFactory(),
                new com.github.claudecodegui.cli.omp.OmpCliSessionFactory(),
                new com.github.claudecodegui.cli.dsh.DshCliSessionFactory()
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

    public CompletableFuture<CliResult> send(CliSendRequest request, MessageCallback callback) {
        String tabId = request.tabId();
        cleanupDisposedTabsIfNeeded();
        // 已销毁的 tab:拒绝迟到 send,避免经 resolveSession 重建 CliSession / 重启 CLI 子进程。
        if (isDisposedTab(tabId)) {
            String error = "Session disposed, send rejected: tab=" + tabId;
            CliResult errorResult = CliResult.error(error);
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
            if (isDisposedTab(tabId)) {
                String error = "Session disposed, send rejected: tab=" + tabId;
                CliResult errorResult = CliResult.error(error);
                callback.onError(error);
                callback.onComplete(errorResult);
                CompletableFuture<CliResult> rejected = CompletableFuture.completedFuture(errorResult);
                rejected.whenComplete((r, ex) -> inFlight.remove(tabId, rejected));
                return rejected;
            }
            // 等前一个 send 完成(吞掉异常以放行后续),再开始当前 send。
            CompletableFuture<CliResult> waitChain = (prev != null)
                    ? prev.exceptionally(ex -> null)
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<CliResult> next = waitChain.thenComposeAsync(
                    v -> dispatchSend(request, callback), CliSessionExecutor.executor());
            next.whenComplete((r, ex) -> inFlight.remove(tabId, next));
            return next;
        });
    }

    private CompletableFuture<CliResult> dispatchSend(CliSendRequest request, MessageCallback callback) {
        String tabId = request.tabId();
        String provider = request.provider();
        // STREAM-01 末道守卫:dispatchSend 经 thenComposeAsync 异步执行,可能在 send 入口/锁区检查通过之后、
        // disposeTab 完整执行(sessions 已清)之后才到达。此时 resolveSession→computeIfAbsent 会重建 CliSession
        // 并重启 CLI 子进程→孤儿。重检 disposedTabs 直接拒绝。
        if (isDisposedTab(tabId)) {
            String error = "Session disposed, send rejected (async dispatch): tab=" + tabId;
            CliResult errorResult = CliResult.error(error);
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

    /** Returns capabilities without creating a missing CLI session. */
    public SessionNegotiatedCapabilities capabilities(String tabId, String provider) {
        if (tabId == null || provider == null || isDisposedTab(tabId)) {
            return SessionNegotiatedCapabilities.unknown();
        }
        ConcurrentHashMap<String, CliSession> providerMap = sessions.get(tabId);
        if (providerMap == null) {
            return SessionNegotiatedCapabilities.unknown();
        }
        CliSession session = providerMap.get(provider);
        return session == null ? SessionNegotiatedCapabilities.unknown() : session.capabilities();
    }

    public void disposeTab(String tabId) {
        // 标记已销毁:拦截本方法返回后迟到的 send(见 send 入口检查)。
        cleanupDisposedTabsIfNeeded();
        disposedTabs.put(tabId, System.currentTimeMillis());
        // 先取消该 tab 进行中的 send future:防止 dispose 后队列里残留的串行 send 再次启动 CLI 子进程,
        // 也避免 dispose() 释放的 CliSession 被正在运行的 send 继续写入(并发损坏/孤儿进程)。
        CompletableFuture<CliResult> inflight = inFlight.remove(tabId);
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

    private boolean isDisposedTab(String tabId) {
        if (disposed) {
            return true;
        }
        Long disposedAt = disposedTabs.get(tabId);
        if (disposedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - disposedAt <= DISPOSED_TAB_TTL_MILLIS) {
            return true;
        }
        return disposedTabs.remove(tabId, disposedAt);
    }

    private void cleanupDisposedTabsIfNeeded() {
        long tick = DISPOSED_TAB_CLEANUP_TICK.incrementAndGet();
        if ((tick & 0x3F) != 0 && disposedTabs.size() <= MAX_DISPOSED_TAB_MARKERS) {
            return;
        }
        long cutoff = System.currentTimeMillis() - DISPOSED_TAB_TTL_MILLIS;
        for (Map.Entry<String, Long> entry : disposedTabs.entrySet()) {
            if (entry.getValue() < cutoff) {
                disposedTabs.remove(entry.getKey(), entry.getValue());
            }
        }
        while (disposedTabs.size() > MAX_DISPOSED_TAB_MARKERS) {
            Map.Entry<String, Long> oldest = null;
            for (Map.Entry<String, Long> entry : disposedTabs.entrySet()) {
                if (oldest == null || entry.getValue() < oldest.getValue()) {
                    oldest = entry;
                }
            }
            if (oldest == null || !disposedTabs.remove(oldest.getKey(), oldest.getValue())) {
                break;
            }
        }
    }

    /**
     * 释放 manager 所拥有的全部 CLI session 和异步状态。
     * manager 释放后即使有迟到的异步 send，也不能重新创建 session 或启动进程。
     */
    public void dispose() {
        disposed = true;
        for (String tabId : sessions.keySet()) {
            disposeTab(tabId);
        }
        inFlight.clear();
        sessions.clear();
        disposedTabs.clear();
    }

    private CompletableFuture<CliResult> sendToSession(
            CliSendRequest request,
            MessageCallback callback,
            CliSession session
    ) {
        return session.send(request, adapt(callback, request.provider()))
                .thenApply(v -> CliResult.success(null))
                .exceptionally(ex -> {
                    CliResult r = CliResult.error(ex.getMessage());
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

    /**
     * 不代表 assistant 产出的"控制/元数据/日志"类消息类型(经 {@link com.github.claudecodegui.cli.common.CliSectionEmitter} 发出的非内容方法)。
     * <p>
     * 用于 {@link #isContentBearing(String)} 的黑名单:整轮仅收到这些类型(或完全无 onMessage)说明
     * CLI 进程未产出任何 AI 内容,属"静默空成功"(典型:provider 服务端调用失败被 exit0 静默吞掉、
     * 子进程阻塞读 stdin)。黑名单方向安全——漏列某个控制类只会漏报(回到现状),不会误伤合法回合。
     */
    private static final Set<String> NON_CONTENT_MESSAGE_TYPES = Set.of(
            CliConstants.MSG_SESSION_ID,
            CliConstants.MSG_STREAM_START,
            CliConstants.MSG_STREAM_END,
            CliConstants.MSG_MESSAGE_START,
            CliConstants.MSG_MESSAGE_END,
            CliConstants.MSG_BLOCK_RESET,
            CliConstants.CODEX_MSG_STATUS,
            CliConstants.MSG_USAGE,
            CliConstants.MSG_RESULT,
            CliConstants.MSG_SLASH_COMMANDS,
            CliConstants.MSG_NODE_LOG,
            CliConstants.MSG_STREAM_EVENT,
            CliConstants.MSG_SESSION_TITLE
    );

    /**
     * 判定消息类型是否代表 assistant 产出了实质内容(文本/思考/工具)。
     * 不在 {@link #NON_CONTENT_MESSAGE_TYPES} 黑名单中的类型均视为有产出
     * (content_delta/thinking_delta/text/tool_use/tool_result/thinking/assistant/content/ai 等)。
     */
    static boolean isContentBearing(String type) {
        return type != null && !NON_CONTENT_MESSAGE_TYPES.contains(type);
    }

    /**
     * 将 CliSessionCallback 适配为 MessageCallback，统一回调格式。
     * <p>
     * 同时做"静默空成功"检测(所有 CLI provider 通用):若整轮声称 success 且无 error,
     * 但从未收到任何内容类消息(文本/思考/工具),则降级为错误上报——避免前端只显示完成提示却无正文。
     * 典型场景:provider 服务端调用失败被 CLI 进程 exit0 静默吞掉(如 opencode 返回空 step_finish)。
     * 该适配器仅作用于 CLI 会话回调路径。
     *
     * @param provider provider 名(诊断信息点名用)
     */
    static CliSessionCallback adapt(MessageCallback callback, String provider) {
        return new CliSessionCallback() {
            // per-turn:整轮是否收到过内容类消息。adapt 实例每次 sendToSession 新建,天然 turn 隔离。
            boolean producedContent;

            @Override
            public void onMessage(String type, String content) {
                if (isContentBearing(type)) {
                    producedContent = true;
                }
                callback.onMessage(type, content);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }

            @Override
            public void onComplete(boolean success, String finalResult, String error) {
                if (success && (error == null || error.isBlank()) && !producedContent) {
                    String diag = CliErrorFormatter.formatError(provider,
                            "进程正常退出但本轮未返回任何内容(无文本/工具/思考输出)。"
                                    + "常见原因:AI 服务端调用失败被静默吞掉、子进程阻塞读 stdin、"
                                    + "或 provider/模型配置无效。请在命令行直接运行该 provider 对照验证。");
                    callback.onError(diag);
                    callback.onComplete(CliResult.completed(false, finalResult, diag, false));
                    return;
                }
                callback.onComplete(CliResult.completed(success, finalResult, error, false));
            }

            @Override
            public void onInterrupted(String finalResult, String message) {
                callback.onComplete(CliResult.completed(false, finalResult, message, true));
            }
        };
    }
}
