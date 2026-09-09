package com.github.claudecodegui.cli.codex.appserver;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.codex.CodexCliCommandUtils;
import com.github.claudecodegui.cli.codex.CodexCliSession;
import com.github.claudecodegui.cli.codex.CodexServiceTierPolicy;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliPersistentFeatureFlags;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.reasoning.ReasoningCapabilities;
import com.github.claudecodegui.reasoning.ReasoningEffortResolver;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.CliTempDir;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * codex app-server 托管流式会话({@code codex app-server} + stdio JSON-RPC,token 级增量)。
 * <p>
 * 与 one-shot {@link CodexCliSession} 的差异仅在传输层:控制面走
 * {@link CodexAppServerClient} 的 JSON-RPC 请求(thread/start·resume、turn/start·interrupt),
 * 事件面走通知流(→ MSG_* 映射在 {@link CodexAppServerTurn})。<b>核心收益:agent 正文与
 * 推理摘要均 token 级增量下发</b>(exec --json 无 delta,agent_message 只能等 item.completed
 * 整段,深推理模型下表现为数十秒黑盒)。
 * <p>
 * 会话 id 续接语义与 one-shot 对齐:优先 request.sessionId()(前端持有的 thread id),
 * 实例字段兜底;thread/resume 失效(线程不存在)→ 清 id 建新线程重试一次(B13 等价物,
 * thread 存盘于 CODEX_HOME,进程重启后仍可续)。逐轮配置(model / reasoning effort /
 * service tier / sandbox / approval policy)全部经 turn/start 覆盖(契约支持,语义与
 * one-shot 的 -m / -c model_reasoning_effort / -c service_tier / --sandbox /
 * --ask-for-approval 一一对应)。
 * <p>
 * 降级语义(对齐 OpenCodeServeSession / CliPersistentProcessRegistry 的静默降级):
 * app-server 不可用(门禁关 / 熔断 / spawn 失败 / acquire 异常)或 turn 递交前的任何失败
 * (不限异常类型)→ 当轮整体降级 one-shot;<b>已递交 / 递交结果未知的轮不重发</b>
 * (turn 可能已被接受,重发会双发消息),断线 / 超时当轮报错,下轮由 manager 重建后再试。
 * <p>
 * interrupt:turn/interrupt(确定性取消,优于杀树——app-server 是 project 级共享进程,
 * 杀树会波及其他 tab 的进行轮),{@link CliConstants#CLI_INTERRUPT_FALLBACK_MS} 无回应
 * 则 {@link CodexAppServerManager#terminateAppServer} 兜底杀树。
 */
public class CodexAppServerSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(CodexAppServerSession.class);

    /**
     * app-server 权限交互闸口:把审批请求(commandExecution / fileChange)路由到插件权限
     * 对话体系(PermissionService 决策记忆 + 前端对话框),返回 app-server 协议决策
     * ("accept" / "acceptForSession" / "decline")。实现方必须保守:任何无法判定的情形
     * 返回 "decline"(decline 后 turn 继续,保守不致命)。
     */
    public interface AppServerPermissionGate {
        CompletionStage<String> ask(String toolName, JsonObject inputs, String cwd);
    }

    private final String tabId;
    private final CodexAppServerManager appServerManager;
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;
    private final CliAttachmentHandler attachmentHandler = new CliAttachmentHandler();
    private final CodexServiceTierPolicy serviceTierPolicy = new CodexServiceTierPolicy();
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    /** 测试 seam:注入已连接的 app-server client 时跳过 manager acquire/spawn(生产恒 null)。 */
    private final CodexAppServerClient injectedClient;
    /** 测试 seam:one-shot 兜底替身(生产恒 null,走懒创建的真实 CodexCliSession)。 */
    private final CliSession fallbackOverride;
    /** 交互式权限闸口;为 null(测试 / 无 Project 路径)时退回保守 MVP(非 bypass 即 decline)。 */
    private final AppServerPermissionGate permissionGate;

    /** 当前 thread id(首轮创建后回写,续接复用;= one-shot 的 threadId 语义)。 */
    private volatile String threadId;
    private volatile CodexAppServerTurn activeTurn;
    private volatile String activeTurnThreadId;
    private volatile String activeTurnId;
    private volatile CodexAppServerClient activeClient;
    /** one-shot 兜底会话(懒创建;降级轮与 interrupt 委托用它)。 */
    private volatile CliSession fallbackSession;

    public CodexAppServerSession(String tabId, CodexAppServerManager appServerManager,
                                 McpGatewayService gatewayService,
                                 LifecycleObservabilityService lifecycleService,
                                 AppServerPermissionGate permissionGate) {
        this.tabId = tabId;
        this.appServerManager = appServerManager;
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
        this.injectedClient = null;
        this.fallbackOverride = null;
        this.permissionGate = permissionGate;
    }

    /** 测试 seam 构造:注入已连接的 app-server client(绕过 manager acquire/spawn)与兜底会话替身。 */
    CodexAppServerSession(String tabId, CodexAppServerClient injectedClient, CliSession fallbackOverride) {
        this(tabId, injectedClient, fallbackOverride, null);
    }

    /** 测试 seam 构造(带权限闸口)。 */
    CodexAppServerSession(String tabId, CodexAppServerClient injectedClient, CliSession fallbackOverride,
                          AppServerPermissionGate permissionGate) {
        this.tabId = tabId;
        this.appServerManager = null;
        this.gatewayService = null;
        this.lifecycleService = null;
        this.injectedClient = injectedClient;
        this.fallbackOverride = fallbackOverride;
        this.permissionGate = permissionGate;
    }

    @Override
    public SessionNegotiatedCapabilities capabilities() {
        // 与 one-shot 对齐(总则六对称)
        return SessionNegotiatedCapabilities.cli(true, true, false);
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        // 门禁在工厂创建期判定一次,此处每轮重检:UI 开关关闭后已创建的会话实例即时回切 one-shot
        CodexAppServerClient client = injectedClient;
        if (client == null && appServerManager != null && CliPersistentFeatureFlags.isCodexAppServerEnabled()) {
            try {
                client = appServerManager.acquire(tabId, effectiveCwd(request), request.extraEnv());
            } catch (Exception | LinkageError e) {
                // acquire 同步异常(spawn/指纹计算等未预期失败):记日志并降级 one-shot
                LOG.warn("[CodexAppServerSession][" + tabId + "] app-server acquire failed, degrading to one-shot", e);
                client = null;
            }
        }
        if (client == null) {
            return fallbackSession().send(request, callback);
        }
        final CodexAppServerClient appServerClient = client;
        return CliSessionExecutor.runAsync(() -> runAppServerTurn(appServerClient, request, callback));
    }

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        CodexAppServerTurn turn = activeTurn;
        CodexAppServerClient client = activeClient;
        String turnThreadId = activeTurnThreadId;
        String turnId = activeTurnId;
        if (turn == null || client == null || turnThreadId == null || turnId == null) {
            CliSession fallback = fallbackSession;
            if (fallback != null) {
                fallback.interrupt();
            }
            return;
        }
        turn.markInterrupted();
        JsonObject params = new JsonObject();
        params.addProperty("threadId", turnThreadId);
        params.addProperty("turnId", turnId);
        CliSessionExecutor.runAsync(() -> {
            try {
                client.request(CliConstants.CODEX_APPSERVER_METHOD_TURN_INTERRUPT, params)
                        .get(CliConstants.CODEX_APPSERVER_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOG.warn("[CodexAppServerSession][" + tabId + "] interrupt request failed, terminating app-server", e);
                if (appServerManager != null) {
                    appServerManager.terminateAppServer("interrupt request failed: " + e.getMessage());
                }
            }
        });
        // 兜底:interrupt 后无 turn/completed 回应 → 杀 app-server 进程树(当轮经 EOF 收尾为中断)
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (!turn.completion().isDone() && appServerManager != null) {
                LOG.warn("[CodexAppServerSession][" + tabId + "] interrupt fallback timeout, terminating app-server");
                appServerManager.terminateAppServer("interrupt fallback timeout");
            }
        }, CliConstants.CLI_INTERRUPT_FALLBACK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispose() {
        interrupt();
    }

    // ── app-server 轮 ─────────────────────────────────────────────────────────

    private void runAppServerTurn(CodexAppServerClient client, CliSendRequest request,
                                  CliSessionCallback callback) {
        List<File> tempFiles = new ArrayList<>();
        String turnThreadId = null;
        // turn 是否已递交成功(turn/start 正常返回):失败收尾按此分派,而非按异常类型——
        // 未递交的任何失败(不限类型)重发安全,降级 one-shot;已递交/递交结果未知不降级,防双发。
        boolean turnSubmitted = false;
        try {
            callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());
            turnThreadId = resolveThread(client, request);
            emitSessionId(callback, turnThreadId);

            CodexAppServerTurn turn = new CodexAppServerTurn(callback);
            activeTurn = turn;
            activeTurnThreadId = turnThreadId;
            activeClient = client;
            client.registerTurnHandler(turnThreadId,
                    wrapWithPermissionIntercept(client, turnThreadId, turn, request));
            callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.AWAITING_MODEL.value());

            JsonObject turnParams = buildTurnStartParams(turnThreadId, request, tempFiles);
            try {
                JsonObject turnResult = client.request(CliConstants.CODEX_APPSERVER_METHOD_TURN_START, turnParams)
                        .get(CliConstants.CODEX_APPSERVER_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                activeTurnId = extractTurnId(turnResult);
                turnSubmitted = true;
            } catch (TimeoutException e) {
                // 递交结果未知(turn 可能已被接受):不降级防双发,当轮报错,
                // 下轮由 manager 重建后再试(对齐 OpenCodeServeSession promptAsync 递交未知语义)
                client.unregisterTurnHandler(turnThreadId);
                reportTurnError(callback, turn, e);
                return;
            } catch (Exception e) {
                // JSON-RPC 确定性错误响应(server 拒绝,turn 未被接受):进外层降级分派
                client.unregisterTurnHandler(turnThreadId);
                throw e;
            }

            awaitTurnCompletion(turn, callback);
        } catch (Exception | LinkageError e) {
            if (turnThreadId != null) {
                client.unregisterTurnHandler(turnThreadId);
            }
            handleTurnFailure(request, callback, e, turnSubmitted);
        } finally {
            activeTurn = null;
            activeTurnThreadId = null;
            activeTurnId = null;
            activeClient = null;
            userInterrupted.set(false);
            CliTempDir.deleteFilesQuietly(tempFiles);
        }
    }

    /**
     * 解析本轮 thread:优先 request.sessionId(前端持有的用户会话)→ 实例 threadId → 新建。
     * resume 失效(线程不存在 / 已删除)→ 清 id 建新线程(B13 等价,thread 未接受任何
     * 本轮输入,新建安全)。
     */
    private String resolveThread(CodexAppServerClient client, CliSendRequest request) throws Exception {
        String requested = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId().trim() : threadId;
        if (requested == null || requested.isBlank()) {
            return startThread(client, request);
        }
        JsonObject resumeParams = new JsonObject();
        resumeParams.addProperty("threadId", requested);
        String cwd = effectiveCwd(request);
        if (cwd != null) {
            resumeParams.addProperty("cwd", cwd);
        }
        try {
            JsonObject resumed = client.request(CliConstants.CODEX_APPSERVER_METHOD_THREAD_RESUME, resumeParams)
                    .get(CliConstants.CODEX_APPSERVER_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            String resumedId = extractThreadId(resumed);
            threadId = resumedId != null ? resumedId : requested;
            return threadId;
        } catch (Exception e) {
            LOG.info("[CodexAppServerSession][" + tabId + "] thread resume failed (threadId=" + requested
                    + "), starting fresh thread: " + e.getMessage());
            threadId = null;
            return startThread(client, request);
        }
    }

    private String startThread(CodexAppServerClient client, CliSendRequest request) throws Exception {
        JsonObject params = new JsonObject();
        String cwd = effectiveCwd(request);
        if (cwd != null) {
            params.addProperty("cwd", cwd);
        }
        // sandbox 是 thread 级配置(扁平 SandboxMode 字符串,契约确认);审批/模型/档位逐轮覆盖
        CodexCliCommandUtils.PermissionSelection perm = selectPermission(request);
        params.addProperty("sandbox", perm.sandbox());
        JsonObject started = client.request(CliConstants.CODEX_APPSERVER_METHOD_THREAD_START, params)
                .get(CliConstants.CODEX_APPSERVER_CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        String startedId = extractThreadId(started);
        if (startedId == null || startedId.isBlank()) {
            throw new IllegalStateException("thread/start response missing thread id");
        }
        threadId = startedId;
        LOG.info("[CodexAppServerSession][" + tabId + "] app-server thread created: " + startedId);
        return startedId;
    }

    /**
     * turn/start 参数:threadId + input(text + localImage)+ 逐轮覆盖
     * (model / effort / summary / service tier / 审批,契约字段一一对应 one-shot 的
     * -m / -c model_reasoning_effort / -c model_reasoning_summary / -c service_tier /
     * --ask-for-approval + -c approvals_reviewer;sandbox 为 thread 级,在 startThread 处设置)。
     */
    JsonObject buildTurnStartParams(String threadId, CliSendRequest request, List<File> tempFiles) {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        String cwd = effectiveCwd(request);
        if (cwd != null) {
            params.addProperty("cwd", cwd);
        }
        String effectiveModel = firstNonBlank(request.actualModel(), request.model());
        if (effectiveModel != null && !effectiveModel.isBlank()) {
            params.addProperty("model", effectiveModel);
        }
        String effort = ReasoningEffortResolver.clamp(request.reasoningEffort(),
                ReasoningCapabilities.levelsFor(ProviderType.CODEX.value(), effectiveModel));
        if (effort != null) {
            params.addProperty("effort", effort);
        }
        // 对齐 one-shot 的 -c model_reasoning_summary=auto:思考区开 → 请求推理摘要流
        // (有能力的部署经 summaryTextDelta 增量下发;块到达的部署由 Turn 的完成块兜底)
        if (request.thinkingOutputEnabled()) {
            params.addProperty("summary", CliConstants.CODEX_REASONING_SUMMARY_AUTO);
        }
        appendServiceTierOverride(params, request, effectiveModel);
        CodexCliCommandUtils.PermissionSelection selection = selectPermission(request);
        params.addProperty("approvalPolicy", selection.approval());
        params.addProperty("approvalsReviewer", selection.approvalsReviewer());

        JsonArray input = new JsonArray();
        JsonObject textInput = new JsonObject();
        textInput.addProperty("type", "text");
        textInput.addProperty("text", AbstractRunOnceCliSession.buildPromptText(request));
        input.add(textInput);
        for (File image : materializeAttachments(request, tempFiles)) {
            JsonObject imageInput = new JsonObject();
            imageInput.addProperty("type", "localImage");
            imageInput.addProperty("path", image.getAbsolutePath());
            input.add(imageInput);
        }
        params.add("input", input);
        return params;
    }

    private void appendServiceTierOverride(JsonObject params, CliSendRequest request, String model) {
        String requestedTier = request.codexServiceTier();
        if (requestedTier == null || requestedTier.isBlank()) {
            return;
        }
        if (!serviceTierPolicy.supports(model, requestedTier)) {
            LOG.info("[CodexAppServerSession][" + tabId + "] Service tier override ignored because the selected"
                    + " model does not advertise it: model=" + (model != null ? model : "(default)")
                    + ", tier=" + requestedTier);
            return;
        }
        params.addProperty("serviceTier", requestedTier);
    }

    private void awaitTurnCompletion(CodexAppServerTurn turn, CliSessionCallback callback) throws Exception {
        CodexAppServerTurn.TurnResult result;
        try {
            result = turn.completion().get(CliConstants.CLI_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 轮挂死:杀 app-server(当轮失败不重发;manager 摘除句柄,下轮重建)
            if (appServerManager != null) {
                appServerManager.terminateAppServer("turn timeout");
            }
            String err = CliErrorFormatter.formatError(ProviderType.CODEX.displayLabel(), "app-server turn timed out");
            callback.onError(err);
            callback.onComplete(false, turn.accumulatedText(), err);
            return;
        }
        switch (result.kind()) {
            case COMPLETED -> {
                turn.emitStreamEndIfStarted();
                callback.onComplete(true, turn.accumulatedText(), null);
            }
            case INTERRUPTED ->
                    callback.onInterrupted(turn.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
            default -> {
                String err = CliErrorFormatter.formatError(ProviderType.CODEX.displayLabel(), result.error());
                if (userInterrupted.get() || turn.wasInterrupted()) {
                    callback.onInterrupted(turn.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    turn.emitStreamEndIfStarted();
                    callback.onError(err);
                    callback.onComplete(false, turn.accumulatedText(), err);
                }
            }
        }
    }

    /**
     * 失败收尾,按「turn 是否已递交 app-server」分派(对齐 OpenCodeServeSession):
     * 未递交(turn/start 之前的任何失败:thread 解析 / 参数构造 / 注册监听器等,不限异常类型)
     * → 当轮整体降级 one-shot(prompt 从未到达,重发安全);已递交(await 阶段的异常)
     * → 只报错不降级,防双发。
     */
    private void handleTurnFailure(CliSendRequest request, CliSessionCallback callback,
                                   Throwable e, boolean turnSubmitted) {
        if (userInterrupted.get()) {
            callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
            return;
        }
        if (turnSubmitted) {
            reportTurnError(callback, null, e);
            return;
        }
        LOG.warn("[CodexAppServerSession][" + tabId
                + "] app-server turn failed before turn submitted, degrading to one-shot", e);
        // 先清 app-server 轮状态:降级期间 interrupt() 应路由到 one-shot 进程而非 turn/interrupt
        activeTurn = null;
        activeTurnThreadId = null;
        activeTurnId = null;
        activeClient = null;
        try {
            fallbackSession().send(request, callback).join();
        } catch (Exception fallbackFailure) {
            // 降级路径自身失败:兜底报错收尾,绝不让异常无声穿透 dispatch 链
            LOG.warn("[CodexAppServerSession][" + tabId + "] one-shot fallback itself failed", fallbackFailure);
            reportTurnError(callback, null, fallbackFailure);
        }
    }

    /** 统一错误报告(中断感知):已标记中断走 onInterrupted,否则 onError + onComplete(false)。 */
    private void reportTurnError(CliSessionCallback callback, CodexAppServerTurn turn, Throwable e) {
        if (userInterrupted.get() || (turn != null && turn.wasInterrupted())) {
            callback.onInterrupted(turn != null ? turn.accumulatedText() : null,
                    CliConstants.I18N_REQUEST_INTERRUPTED);
            return;
        }
        LOG.warn("[CodexAppServerSession][" + tabId + "] app-server turn failed", e);
        String err = CliErrorFormatter.formatError(ProviderType.CODEX.displayLabel(), e.getMessage());
        callback.onError(err);
        callback.onComplete(false, turn != null ? turn.accumulatedText() : null, err);
    }

    // ── 权限拦截 ──────────────────────────────────────────────────────────────

    /**
     * 审批请求拦截:app-server 模式工具调用经 server 主动请求({@code item/commandExecution/
     * requestApproval}、{@code item/fileChange/requestApproval})征询,须 JSON-RPC 应答。
     * <ul>
     *   <li>bypass 权限模式 → 直接应答 acceptForSession(等价 one-shot --dangerously-bypass);
     *   <li>其余模式且已注入 {@link AppServerPermissionGate} → 转发插件权限对话体系
     *       (决策记忆命中直接应答;否则前端对话框,用户决策映射 allow→accept /
     *       allow_always→acceptForSession / deny→decline),对话框超时 / 会话关闭 /
     *       任何异常兜底 decline(保守,decline 后 turn 继续);</li>
     *   <li>闸口缺失(测试 / 无 Project 路径)→ 保守 MVP:非 bypass 一律 decline;</li>
     *   <li>非审批类 server 请求 → -32601 错误应答(协议正确的「不支持」)。</li>
     * </ul>
     */
    CodexAppServerClient.TurnEventHandler wrapWithPermissionIntercept(
            CodexAppServerClient client, String turnThreadId, CodexAppServerTurn turn, CliSendRequest request) {
        boolean bypass = CommonConstants.PERMISSION_MODE_BYPASS.equals(request.permissionMode());
        return new CodexAppServerClient.TurnEventHandler() {
            @Override
            public void onNotification(JsonObject notification) {
                captureTurnIdFromNotification(notification);
                turn.onNotification(notification);
            }

            @Override
            public void onServerRequest(JsonObject serverRequest) {
                handleServerRequest(client, turnThreadId, request, bypass, serverRequest);
            }

            @Override
            public void onProcessDied(String reason) {
                turn.onProcessDied(reason);
            }
        };
    }

    /**
     * turn/started 通知兜底捕获 turnId:turn/start 响应尚未返回时用户即点中断的窗口内,
     * interrupt() 也能拿到 turnId 走确定性 turn/interrupt(响应与通知携带同一 turn 对象)。
     */
    private void captureTurnIdFromNotification(JsonObject notification) {
        if (activeTurnId != null || !notification.has("method")
                || !CliConstants.CODEX_APPSERVER_NOTIFY_TURN_STARTED.equals(notification.get("method").getAsString())) {
            return;
        }
        JsonObject params = notification.has("params") && notification.get("params").isJsonObject()
                ? notification.getAsJsonObject("params") : null;
        if (params == null) {
            return;
        }
        JsonObject turn = params.has("turn") && params.get("turn").isJsonObject()
                ? params.getAsJsonObject("turn") : null;
        String turnId = turn != null ? getString(turn, "id") : null;
        if (turnId != null) {
            activeTurnId = turnId;
        }
    }

    private void handleServerRequest(CodexAppServerClient client, String turnThreadId,
                                     CliSendRequest request, boolean bypass, JsonObject serverRequest) {
        long id = serverRequest.has("id") && !serverRequest.get("id").isJsonNull()
                ? serverRequest.get("id").getAsLong() : -1L;
        String method = serverRequest.has("method") && !serverRequest.get("method").isJsonNull()
                ? serverRequest.get("method").getAsString() : null;
        if (id < 0 || method == null) {
            return;
        }
        if (!CliConstants.CODEX_APPSERVER_REQUEST_COMMAND_APPROVAL.equals(method)
                && !CliConstants.CODEX_APPSERVER_REQUEST_FILE_CHANGE_APPROVAL.equals(method)) {
            client.respondErrorToServer(id, -32601, "unsupported server request: " + method);
            return;
        }
        JsonObject params = serverRequest.has("params") && serverRequest.get("params").isJsonObject()
                ? serverRequest.getAsJsonObject("params") : new JsonObject();
        if (bypass || permissionGate == null) {
            respondDecisionQuietly(client, id,
                    bypass ? CliConstants.CODEX_APPSERVER_DECISION_ACCEPT_FOR_SESSION
                            : CliConstants.CODEX_APPSERVER_DECISION_DECLINE);
            return;
        }
        String toolName = approvalToolName(method);
        JsonObject inputs = approvalInputs(method, params);
        String cwd = effectiveCwd(request);
        CliSessionExecutor.runAsync(() ->
                permissionGate.ask(toolName, inputs, cwd)
                        .thenAccept(decision -> respondDecisionQuietly(client, id, decision))
                        .exceptionally(e -> {
                            LOG.warn("[CodexAppServerSession][" + tabId + "] permission gate failed: "
                                    + e.getMessage());
                            respondDecisionQuietly(client, id, CliConstants.CODEX_APPSERVER_DECISION_DECLINE);
                            return null;
                        }));
    }

    /** commandExecution 审批 → 工具名 Bash(command 入参);fileChange 审批 → Edit(changes 入参)。 */
    private static String approvalToolName(String method) {
        return CliConstants.CODEX_APPSERVER_REQUEST_FILE_CHANGE_APPROVAL.equals(method)
                ? "Edit" : "Bash";
    }

    private static JsonObject approvalInputs(String method, JsonObject params) {
        JsonObject inputs = new JsonObject();
        if (CliConstants.CODEX_APPSERVER_REQUEST_COMMAND_APPROVAL.equals(method)) {
            inputs.addProperty("command", getString(params, "command"));
        } else if (params.has("changes") && params.get("changes").isJsonArray()) {
            inputs.add("changes", params.get("changes").getAsJsonArray());
        }
        String cwd = getString(params, "cwd");
        if (cwd != null) {
            inputs.addProperty("cwd", cwd);
        }
        return inputs.isEmpty() ? params : inputs;
    }

    /** 审批决策应答(异步、不阻塞 reader 线程):{@code {id, result:{decision}}}。 */
    private void respondDecisionQuietly(CodexAppServerClient client, long id, String decision) {
        JsonObject result = new JsonObject();
        result.addProperty("decision", decision);
        CliSessionExecutor.runAsync(() -> client.respondToServer(id, result));
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /** 图片附件物化为磁盘文件(与 one-shot 同一处理函数);失败降级为空列表,不阻塞主消息。 */
    private List<File> materializeAttachments(CliSendRequest request, List<File> tempFiles) {
        try {
            return attachmentHandler.processForCodex(request.attachments(), tempFiles);
        } catch (Exception e) {
            LOG.warn("[CodexAppServerSession][" + tabId + "] process attachments failed", e);
            return List.of();
        }
    }

    /** 审批/沙箱选择(SSOT 复用 one-shot 的 CodexCliCommandUtils.selectPermission,总则四)。 */
    private static CodexCliCommandUtils.PermissionSelection selectPermission(CliSendRequest request) {
        return CodexCliCommandUtils.selectPermission(request.permissionMode(),
                CliSettings.getCodexSandboxMode(effectiveCwd(request)));
    }

    private CliSession fallbackSession() {
        if (fallbackOverride != null) {
            return fallbackOverride;
        }
        CliSession fallback = fallbackSession;
        if (fallback == null) {
            synchronized (this) {
                if (fallbackSession == null) {
                    fallbackSession = new CodexCliSession(tabId, gatewayService, lifecycleService);
                }
                fallback = fallbackSession;
            }
        }
        return fallback;
    }

    /** cwd:null/空 → null(由 manager spawn / thread 参数处回退),对称 one-shot 的 cwd 回退。 */
    private static String effectiveCwd(CliSendRequest request) {
        String cwd = request.cwd();
        return cwd != null && !cwd.isBlank() ? cwd : null;
    }

    private static void emitSessionId(CliSessionCallback callback, String sessionId) {
        new CliSectionEmitter(callback::onMessage).sessionId(sessionId);
    }

    /** thread/start|resume 响应:{thread:{id}}(0.153.4 实测);兼容顶层 id。 */
    private static @Nullable String extractThreadId(JsonObject response) {
        if (response == null) {
            return null;
        }
        JsonObject thread = response.has("thread") && response.get("thread").isJsonObject()
                ? response.getAsJsonObject("thread") : null;
        return thread != null ? getString(thread, "id") : getString(response, "id");
    }

    /** turn/start 响应:{turn:{id}}(0.153.4 实测);兼容顶层 id。 */
    private static @Nullable String extractTurnId(JsonObject response) {
        if (response == null) {
            return null;
        }
        JsonObject turn = response.has("turn") && response.get("turn").isJsonObject()
                ? response.getAsJsonObject("turn") : null;
        return turn != null ? getString(turn, "id") : getString(response, "id");
    }

    private static @Nullable String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = object.get(key).getAsString();
            return value == null || value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
