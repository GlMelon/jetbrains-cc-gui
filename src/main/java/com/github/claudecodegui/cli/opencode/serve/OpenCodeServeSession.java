package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliPersistentFeatureFlags;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.opencode.OpenCodeCliSession;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.CliTempDir;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.asObject;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.getString;

/**
 * opencode serve 托管流式会话({@code opencode serve} + HTTP/SSE,token 级增量)。
 * <p>
 * 与 one-shot {@link OpenCodeCliSession} 的差异仅在传输层:控制面走
 * {@link OpenCodeServeClient} 的 HTTP 调用,事件面走 SSE 长连接(事件 → MSG_* 映射在
 * {@link OpenCodeServeTurn},纯函数与 one-shot 共用 OpenCodeEventMapper)。
 * 会话 id 续接语义与 one-shot 对齐:优先 request.sessionId()(前端持有的用户会话),
 * 实例字段兜底;prompt_async 对失效 session 返回 4xx → 清 sessionId 建新会话重试一次
 * (B13 等价物,serve 的 session 存 opencode.db,重启后仍在)。
 * <p>
 * 降级语义(对齐 CliPersistentProcessRegistry acquire 未命中的静默降级):serve 不可用
 * (门禁关 / 熔断 / spawn 失败 / acquire 异常)或 prompt 递交前的任何失败(不限异常类型,
 * 含 createSession 超时/IO)→ 当轮整体降级 one-shot;<b>已递交 / 递交结果未知的轮不重发</b>
 * (prompt_async 可能已被 serve 接受,重发会双发消息),断线 / 超时当轮报错,下轮由 manager 重建后再试。
 * <p>
 * interrupt:POST abort(确定性取消,优于杀树——serve 是 project 级共享进程,
 * 杀树会波及其他 tab 的进行轮),{@link CliConstants#CLI_INTERRUPT_FALLBACK_MS} 无回应
 * 则 {@link OpenCodeServeManager#terminateServe} 兜底杀树。
 */
public class OpenCodeServeSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(OpenCodeServeSession.class);

    private final String tabId;
    private final OpenCodeServeManager serveManager;
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;
    private final CliAttachmentHandler attachmentHandler = new CliAttachmentHandler();
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    /** 测试 seam:注入已连接的 serve client 时跳过 manager acquire/spawn(生产恒 null)。 */
    private final OpenCodeServeClient injectedClient;
    /** 测试 seam:one-shot 兜底替身(生产恒 null,走懒创建的真实 OpenCodeCliSession)。 */
    private final CliSession fallbackOverride;

    /** 当前 session id(首轮创建后回写,续接复用;语义对齐 one-shot 实例字段)。 */
    private volatile String sessionId;
    private volatile OpenCodeServeTurn activeTurn;
    private volatile String activeTurnSessionId;
    private volatile OpenCodeServeClient activeClient;
    /** one-shot 兜底会话(懒创建;降级轮与 interrupt 委托用它)。 */
    private volatile CliSession fallbackSession;

    public OpenCodeServeSession(String tabId, OpenCodeServeManager serveManager,
                                McpGatewayService gatewayService,
                                LifecycleObservabilityService lifecycleService) {
        this.tabId = tabId;
        this.serveManager = serveManager;
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
        this.injectedClient = null;
        this.fallbackOverride = null;
    }

    /** 测试 seam 构造:注入已连接的 serve client(绕过 manager acquire/spawn)与兜底会话替身。 */
    OpenCodeServeSession(String tabId, OpenCodeServeClient injectedClient, CliSession fallbackOverride) {
        this.tabId = tabId;
        this.serveManager = null;
        this.gatewayService = null;
        this.lifecycleService = null;
        this.injectedClient = injectedClient;
        this.fallbackOverride = fallbackOverride;
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
        OpenCodeServeClient client = injectedClient;
        if (client == null && serveManager != null && CliPersistentFeatureFlags.isOpenCodeServeEnabled()) {
            try {
                client = serveManager.acquire(tabId, effectiveCwd(request), request.extraEnv());
            } catch (Exception | LinkageError e) {
                // acquire 同步异常(spawn/指纹计算等未预期失败):记日志并降级 one-shot——
                // 不让异常穿透 dispatch 链、只喂前端错误而后端无任何日志
                LOG.warn("[OpenCodeServeSession][" + tabId + "] serve acquire failed, degrading to one-shot", e);
                client = null;
            }
        }
        if (client == null) {
            return fallbackSession().send(request, callback);
        }
        final OpenCodeServeClient serveClient = client;
        return CliSessionExecutor.runAsync(() -> runServeTurn(serveClient, request, callback));
    }

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        OpenCodeServeTurn turn = activeTurn;
        OpenCodeServeClient client = activeClient;
        String turnSessionId = activeTurnSessionId;
        if (turn == null || client == null || turnSessionId == null) {
            CliSession fallback = fallbackSession;
            if (fallback != null) {
                fallback.interrupt();
            }
            return;
        }
        turn.markInterrupted();
        CliSessionExecutor.runAsync(() -> {
            try {
                client.abort(turnSessionId);
            } catch (Exception e) {
                LOG.warn("[OpenCodeServeSession][" + tabId + "] abort request failed, terminating serve", e);
                if (serveManager != null) {
                    serveManager.terminateServe("abort request failed: " + e.getMessage());
                }
            }
        });
        // 兜底:abort 后无 session.error/idle 回应 → 杀 serve 进程树(当轮经流关闭收尾为中断)
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (!turn.completion().isDone() && serveManager != null) {
                LOG.warn("[OpenCodeServeSession][" + tabId + "] abort fallback timeout, terminating serve");
                serveManager.terminateServe("abort fallback timeout");
            }
        }, CliConstants.CLI_INTERRUPT_FALLBACK_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispose() {
        interrupt();
    }

    // ── serve 轮 ─────────────────────────────────────────────────────────────

    private void runServeTurn(OpenCodeServeClient client, CliSendRequest request, CliSessionCallback callback) {
        List<File> tempFiles = new ArrayList<>();
        String turnSessionId = null;
        // prompt 是否已递交成功(promptAsync 正常返回):失败收尾按此分派,而非按异常类型——
        // 未递交的任何失败(不限类型)重发安全,降级 one-shot;已递交/递交结果未知不降级,防双发。
        boolean promptSubmitted = false;
        try {
            callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());
            String requestedSessionId = request.sessionId();
            String effectiveSessionId = requestedSessionId != null && !requestedSessionId.isBlank()
                    ? requestedSessionId.trim() : sessionId;
            boolean resuming = effectiveSessionId != null;
            if (effectiveSessionId == null) {
                effectiveSessionId = client.createSession(effectiveCwd(request));
                sessionId = effectiveSessionId;
                LOG.info("[OpenCodeServeSession][" + tabId + "] serve session created: " + effectiveSessionId);
            }
            turnSessionId = effectiveSessionId;

            JsonObject body = buildPromptBody(request, tempFiles);
            OpenCodeServeTurn turn = new OpenCodeServeTurn(callback);
            activeTurn = turn;
            activeTurnSessionId = turnSessionId;
            activeClient = client;
            client.registerTurnHandler(turnSessionId, wrapWithPermissionIntercept(client, turnSessionId, turn, request));
            emitSessionId(callback, turnSessionId);
            callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.AWAITING_MODEL.value());

            // promptAsync 自身抛出的异常(含 retry 第二次失败)就地报告、不降级:
            // 无法判定 serve 是否已接受,重发会双发。仅 4xx 续接失效走 B13 等价重试。
            try {
                client.promptAsync(turnSessionId, body);
                promptSubmitted = true;
            } catch (OpenCodeServeClient.ServeApiException e) {
                if (!e.isClientError() || !resuming) {
                    client.unregisterTurnHandler(turnSessionId);
                    reportTurnError(callback, null, e);
                    return;
                }
                // B13 等价:续接 session 失效 → 清 id 建新会话重试一次(prompt 未被接受,可安全重发)
                LOG.info("[OpenCodeServeSession][" + tabId + "] continuation session invalidated (HTTP "
                        + e.statusCode() + "), retrying with fresh serve session");
                client.unregisterTurnHandler(turnSessionId);
                sessionId = null;
                // 此处 createSession/register 失败进外层 catch:retry 的 prompt 尚未递交,降级安全
                turnSessionId = client.createSession(effectiveCwd(request));
                sessionId = turnSessionId;
                activeTurnSessionId = turnSessionId;
                client.registerTurnHandler(turnSessionId,
                        wrapWithPermissionIntercept(client, turnSessionId, turn, request));
                emitSessionId(callback, turnSessionId);
                try {
                    client.promptAsync(turnSessionId, body);
                    promptSubmitted = true;
                } catch (Exception retryFailure) {
                    client.unregisterTurnHandler(turnSessionId);
                    reportTurnError(callback, null, retryFailure);
                    return;
                }
            } catch (Exception e) {
                // promptAsync 的 IO/超时等:递交结果未知,不降级,防双发
                client.unregisterTurnHandler(turnSessionId);
                reportTurnError(callback, null, e);
                return;
            }

            awaitTurnCompletion(turn, callback);
        } catch (Exception | LinkageError e) {
            if (turnSessionId != null) {
                client.unregisterTurnHandler(turnSessionId);
            }
            handleTurnFailure(request, callback, e, promptSubmitted);
        } finally {
            activeTurn = null;
            activeTurnSessionId = null;
            activeClient = null;
            userInterrupted.set(false);
            CliTempDir.deleteFilesQuietly(tempFiles);
        }
    }

    private void awaitTurnCompletion(OpenCodeServeTurn turn, CliSessionCallback callback) throws Exception {
        OpenCodeServeTurn.TurnResult result;
        try {
            result = turn.completion().get(CliConstants.CLI_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 轮挂死:杀 serve(当轮失败不重发;manager 摘除句柄,下轮重建)
            if (serveManager != null) {
                serveManager.terminateServe("turn timeout");
            }
            String err = CliErrorFormatter.formatError(ProviderType.OPENCODE.displayLabel(), "serve turn timed out");
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
                String err = CliErrorFormatter.formatError(ProviderType.OPENCODE.displayLabel(), result.error());
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
     * 失败收尾,按「prompt 是否已递交 serve」分派(不再按异常类型):
     * <ul>
     *   <li>未递交(promptAsync 之前的任何失败:createSession 超时/IO/4xx、请求体构造、
     *       注册监听器等,不限异常类型)→ 当轮整体降级 one-shot
     *       (prompt 从未到达 serve,重发安全;对齐 acquire 未命中的静默降级);</li>
     *   <li>已递交(await 阶段的异常)→ 只报错不降级,防双发;
     *       promptAsync 自身抛出的异常(递交结果未知)已在递交处就地报告,不进此方法。</li>
     * </ul>
     */
    private void handleTurnFailure(CliSendRequest request, CliSessionCallback callback,
                                   Throwable e, boolean promptSubmitted) {
        if (userInterrupted.get()) {
            callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
            return;
        }
        if (promptSubmitted) {
            reportTurnError(callback, null, e);
            return;
        }
        LOG.warn("[OpenCodeServeSession][" + tabId
                + "] serve turn failed before prompt submitted, degrading to one-shot", e);
        // 先清 serve 轮状态:降级期间 interrupt() 应路由到 one-shot 进程而非 serve abort
        activeTurn = null;
        activeTurnSessionId = null;
        activeClient = null;
        try {
            fallbackSession().send(request, callback).join();
        } catch (Exception fallbackFailure) {
            // 降级路径自身失败(join 中断/回调异常等):兜底报错收尾,
            // 绝不让异常无声穿透 dispatch 链(只喂前端而后端无日志)
            LOG.warn("[OpenCodeServeSession][" + tabId + "] one-shot fallback itself failed", fallbackFailure);
            reportTurnError(callback, null, fallbackFailure);
        }
    }

    /** 统一错误报告(中断感知):已标记中断走 onInterrupted,否则 onError + onComplete(false)。 */
    private void reportTurnError(CliSessionCallback callback, OpenCodeServeTurn turn, Throwable e) {
        if (userInterrupted.get() || (turn != null && turn.wasInterrupted())) {
            callback.onInterrupted(turn != null ? turn.accumulatedText() : null,
                    CliConstants.I18N_REQUEST_INTERRUPTED);
            return;
        }
        LOG.warn("[OpenCodeServeSession][" + tabId + "] serve turn failed", e);
        String err = CliErrorFormatter.formatError(ProviderType.OPENCODE.displayLabel(), e.getMessage());
        callback.onError(err);
        callback.onComplete(false, turn != null ? turn.accumulatedText() : null, err);
    }

    // ── 请求体构造 ────────────────────────────────────────────────────────────

    /**
     * prompt_async 请求体:{model:{providerID, modelID}, variant?, parts:[text + file...]}。
     * model 把插件的 "provider/model" 串拆两段(无 '/' 时告警并省略 model,用 serve 会话默认);
     * variant 复用 one-shot 的 reasoningEffort→variant 映射(SSOT,总则四)。
     */
    JsonObject buildPromptBody(CliSendRequest request, List<File> tempFiles) {
        JsonObject body = new JsonObject();
        String model = firstNonBlank(request.actualModel(), request.model());
        if (model != null) {
            JsonObject modelRef = splitModelRef(model);
            if (modelRef != null) {
                body.add("model", modelRef);
            } else {
                // 无 '/' 无法拆 providerID/modelID(serve API 要求双段),此前静默丢弃
                // 用户模型选择;显式告警,行为仍为省略(用 serve 会话默认模型)。
                LOG.warn("[OpenCodeServeSession][" + tabId + "] model '" + model
                        + "' is not in 'provider/model' form; model selection dropped,"
                        + " using serve session default model");
            }
        }
        String variant = AbstractRunOnceCliSession.mapReasoningVariant(request.reasoningEffort());
        if (variant != null) {
            body.addProperty("variant", variant);
        }
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", AbstractRunOnceCliSession.buildPromptText(request));
        parts.add(textPart);
        for (File file : materializeAttachments(request, tempFiles)) {
            JsonObject filePart = new JsonObject();
            filePart.addProperty("type", "file");
            filePart.addProperty("mime", guessImageMime(file.getName()));
            // file:// URL 替代 one-shot 的 -f 附件参数
            filePart.addProperty("url", file.toURI().toString());
            filePart.addProperty("filename", file.getName());
            parts.add(filePart);
        }
        body.add("parts", parts);
        return body;
    }

    /**
     * "provider/model" → serve 双段 modelRef;无 '/' 或段为空返回 null
     * (serve API 要求 providerID + modelID 双段,单段无安全回退)。
     */
    static JsonObject splitModelRef(String model) {
        if (model == null) {
            return null;
        }
        int slash = model.indexOf('/');
        if (slash <= 0 || slash >= model.length() - 1) {
            return null;
        }
        JsonObject modelRef = new JsonObject();
        modelRef.addProperty("providerID", model.substring(0, slash));
        modelRef.addProperty("modelID", model.substring(slash + 1));
        return modelRef;
    }

    /** 图片附件物化为磁盘文件(与 one-shot 同一处理函数);失败降级为空列表,不阻塞主消息。 */
    private List<File> materializeAttachments(CliSendRequest request, List<File> tempFiles) {
        try {
            return attachmentHandler.processForCodex(request.attachments(), tempFiles);
        } catch (Exception e) {
            LOG.warn("[OpenCodeServeSession][" + tabId + "] process attachments failed", e);
            return List.of();
        }
    }

    /**
     * 权限请求拦截:serve 模式工具调用发 permission.asked 事件,须 HTTP 应答。
     * MVP 映射(有意差异,见 AGENTS.md 总则六):bypass 权限模式 → "always",其他 → "reject"
     * (one-shot 的 --auto 只自动批准未被 deny 的项,语义强于 reject;serve 无等价细粒度
     * 应答,MVP 保守拒绝,交互式授权留待后续)。
     */
    private OpenCodeServeClient.TurnEventHandler wrapWithPermissionIntercept(
            OpenCodeServeClient client, String turnSessionId, OpenCodeServeTurn turn, CliSendRequest request) {
        boolean bypass = CommonConstants.PERMISSION_MODE_BYPASS.equals(request.permissionMode());
        return new OpenCodeServeClient.TurnEventHandler() {
            @Override
            public void onEvent(JsonObject event) {
                if (OpenCodeServeTurn.EVENT_PERMISSION_ASKED.equals(getString(event, "type"))) {
                    JsonObject properties = asObject(event, "properties");
                    String permissionId = properties != null
                            ? firstNonBlank(getString(properties, "id"), getString(properties, "permissionID"))
                            : null;
                    if (permissionId == null) {
                        return;
                    }
                    String response = bypass ? "always" : "reject";
                    CliSessionExecutor.runAsync(() -> {
                        try {
                            client.respondPermission(turnSessionId, permissionId, response);
                        } catch (Exception e) {
                            LOG.warn("[OpenCodeServeSession][" + tabId + "] permission respond failed: "
                                    + e.getMessage());
                        }
                    });
                    return;
                }
                turn.onEvent(event);
            }

            @Override
            public void onStreamClosed(String reason) {
                turn.onStreamClosed(reason);
            }
        };
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private CliSession fallbackSession() {
        if (fallbackOverride != null) {
            return fallbackOverride;
        }
        CliSession fallback = fallbackSession;
        if (fallback == null) {
            synchronized (this) {
                if (fallbackSession == null) {
                    fallbackSession = new OpenCodeCliSession(tabId, gatewayService, lifecycleService);
                }
                fallback = fallbackSession;
            }
        }
        return fallback;
    }

    /** cwd:null/空 → null(由 manager spawn / createSession 处回退),对称 one-shot 的 cwd 回退。 */
    private static String effectiveCwd(CliSendRequest request) {
        String cwd = request.cwd();
        return cwd != null && !cwd.isBlank() ? cwd : null;
    }

    private static void emitSessionId(CliSessionCallback callback, String sessionId) {
        new CliSectionEmitter(callback::onMessage).sessionId(sessionId);
    }

    /** 附件 mime 按扩展名推断(MVP:仅图片附件,默认 png)。 */
    private static String guessImageMime(String fileName) {
        if (fileName == null) {
            return "image/png";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
