package com.github.claudecodegui.cli;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.handler.PromptEnhancerProcessRunner;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.session.SessionCallbackFacade;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 模式 AI 会话标题生成服务(provider 无关)。
 *
 * <p>历史:已移除的 SDK daemon 模式下,标题由 daemon 在轮次结束后 fire-and-forget 调用
 * {@code ai-bridge/services/session-title-service.js#generateSessionTitle} 生成;
 * 现行 CLI 唯一路径每轮是一次性子进程,不经过常驻进程,故由本服务在 CLI 轮次成功完成后,
 * 以独立 node 子进程复用同一 {@code session-title-service.js} 生成标题。
 *
 * <p>触发条件(provider 可发送 + 首轮 + 成功 + 配置开启)由 {@link com.github.claudecodegui.session.SessionSendService}
 * 在 provider 无关的公共出口经 {@code whenComplete} 钩子统一判定后传入本服务;
 * 全部 CLI provider 共用本组件——{@code session-title-service.js} 调 Haiku API,
 * 生成环节本身与对话 provider 解耦。provider 相关差异有两处:
 * <ul>
 *   <li>kimi 自带原生标题通道(ACP {@code session_info_update.title}),Haiku 标题后到会
 *       覆盖原生标题,故在 {@link #NATIVE_TITLE_PROVIDERS} 门控下完全跳过;</li>
 *   <li>ai-title 落盘({@code ~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl} 追加一行)
 *       是 Claude 会话专属:该 jsonl 由 claude CLI 在轮次中自建。本服务经 stdin 把
 *       {@code provider} 传给脚本,非 claude provider 只下发 UI 标题不写盘——否则会在
 *       Claude 目录造出只含 ai-title 一行的孤儿 jsonl,被 Claude 历史读取器收成幽灵会话
 *       (2026-08 修复的历史遗留污染)。</li>
 * </ul>
 *
 * <p>本服务依赖的 Node 可执行文件 / ai-bridge 目录 / {@link ProcessManager}
 * 等共享基础设施由各 provider 共用(同一 ai-bridge 目录),非 Claude 特异依赖。
 *
 * <p>下行复用既有 {@link DownstreamEvent#SESSION_TITLE} 事件:
 * {@link SessionCallbackFacade#notifyProtocolEvent} → SessionCallbackAdapter.onProtocolEvent
 * → dispatchEvent,沿用既有前端入口,前端零改动。
 */
public class CliSessionTitleService {

    private static final Logger LOG = Logger.getInstance(CliSessionTitleService.class);
    private static final Gson gson = GsonHolder.GSON;

    // 标题生成 node 进程硬超时。session-title-service.js 内部 Haiku 调用 15s 超时,
    // 这里留足余量覆盖进程启动 + stdin/stdout 往返 + 网络抖动。超时强杀,fire-and-forget 不阻塞对话。
    private static final long TITLE_TIMEOUT_SECONDS = 30;
    private static final long READER_DRAIN_SECONDS = 5;

    /**
     * 自带原生标题通道、不应再触发 Haiku 标题的 provider:kimi 经 ACP
     * {@code session_info_update.title} 下发原生标题(KimiAcpCliSession.finalizeTurn →
     * MSG_SESSION_TITLE → SESSION_TITLE),Haiku 标题生成更慢,后到会覆盖原生标题。
     */
    private static final Set<String> NATIVE_TITLE_PROVIDERS = Set.of(CommonConstants.PROVIDER_KIMI);

    // ⚠️ 构造期不可解析 NodeService(内部 new EnvironmentConfigurator 触碰 IntelliJ 平台
    // Application 单例):本服务在 SessionSendService 构造链上,纯 JUnit 装配环境会 NPE。
    // 惰性到标题生成实际触发时。
    private volatile NodeService nodeService;
    private final Set<PendingTitleTask> pendingTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean disposed;

    private NodeService nodeService() {
        if (nodeService == null) {
            nodeService = NodeService.getInstance();
        }
        return nodeService;
    }

    private static final class PendingTitleTask {
        private final ProcessManager processManager;
        private final String channelId;
        private volatile CompletableFuture<?> future;

        private PendingTitleTask(ProcessManager processManager, String channelId) {
            this.processManager = processManager;
            this.channelId = channelId;
        }

        private void cancel() {
            CompletableFuture<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(true);
            }
            // This also covers the window where the Java task has been
            // cancelled before ProcessBuilder.start() registers the process.
            processManager.interruptChannel(channelId);
        }
    }

    /**
     * 在 CLI 轮次成功完成后,按需触发 AI 标题生成。
     *
     * <p>所有前置判定(CLI / 首轮 / 成功)由调用方完成并传入布尔标志;本方法再做
     * sessionId / userMessage / 配置开关 / Node 基础设施可用性的二次校验,然后
     * fire-and-forget 起子进程。标题失败不影响对话(锦上添花能力)。
     *
     * @param isProviderSendable 当前 provider 是否可解析、可发送(调用方 SessionSendService 判定)
     * @param provider       当前对话 provider id(决定门控与脚本落盘行为,见类注释)
     * @param isFirstTurn    本次发送是否为新会话首轮(send 前 sessionId 为空)
     * @param userMessage    首轮用户消息文本(标题生成的输入)
     * @param sessionId      轮次完成后解析到的会话 ID(首轮由 CLI 流输出捕获)
     * @param cwd            工作目录(claude 落盘时定位 ~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl)
     * @param callbackFacade 用于回传 SESSION_TITLE 事件
     */
    public void maybeGenerateTitle(boolean isProviderSendable,
                                   String provider,
                                   boolean isFirstTurn,
                                   String userMessage,
                                   String sessionId,
                                   String cwd,
                                   SessionCallbackFacade callbackFacade) {
        if (disposed || !isProviderSendable || !isFirstTurn) {
            return;
        }
        if (provider == null || NATIVE_TITLE_PROVIDERS.contains(provider)) {
            // 有原生标题通道的 provider(kimi)跳过 Haiku 标题,避免覆盖原生标题。
            LOG.debug("[CliTitle] Skipping: provider " + provider + " has a native title channel");
            return;
        }
        if (callbackFacade == null) {
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            LOG.debug("[CliTitle] Skipping: no sessionId resolved after CLI turn");
            return;
        }
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return;
        }
        try {
            if (!CodemossSettingsService.getInstance().getAiTitleGenerationEnabled()) {
                LOG.debug("[CliTitle] Skipping: AI title generation disabled in settings");
                return;
            }
        } catch (Exception e) {
            // getAiTitleGenerationEnabled 读配置可能抛 IOException;读失败时保守放行(默认开启)。
            LOG.warn("[CliTitle] Failed to read AI title toggle, proceeding: " + e.getMessage());
        }

        final String nodeExecutable = nodeService().getNodeExecutable();
        if (nodeExecutable == null || nodeExecutable.isEmpty()) {
            LOG.warn("[CliTitle] Skipping: Node.js executable not configured");
            return;
        }
        File bridgeDir = nodeService().getBridgeDir();
        if (bridgeDir == null || !bridgeDir.exists()) {
            LOG.warn("[CliTitle] Skipping: ai-bridge directory unavailable");
            return;
        }

        ProcessManager processManager = nodeService().getProcessManager();
        String channelId = ProcessManager.newChannelId("cli-session-title");
        PendingTitleTask pendingTask = new PendingTitleTask(processManager, channelId);
        pendingTasks.add(pendingTask);
        if (disposed) {
            pendingTasks.remove(pendingTask);
            processManager.finishChannelStart(channelId);
            return;
        }

        CompletableFuture<Void> task = CliSessionExecutor.runAsync(() -> runTitleGeneration(
                nodeExecutable, bridgeDir, userMessage, sessionId, cwd, provider, callbackFacade,
                processManager, channelId));
        pendingTask.future = task;
        task.whenComplete((ignored, error) -> pendingTasks.remove(pendingTask));
        if (disposed) {
            pendingTask.cancel();
        }
    }

    private void runTitleGeneration(String nodeExecutable,
                                    File bridgeDir,
                                    String userMessage,
                                    String sessionId,
                                    String cwd,
                                    String provider,
                                    SessionCallbackFacade callbackFacade,
                                    ProcessManager processManager,
                                    String channelId) {
        if (disposed) {
            processManager.finishChannelStart(channelId);
            return;
        }
        List<String> command = new ArrayList<>();
        command.add(nodeExecutable);
        command.add(new File(bridgeDir, "services/session-title-service.js").getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(bridgeDir);
        pb.redirectErrorStream(true);
        try {
            new EnvironmentConfigurator().updateProcessEnvironment(pb, nodeExecutable);
        } catch (Exception e) {
            LOG.warn("[CliTitle] Failed to configure process environment: " + e.getMessage());
        }

        JsonObject stdinInput = new JsonObject();
        stdinInput.addProperty("userMessage", userMessage);
        stdinInput.addProperty("sessionId", sessionId);
        if (cwd != null && !cwd.isEmpty()) {
            stdinInput.addProperty("cwd", cwd);
        }
        // 脚本按此决定是否把 ai-title 落盘 ~/.claude/projects(仅 'claude';缺省不落盘,fail-safe)。
        if (provider != null && !provider.isEmpty()) {
            stdinInput.addProperty("provider", provider);
        }

        try {
            PromptEnhancerProcessRunner.runWithRegisteredChannel(
                    pb,
                    processManager,
                    channelId,
                    gson.toJson(stdinInput),
                    TITLE_TIMEOUT_SECONDS,
                    READER_DRAIN_SECONDS,
                    line -> handleStdoutLine(line, sessionId, callbackFacade));
        } catch (Exception e) {
            // 标题生成是 fire-and-forget,任何失败(超时 / 进程异常)都不影响对话。
            LOG.warn("[CliTitle] Title generation process failed: " + e.getMessage());
        }
    }

    /**
     * 解析 node 子进程 stdout 行。{@code session-title-service.js} 经
     * {@code emitTitleGenerated} 写出 {@code {type:'daemon', event:'title_generated',
     * sessionId, title}} 行;捕获后下发 SESSION_TITLE 事件。
     *
     * <p>注意:{@code type:'daemon'} 是前后端既有协议名(SDK daemon 模式移除前的遗留),
     * 仅为 stdout 行格式约定,值不可改——ai-bridge 侧 emit 与前端均依赖该字面量。
     */
    private void handleStdoutLine(String line, String sessionId, SessionCallbackFacade callbackFacade) {
        if (disposed) {
            return;
        }
        if (line == null || line.isEmpty() || line.charAt(0) != '{') {
            // 非 JSON 行(进程 stderr 合并行、空行)忽略。
            return;
        }
        try {
            JsonObject obj = gson.fromJson(line, JsonObject.class);
            if (obj == null) {
                return;
            }
            String event = obj.has("event") ? obj.get("event").getAsString() : null;
            if (!"title_generated".equals(event)) {
                // title_log 等其他协议事件忽略。
                return;
            }
            String title = obj.has("title") ? obj.get("title").getAsString() : null;
            if (title == null || title.isEmpty()) {
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", sessionId);
            payload.addProperty("title", title);
            String payloadJson = gson.toJson(payload);
            LOG.info("[CliTitle] AI title generated for session " + sessionId + ": " + title);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || callbackFacade == null) {
                    return;
                }
                try {
                    callbackFacade.notifyProtocolEvent(
                            DownstreamEvent.SESSION_TITLE.value(), payloadJson);
                } catch (Exception e) {
                    LOG.warn("[CliTitle] Failed to dispatch SESSION_TITLE: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.debug("[CliTitle] Unparseable stdout line: " + line);
        }
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (PendingTitleTask task : pendingTasks) {
            task.cancel();
        }
        pendingTasks.clear();
    }
}
