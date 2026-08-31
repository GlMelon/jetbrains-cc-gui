package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliEnvironmentBuilder;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliProcessHandle;
import com.github.claudecodegui.cli.common.CliPromptContexts;
import com.github.claudecodegui.cli.common.CliProcessLifecycle;
import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.session.SessionCapabilityChannel;
import com.github.claudecodegui.session.SessionCapabilityDegradationReason;
import com.github.claudecodegui.session.SessionCapabilityState;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.service.lifecycle.LifecycleEventType;
import com.github.claudecodegui.service.lifecycle.LifecycleProcessKind;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * kimi ACP 通道会话:直 spawn {@code kimi acp}(ACP server over stdio),不继承
 * {@link com.github.claudecodegui.cli.common.AbstractRunOnceCliSession}。
 *
 * <p><b>不继承基类的理由</b>:基类 {@code runOnce} 硬编码 {@code pb.redirectInput(stdinNullSink())}
 * (一次性 headless 流的零 stdin 模型),而 ACP 需保持 stdin 开着写多个握手请求
 * (initialize→session/new→set_config_option→session/prompt),仅 turn 结束才 close;
 * 基类 {@code activeHandle}/{@code userInterrupted} 均 private,继承后须全量覆写——
 * 继承收益只剩 {@code resolver()}/{@code buildPromptText} 两个小工具,代价是模板方法整体作废的
 * 坏味道 + 共享基类(grok/pi/opencode)回归面。{@link com.github.claudecodegui.cli.common.ChannelCliSession}
 * 已确立「非继承直实现 CliSession」先例,本类骨架对照之。
 *
 * <p><b>与 ChannelCliSession 的 stdin 差异</b>:ChannelCliSession stdin writer 写完 JSON 即关闭
 * (触发 ai-bridge 读 EOF 完成请求);ACP 必须保持 stdin 开着写多个握手请求,仅 turn 结束 close。
 *
 * <p><b>stderr 必须 {@code redirectErrorStream(false)}</b>:ACP stdout 是纯 NDJSON 协议流,
 * 混入 stderr 日志会破坏解析(基类合并 stderr 是为 headless 一次性流设计)。
 *
 * <p>协议事实(0.38.0 实测 + 源码级确认)见 {@link KimiAcpProtocol}。
 */
public class KimiAcpCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(KimiAcpCliSession.class);

    private static final String KIMI_PROVIDER_LABEL = "Kimi";
    private static final String ACP_SUBCOMMAND = "acp";
    private static final long HANDSHAKE_TIMEOUT_MS = 30_000L;
    private static final long SET_CONFIG_TIMEOUT_MS = 10_000L;
    private static final long TURN_PROMPT_TIMEOUT_MS = CliConstants.CLI_REQUEST_TIMEOUT_MS;

    private final String tabId;
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;
    private final Gson gson = GsonHolder.GSON;
    private final ProviderCliResolver resolver = new ProviderCliResolver(ProviderType.KIMI, "kimi");

    private volatile CliProcessHandle activeHandle;
    private volatile KimiAcpConnection activeConnection;
    private volatile CliSendRequest activeLifecycleRequest;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private final AtomicLong turnSequence = new AtomicLong();
    private volatile long activeTurnId;
    private volatile String sessionId;

    // ── 长驻(一进程多 turn) ──────────────────────────────────────────────────
    // ACP 协议设计即为「一次 initialize,多 session/prompt」;run-once 每 turn spawn(~1-2s
    // node 冷启)是反 ACP 用法。长驻复用进程 + session,省 spawn+握手;interrupt 经
    // session/cancel notification 优雅取消 turn(实测 0.38:notification 后 stopReason=cancelled,
    // 不杀进程)。进程死/握手失败 → 清 persistent,下 turn 重建(session/new,上下文重建)。
    private volatile KimiAcpConnection persistentConn;
    private volatile String persistentSessionId;
    private volatile CliProcessHandle persistentHandle;
    private volatile Long persistentProcessGeneration;
    /** 当前 session 的思考档位目录(session/new 或 load 的 configOptions 解析),随 clearPersistent 清空。 */
    private volatile ThinkingOptions thinkingOptions;
    private volatile SessionNegotiatedCapabilities negotiatedCapabilities =
            SessionNegotiatedCapabilities.unknown();

    public KimiAcpCliSession(String tabId, McpGatewayService gatewayService) {
        this(tabId, gatewayService, null);
    }

    public KimiAcpCliSession(String tabId, McpGatewayService gatewayService,
                             LifecycleObservabilityService lifecycleService) {
        this.tabId = tabId;
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public SessionNegotiatedCapabilities capabilities() {
        return negotiatedCapabilities;
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        long turnId = turnSequence.incrementAndGet();
        activeTurnId = turnId;
        return CliSessionExecutor.runAsync(() -> {
            StringBuilder diagnostic = new StringBuilder();
            activeLifecycleRequest = request;
            try {
                // 续接 id 与 claude/codex(--resume 语义)对齐:优先用 request.sessionId()
                // (前端/state 持有的会话 id,历史回load、跨通道切换后仍指向用户认可的会话),
                // 本实例长驻缓存的 sessionId 仅作兜底。此前只看自身字段:插件重启/通道切换后
                // 直接 session/new 另起炉灶,用户会话上下文静默丢失。
                String requested = request.sessionId();
                String effectiveSessionId = requested != null && !requested.isBlank()
                        ? requested.trim() : sessionId;
                // B13:续接失败时清空 sessionId 与长驻连接,重试一次首轮流程
                boolean retry = runTurn(request, callback, effectiveSessionId, diagnostic);
                if (retry) {
                    LOG.info("[KimiAcpCliSession][" + tabId + "] continuation session invalidated; retrying as fresh turn");
                    sessionId = null;
                    clearPersistent();
                    diagnostic.setLength(0);
                    if (!userInterrupted.get()) {
                        runTurn(request, callback, null, diagnostic);
                    }
                }
            } catch (Exception | LinkageError e) {
                // LinkageError(NoClassDefFoundError 等)同样按 turn 失败收尾,防静默穿透
                LOG.warn("[KimiAcpCliSession][" + tabId + "] send failed", e);
                if (wasInterrupted()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                }
            } finally {
                if (activeTurnId == turnId) {
                    activeLifecycleRequest = null;
                    activeHandle = null;
                    activeTurnId = 0L;
                    userInterrupted.set(false);
                }
            }
        });
    }

    /**
     * 执行一次 ACP turn。返回 true 表示续接 session 失效应重试首轮(B13)。
     *
     * @param effectiveSessionId 续接 session id;null 表示首轮(直接 session/new)
     */
    private boolean runTurn(CliSendRequest request, CliSessionCallback callback,
                             String effectiveSessionId, StringBuilder diagnostic) {
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.MCP_SYNCING.value());
        McpGatewayCliConfig gatewayConfig = buildGatewayConfig(request);
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());

        KimiAcpStreamParser parser = new KimiAcpStreamParser(callback);
        // keepAlive:prompt 正常完成则 true(保持长驻进程);RPC 异常/超时/中断则 false(finally 清理)
        boolean keepAlive = false;
        KimiAcpConnection conn = null;
        String resolvedSessionId = null;
        CliProcessHandle currentHandle = null;
        boolean freshSpawn = false;
        Long currentProcessGeneration = null;

        try {
            // ── 复用长驻连接 or 新建 ──
            KimiAcpConnection existing = persistentConn;
            // 复用一致性:请求要续接的会话(id 与长驻缓存不同)时必须重建走 session/load,
            // 否则会把消息发进另一条会话(历史回load/切换会话场景)。
            String requestedId = request.sessionId();
            boolean reuseConsistent = requestedId == null || requestedId.isBlank()
                    || requestedId.trim().equals(persistentSessionId);
            if (existing != null && existing.isAlive() && persistentSessionId != null && reuseConsistent) {
                // 复用:省 spawn(~1-2s node 冷启)+ 握手 initialize/session/new
                conn = existing;
                resolvedSessionId = persistentSessionId;
                currentHandle = persistentHandle;
                currentProcessGeneration = persistentProcessGeneration;
                activeHandle = currentHandle;
                // 关键:通知行换绑到本轮新 parser(构造时绑定的旧 parser 已随上一 turn 结束)。
                // 另补 attachSessionId:复用分支不走 establishSession,本轮 title payload 需要它。
                existing.rebindLineSink(parser::parseLine);
                parser.attachSessionId(resolvedSessionId);
                LOG.debug("[KimiAcpCliSession][" + tabId + "] reusing persistent ACP connection, session=" + resolvedSessionId);
            } else {
                if (existing != null) {
                    // 长驻连接仍在但会话与请求不一致(或已死):优雅关闭,防孤儿进程。
                    LOG.debug("[KimiAcpCliSession][" + tabId + "] persistent ACP connection replaced (session mismatch or dead)");
                    clearPersistent();
                    try {
                        existing.close();
                    } catch (Exception ignored) {
                        // 忽略关闭异常
                    }
                }
                // 新建:spawn + initialize + session/new(或 load 续接)
                freshSpawn = true;
                String executable = resolver.findExecutable();
                if (executable == null || executable.isBlank()) {
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "kimi CLI not found");
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                    return false;
                }
                List<String> cmd = new ArrayList<>();
                cmd.add(executable);
                cmd.add(ACP_SUBCOMMAND);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                // 关键:stderr 与 stdout 分离(ACP stdout 是纯 NDJSON 协议流,不能混入 stderr)
                pb.redirectErrorStream(false);
                Map<String, String> cliEnv = pb.environment();
                cliEnv.clear();
                cliEnv.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
                cliEnv.put(CliConstants.ARG_NO_COLOR, "1");
                CliEnvironmentBuilder.configureProjectPath(cliEnv, request.cwd());
                CliEnvironmentBuilder.applyExtraEnv(cliEnv, request.extraEnv());
                if (gatewayConfig != null && gatewayConfig.usable()) {
                    cliEnv.putAll(gatewayConfig.environment());
                }
                if (request.cwd() != null && !request.cwd().isBlank()) {
                    File cwd = new File(request.cwd());
                    if (cwd.isDirectory()) {
                        pb.directory(cwd);
                    }
                }
                // 不 redirectInput(NUL):ACP 需保持 stdin 开着写握手请求
                Process process;
                try {
                    process = pb.start();
                } catch (Exception e) {
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "failed to start kimi acp: " + e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                    return false;
                }
                currentHandle = new CliProcessHandle(process, "kimi-acp-tab-" + tabId);
                final Long spawnedGeneration = lifecycleService != null
                        ? lifecycleService.nextProcessGeneration() : null;
                currentProcessGeneration = spawnedGeneration;
                recordLifecycle(LifecycleEventType.SPAWN, process, request, spawnedGeneration,
                        "ACP process spawned");
                activeHandle = currentHandle;
                if (userInterrupted.get()) {
                    currentHandle.interrupt();
                }
                conn = new KimiAcpConnection(process,
                        parser::parseLine,
                        (method, params) -> handleServerRequest(method, params),
                        line -> CliErrorFormatter.appendDiagnosticLine(diagnostic, line),
                        new KimiAcpConnection.LifecycleCallbacks() {
                            @Override
                            public void onStdinClose() {
                                recordLifecycle(LifecycleEventType.STDIN_CLOSE, process, request,
                                        spawnedGeneration, "ACP stdin closed");
                            }

                            @Override
                            public void onStdoutEof() {
                                recordLifecycle(LifecycleEventType.STDOUT_EOF, process, request,
                                        spawnedGeneration, "ACP stdout EOF");
                            }

                            @Override
                            public void onExit(int exitCode) {
                                recordLifecycle(LifecycleEventType.EXIT, process, request,
                                        spawnedGeneration, "ACP process exited: " + exitCode);
                            }

                            @Override
                            public void onTerminate() {
                                recordLifecycle(LifecycleEventType.TERMINATE, process, request,
                                        spawnedGeneration, "ACP process tree terminated");
                            }
                        });
                activeConnection = conn;
                conn.start();
                // ── 握手 ──
                try {
                    initialize(conn);
                    resolvedSessionId = establishSession(conn, request, effectiveSessionId, gatewayConfig, parser);
                } catch (KimiAcpConnection.AcpRpcException e) {
                    if (looksLikeSessionInvalidation(e.getMessage()) && effectiveSessionId != null) {
                        // B13:续接 session 失效 → 清长驻,重试首轮
                        clearPersistent();
                        return true;
                    }
                    String err = formatAcpError(e, conn);
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                    return false;
                } catch (KimiAcpConnection.AcpTimeoutException e) {
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "ACP handshake timed out: " + e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                    return false;
                } catch (Exception e) {
                    if (wasInterrupted()) {
                        callback.onInterrupted(parser.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
                        return false;
                    }
                    updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                            SessionCapabilityDegradationReason.ACP_NEGOTIATION_FAILED);
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "ACP handshake failed: " + e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                    return false;
                }
                // 握手成功 → 存长驻(下 turn 复用,省 spawn+握手)
                persistentConn = conn;
                persistentSessionId = resolvedSessionId;
                persistentHandle = currentHandle;
                persistentProcessGeneration = currentProcessGeneration;
                this.sessionId = resolvedSessionId;
            }

            // 开启思考(若用户启用)。档位经 configOptions 协商:合法值是模型动态的
            // (KimiAcpProtocol.THINKING 取值说明),这里先把通用档位映射成当前 session
            // 支持的值;setThinkingConfig 内的 "on" 回退仅作协商失手的最后防线。
            try {
                String desired = resolveThinkingValue(request);
                String negotiated = negotiateThinkingValue(desired, thinkingOptions);
                if (negotiated != null) {
                    if (!negotiated.equals(desired)) {
                        LOG.info("[KimiAcpCliSession][" + tabId + "] thinking effort '" + desired
                                + "' not supported by current model; negotiated to '" + negotiated + "'");
                    }
                    setThinkingConfig(conn, resolvedSessionId, negotiated);
                }
            } catch (Exception e) {
                LOG.warn("[KimiAcpCliSession][" + tabId + "] set thinking config failed (non-fatal)", e);
                updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                        SessionCapabilityDegradationReason.ACP_NEGOTIATION_FAILED);
                // 思考配置失败不致命,继续(prompt 仍可用,只是无 thought chunk)
            }

            // 开启重放门控 + 流开始
            parser.beginLiveTurn();
            callback.onMessage(CliConstants.MSG_STREAM_START, "");
            callback.onMessage(CliConstants.MSG_MESSAGE_START, "");
            callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.UNDERSTANDING.value());

            // ── session/prompt(等待响应期间 update 实时进 UI) ──
            try {
                JsonObject promptParams = new JsonObject();
                promptParams.addProperty(KimiAcpProtocol.FIELD_SESSION_ID, resolvedSessionId);
                promptParams.add(KimiAcpProtocol.FIELD_PROMPT, buildPromptBlocks(request));
                conn.request(KimiAcpProtocol.METHOD_SESSION_PROMPT, promptParams, TURN_PROMPT_TIMEOUT_MS);
                // prompt 正常完成 → 保持长驻进程(下 turn 复用)
                keepAlive = true;
            } catch (KimiAcpConnection.AcpRpcException e) {
                if (e.code == KimiAcpProtocol.ERROR_NOT_LOGGED_IN) {
                    updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                            SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED);
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL,
                            "kimi 未登录,请在终端运行 kimi 登录后重试 (-32000)");
                    callback.onError(err);
                    callback.onComplete(false, parser.accumulatedText(), err);
                    return false;
                }
                // prompt RPC 失败:session 失效或进程死 → 清长驻,下 turn 重建
                updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                        SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED);
                clearPersistent();
                String err = formatAcpError(e, conn);
                callback.onError(err);
                callback.onComplete(false, parser.accumulatedText(), err);
                return false;
            } catch (KimiAcpConnection.AcpTimeoutException e) {
                // 超时可能进程卡死 → 清长驻
                updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                        SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED);
                clearPersistent();
                String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "kimi 响应超时: " + e.getMessage());
                callback.onError(err);
                callback.onComplete(false, parser.accumulatedText(), err);
                return false;
            } catch (Exception e) {
                if (wasInterrupted()) {
                    updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                            SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED);
                    // interrupt 经 session/cancel notification:prompt 应以 cancelled stopReason 正常结束;
                    // 若仍抛异常说明 cancel 未生效/进程问题 → 清长驻,下 turn 重建
                    clearPersistent();
                    callback.onInterrupted(parser.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
                    return false;
                }
                updateNegotiatedCapabilities(SessionCapabilityState.DEGRADED,
                        SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED);
                clearPersistent();
                String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "kimi prompt failed: " + e.getMessage());
                callback.onError(err);
                callback.onComplete(false, parser.accumulatedText(), err);
                return false;
            }

            // ── finalizeTurn ──
            return finalizeTurn(parser, callback, diagnostic, conn);
        } finally {
            // 长驻:keepAlive=true(成功)不 close(进程保持,下 turn 复用);
            // keepAlive=false(出错/中断)→ 清长驻 + close 连接(进程死/重建)
            if (!keepAlive) {
                clearPersistent();
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (Exception ignored) {
                        // 忽略关闭异常
                    }
                }
            }
            if (activeHandle == currentHandle) {
                activeHandle = null;
            }
            if (activeConnection == conn) {
                activeConnection = null;
            }
        }
    }

    private boolean finalizeTurn(KimiAcpStreamParser parser, CliSessionCallback callback,
                                  StringBuilder diagnostic, KimiAcpConnection conn) {
        // interrupt 经 session/cancel notification:prompt 以 stopReason=cancelled 正常返回(不抛异常),
        // 此处判定 userInterrupted → 走 onInterrupted(而非 onComplete true),与杀进程中断语义对齐。
        if (wasInterrupted()) {
            // interrupt 经 session/cancel notification:prompt 以 stopReason=cancelled 正常返回(不抛异常),
            // 此处判定 userInterrupted → 走 onInterrupted(而非 onComplete true),与杀进程中断语义对齐。
            // session/cancel 只取消当前 turn,session 保持可用 → 不 clearPersistent,保持长驻(keepAlive=true),
            // 下 turn 直接复用进程+session(中断不杀进程,ACP 优雅中断的核心收益)。
            callback.onMessage(CliConstants.MSG_STREAM_END, "");
            callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
            callback.onInterrupted(parser.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
            return false;
        }
        // 标题接入。kimi 原生 title=lastPrompt 首行,注入段(Opened Files 等)会污染实时展示——
        // 与历史 reader 同源清理(CliPromptContexts),剥除注入标记 + 60 字符预览截断。
        String title = parser.capturedTitle();
        String cleanedTitle = CliPromptContexts.truncatePreview(
                CliPromptContexts.stripInjectedContext(title));
        if (cleanedTitle != null && !cleanedTitle.isBlank()) {
            JsonObject titlePayload = new JsonObject();
            if (parser.capturedSessionId() != null) {
                titlePayload.addProperty("sessionId", parser.capturedSessionId());
            }
            titlePayload.addProperty("title", cleanedTitle);
            callback.onMessage(CliConstants.MSG_SESSION_TITLE, gson.toJson(titlePayload));
        }

        if (parser.hasError()) {
            String errDiag = parser.errorDiagnostic();
            if (errDiag == null || errDiag.isEmpty()) {
                errDiag = diagnostic.toString();
            }
            String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL,
                    errDiag.isBlank() ? "ACP 流解析错误" : errDiag);
            callback.onError(err);
            callback.onComplete(false, parser.accumulatedText(), err);
            return false;
        }

        if (!parser.receivedAnyEvent()) {
            // 静默空失败:进程正常但无任何事件(典型:内部静默错误)
            String stderr = conn.stderrDiagnostic();
            String detail = stderr != null && !stderr.isBlank() ? stderr : "进程未返回任何事件";
            String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, detail);
            callback.onError(err);
            callback.onComplete(false, parser.accumulatedText(), err);
            return false;
        }

        // 补发流结束(parser.streamEnded 恒 false)
        callback.onMessage(CliConstants.MSG_STREAM_END, "");
        callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
        callback.onComplete(true, parser.accumulatedText(), null);
        return false;
    }

    // ── 握手步骤 ──────────────────────────────────────────────────────────────

    private void initialize(KimiAcpConnection conn) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty(KimiAcpProtocol.FIELD_PROTOCOL_VERSION, 1);
        JsonObject caps = new JsonObject();
        JsonObject fs = new JsonObject();
        fs.addProperty("readTextFile", false);
        fs.addProperty("writeTextFile", false);
        caps.add("fs", fs);
        caps.addProperty("terminal", false);
        params.add("clientCapabilities", caps);
        conn.request(KimiAcpProtocol.METHOD_INITIALIZE, params, HANDSHAKE_TIMEOUT_MS);
    }

    /**
     * 建立 session:effectiveSessionId 非空 → session/load(失败降级 new);否则 session/new。
     * 返回 resolvedSessionId,并调 parser.attachSessionId。
     */
    private String establishSession(KimiAcpConnection conn, CliSendRequest request,
                                     String effectiveSessionId, McpGatewayCliConfig gatewayConfig,
                                     KimiAcpStreamParser parser) throws Exception {
        JsonArray mcpServers = buildMcpServers(gatewayConfig);
        String resolved;
        JsonObject sessionResult;
        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            // 续接:session/load(失败抛出,runTurn 判定 B13)
            JsonObject params = new JsonObject();
            params.addProperty(KimiAcpProtocol.FIELD_SESSION_ID, effectiveSessionId);
            params.addProperty(KimiAcpProtocol.FIELD_CWD, request.cwd());
            params.add(KimiAcpProtocol.FIELD_MCP_SERVERS, mcpServers);
            sessionResult = conn.request(KimiAcpProtocol.METHOD_SESSION_LOAD, params, HANDSHAKE_TIMEOUT_MS);
            resolved = getString(sessionResult, KimiAcpProtocol.FIELD_SESSION_ID);
            if (resolved == null || resolved.isBlank()) {
                resolved = effectiveSessionId;
            }
        } else {
            // 首轮:session/new
            JsonObject params = new JsonObject();
            params.addProperty(KimiAcpProtocol.FIELD_CWD, request.cwd());
            params.add(KimiAcpProtocol.FIELD_MCP_SERVERS, mcpServers);
            sessionResult = conn.request(KimiAcpProtocol.METHOD_SESSION_NEW, params, HANDSHAKE_TIMEOUT_MS);
            resolved = getString(sessionResult, KimiAcpProtocol.FIELD_SESSION_ID);
            if (resolved == null || resolved.isBlank()) {
                throw new IllegalStateException("session/new 未返回 sessionId");
            }
        }
        // thinking 档位目录:合法值由当前模型决定(KimiAcpProtocol 取值说明),
        // 从 configOptions 解析供 set_config_option 前协商,避免发不支持的档位。
        this.thinkingOptions = parseThinkingOptions(sessionResult);
        updateNegotiatedCapabilities(SessionCapabilityState.NEGOTIATED, null);
        this.sessionId = resolved;
        parser.attachSessionId(resolved);
        return resolved;
    }

    private void setThinkingConfig(KimiAcpConnection conn, String sessionId, String value) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty(KimiAcpProtocol.FIELD_SESSION_ID, sessionId);
        params.addProperty(KimiAcpProtocol.FIELD_CONFIG_ID, KimiAcpProtocol.CONFIG_ID_THINKING);
        params.addProperty(KimiAcpProtocol.FIELD_VALUE, value);
        try {
            conn.request(KimiAcpProtocol.METHOD_SET_CONFIG_OPTION, params, SET_CONFIG_TIMEOUT_MS);
        } catch (KimiAcpConnection.AcpRpcException e) {
            // kimi 的合法档位由模型目录动态下发(supportEfforts,服务端刷新),请求档位
            // 不在当前模型词表时(实测 k3 拒绝 medium)退回 "on"——kimi 侧语义为
            // 采用模型 defaultThinkingEffort,对所有模型通用,不硬编码词表。
            LOG.info("[KimiAcpCliSession][" + tabId + "] thinking value '" + value
                    + "' rejected by model catalog; falling back to 'on'");
            params.addProperty(KimiAcpProtocol.FIELD_VALUE, "on");
            conn.request(KimiAcpProtocol.METHOD_SET_CONFIG_OPTION, params, SET_CONFIG_TIMEOUT_MS);
        }
    }

    // ── server 请求处理(权限兜底) ──────────────────────────────────────────────

    /**
     * 处理 server→client 请求。kimi auto mode 不发 permission;AskUserQuestion 等会发,
     * 带 id 必须回应否则 turn 挂死。这里对 permission 请求回 cancelled(拒绝),
     * 其它(fs/terminal,clientCapabilities 未声明故 kimi 0.38 不发)兜底也回 cancelled。
     */
    private JsonObject handleServerRequest(String method, JsonObject params) {
        if (method != null && method.toLowerCase(Locale.ROOT).contains("permission")) {
            LOG.info("[KimiAcpCliSession][" + tabId + "] permission request auto-cancelled: " + method);
            return buildPermissionFallbackResponse();
        }
        // 未识别的 server 请求 → 兜底 cancelled(避免挂死 turn)
        LOG.warn("[KimiAcpCliSession][" + tabId + "] unhandled server request, auto-cancelled: " + method);
        return buildPermissionFallbackResponse();
    }

    // ── prompt blocks 构建 ──────────────────────────────────────────────────────

    /**
     * 构造 ACP prompt content blocks:text 块 + 图片块(ACP 原生 image content blocks)。
     * 图片经 {@link CliAttachmentHandler#processForAcp} 取 base64 直传,无需磁盘物化。
     */
    private JsonArray buildPromptBlocks(CliSendRequest request) {
        JsonArray blocks = new JsonArray();
        // text 块(消息 + 打开文件 + @引用 + agent 角色)
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", composePromptText(request));
        blocks.add(textBlock);
        // 图片块(ACP content blocks,promptCapabilities.image=true)
        if (request.attachments() != null && !request.attachments().isEmpty()) {
            List<CliAttachmentHandler.AcpImagePart> images =
                    new CliAttachmentHandler().processForAcp(request.attachments());
            for (CliAttachmentHandler.AcpImagePart img : images) {
                JsonObject imageBlock = new JsonObject();
                imageBlock.addProperty("type", "image");
                imageBlock.addProperty("data", img.base64());
                imageBlock.addProperty("mimeType", img.mimeType());
                blocks.add(imageBlock);
            }
            if (!images.isEmpty()) {
                LOG.info("[KimiAcpCliSession][" + tabId + "] embedded " + images.size()
                        + " ACP image block(s) into prompt");
            }
        }
        return blocks;
    }

    /**
     * prompt 文本组合(消息 + 打开文件 + @文件引用 + agent 角色)。
     * 复制自 {@link com.github.claudecodegui.cli.common.AbstractRunOnceCliSession#buildPromptText},
     * legacy(stream-json)与 ACP 两通道必须逐字一致(对称性测试要求);修改其一须同步另一。
     */
    static String composePromptText(CliSendRequest request) {
        StringBuilder sb = new StringBuilder(request.message());
        if (request.openedFiles() != null && !request.openedFiles().isJsonNull() && request.openedFiles().size() > 0) {
            sb.append(CliConstants.PROMPT_OPENED_FILES).append(GsonHolder.GSON.toJson(request.openedFiles()));
        }
        if (!request.fileTagPaths().isEmpty()) {
            sb.append(CliConstants.PROMPT_REFERENCED);
            for (String p : request.fileTagPaths()) {
                sb.append("- ").append(p).append('\n');
            }
        }
        if (request.agentPrompt() != null && !request.agentPrompt().isBlank()) {
            sb.append(CliConstants.PROMPT_AGENT_ROLE).append(request.agentPrompt());
        }
        return sb.toString();
    }

    // ── MCP / config 辅助 ──────────────────────────────────────────────────────

    private McpGatewayCliConfig buildGatewayConfig(CliSendRequest request) {
        if (gatewayService == null) {
            return McpGatewayCliConfig.disabled("No MCP Gateway service");
        }
        return gatewayService.buildCliConfig(ProviderType.KIMI, tabId, request.cwd());
    }

    /**
     * 构造 session/new 的 mcpServers 参数。阶段 3 接入(走 gateway stdio client);
     * 本阶段返回空数组(占位)。
     */
    private JsonArray buildMcpServers(McpGatewayCliConfig cfg) {
        // TODO 阶段3 MCP 注入:cfg.command() → [{name:melon_gateway, command, args, env:{}}]
        return new JsonArray();
    }

    // ── 静态工具(单测直打) ─────────────────────────────────────────────────────

    /**
     * reasoningEffort → kimi thinking 档位:
     * low→low, medium→medium, high→high, xhigh/max→max, 其它/null/blank→medium。
     * (不用 currentValue,避免用户 config.toml=high 意外烧 token;未设档默认 medium)
     */
    static String mapThinkingEffort(String reasoningEffort) {
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return KimiAcpProtocol.THINKING_MEDIUM;
        }
        return switch (reasoningEffort.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> KimiAcpProtocol.THINKING_LOW;
            case "medium" -> KimiAcpProtocol.THINKING_MEDIUM;
            case "high" -> KimiAcpProtocol.THINKING_HIGH;
            case "xhigh", "max" -> KimiAcpProtocol.THINKING_MAX;
            default -> KimiAcpProtocol.THINKING_MEDIUM;
        };
    }

    /**
     * thinking 开启值:showThinking on → mapThinkingEffort(reasoningEffort);off → null(不调 set_config,真省 token)。
     * <p>返回的是<b>期望档位字面量</b>,发送前还须经 {@link #negotiateThinkingValue} 对当前模型协商。
     */
    static String resolveThinkingValue(CliSendRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.thinkingOutputEnabled())) {
            return null;
        }
        return mapThinkingEffort(request.reasoningEffort());
    }

    // ── thinking 档位协商(模型动态词表) ────────────────────────────────────────

    /** 当前 session 的思考档位目录(session/new、load 响应 configOptions 中 category=thought_level 项)。 */
    record ThinkingOptions(List<String> supportedValues, String currentValue) {
    }

    /**
     * 从 session/new / session/load 响应解析 thinking 档位目录。
     * configOptions 缺失(旧版 kimi / 字段可省)时返回 null,协商退回字面量直发。
     */
    static ThinkingOptions parseThinkingOptions(JsonObject sessionResult) {
        if (sessionResult == null || !sessionResult.has(KimiAcpProtocol.FIELD_CONFIG_OPTIONS)
                || !sessionResult.get(KimiAcpProtocol.FIELD_CONFIG_OPTIONS).isJsonArray()) {
            return null;
        }
        for (var element : sessionResult.getAsJsonArray(KimiAcpProtocol.FIELD_CONFIG_OPTIONS)) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject option = element.getAsJsonObject();
            boolean isThinking = KimiAcpProtocol.CONFIG_ID_THINKING.equals(getString(option, KimiAcpProtocol.FIELD_ID))
                    || KimiAcpProtocol.CONFIG_CATEGORY_THOUGHT_LEVEL.equals(getString(option, KimiAcpProtocol.FIELD_CATEGORY));
            if (!isThinking || !option.has(KimiAcpProtocol.FIELD_OPTIONS)
                    || !option.get(KimiAcpProtocol.FIELD_OPTIONS).isJsonArray()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (var v : option.getAsJsonArray(KimiAcpProtocol.FIELD_OPTIONS)) {
                if (v.isJsonObject()) {
                    String value = getString(v.getAsJsonObject(), KimiAcpProtocol.FIELD_VALUE);
                    if (value != null && !value.isBlank()) {
                        values.add(value);
                    }
                }
            }
            if (!values.isEmpty()) {
                return new ThinkingOptions(values, getString(option, KimiAcpProtocol.FIELD_CURRENT_VALUE));
            }
        }
        return null;
    }

    /**
     * 期望档位 → 当前模型支持的档位(opencode {@code mapReasoningVariant} 同范式:
     * 支持就直发,不支持就近映射,映射不了用万能别名,再不行省略不发):
     * <ol>
     *   <li>目录未知(旧版 kimi)→ 字面量直发(legacy 行为,set_config 失败非致命);</li>
     *   <li>字面量在目录中 → 直发;</li>
     *   <li>就近 effort 档位(rank: low&lt;medium&lt;high&lt;max,非 effort 词按 medium 计,跳过 "off";并列取低档);</li>
     *   <li>目录只有 "on"/无 effort 词 → "on"(kimi 侧解析为模型 defaultThinkingEffort);</li>
     *   <li>目录为空/只有 "off"(alwaysThinking 等)→ null(不发)。</li>
     * </ol>
     */
    static String negotiateThinkingValue(String desired, ThinkingOptions options) {
        if (desired == null) {
            return null;
        }
        if (options == null || options.supportedValues() == null || options.supportedValues().isEmpty()) {
            return desired;
        }
        List<String> values = options.supportedValues();
        if (values.contains(desired)) {
            return desired;
        }
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : values) {
            if (KimiAcpProtocol.THINKING_OFF.equals(candidate)) {
                continue;
            }
            int dist = Math.abs(effortRank(candidate) - effortRank(desired));
            if (dist < bestDist || (dist == bestDist && best != null && effortRank(candidate) < effortRank(best))) {
                best = candidate;
                bestDist = dist;
            }
        }
        return best != null ? best
                : (values.contains(KimiAcpProtocol.THINKING_ON) ? KimiAcpProtocol.THINKING_ON : null);
    }

    /** effort 档位序:low=0,medium=1(非 effort 词同),high=2,xhigh/max=3,"off" 不参与(调用方跳过)。 */
    private static int effortRank(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case KimiAcpProtocol.THINKING_LOW -> 0;
            case KimiAcpProtocol.THINKING_HIGH -> 2;
            case KimiAcpProtocol.THINKING_MAX, "xhigh" -> 3;
            default -> 1;
        };
    }

    /** 权限兜底响应:cancelled(拒绝;无 UI 渲染权限弹窗时一律拒绝的安全立场)。 */
    static JsonObject buildPermissionFallbackResponse() {
        JsonObject outcome = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("outcome", "cancelled");
        outcome.add("outcome", inner);
        return outcome;
    }

    /**
     * B13:判断错误是否指向 session 失效(续接 load 命中已不存在的 session)。
     * 复制自 {@link com.github.claudecodegui.cli.common.AbstractRunOnceCliSession} 的同名 private 方法。
     */
    private static boolean looksLikeSessionInvalidation(CharSequence diagnostic) {
        if (diagnostic == null || diagnostic.length() == 0) {
            return false;
        }
        String text = diagnostic.toString().toLowerCase(Locale.ROOT);
        if (!text.contains("session")) {
            return false;
        }
        return text.contains("not found")
                || text.contains("does not exist")
                || text.contains("not exist")
                || text.contains("invalid")
                || text.contains("expired")
                || text.contains("no such")
                || text.contains("unknown");
    }

    private static String formatAcpError(KimiAcpConnection.AcpRpcException e, KimiAcpConnection conn) {
        String stderr = conn.stderrDiagnostic();
        String detail = stderr != null && !stderr.isBlank() ? stderr : e.getMessage();
        return CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "ACP error (" + e.code + "): " + detail);
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── 中断/释放 ─────────────────────────────────────────────────────────────

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        // 长驻优先:session/cancel notification 优雅取消 turn(进程保持,下 turn 复用)。
        KimiAcpConnection conn = persistentConn;
        String sid = persistentSessionId;
        if (conn != null && conn.isAlive() && sid != null) {
            try {
                conn.sendSessionCancel(sid);
                scheduleCancelFallback(activeTurnId, conn, activeHandle);
                return;
            } catch (Exception e) {
                LOG.warn("[KimiAcpCliSession][" + tabId + "] session/cancel failed, falling back to process interrupt", e);
            }
        }
        // 退化:无长驻连接或 cancel 失败 → 杀进程(下 turn 重建)
        CliProcessHandle h = activeHandle;
        if (h != null) {
            KimiAcpConnection active = activeConnection;
            if (active != null) {
                active.markTerminated();
            }
            h.interrupt();
        }
    }

    /**
     * ACP cancel 是无响应 notification。若 provider 未在短预算内结束当前 turn，
     * 强杀对应进程树，避免等待 15 分钟 prompt timeout 导致 streaming/loading 永久悬挂。
     * turnId + handle 双重校验防止上一轮迟到 fallback 误杀已复用同一长驻进程的新轮次。
     */
    private void scheduleCancelFallback(long turnId, KimiAcpConnection conn, CliProcessHandle handle) {
        CompletableFuture.delayedExecutor(CliConstants.CLI_INTERRUPT_FALLBACK_MS, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    if (turnId == 0L || activeTurnId != turnId || !userInterrupted.get()
                            || persistentConn != conn || activeHandle != handle) {
                        return;
                    }
                    LOG.warn("[KimiAcpCliSession][" + tabId + "] session/cancel fallback after "
                            + CliConstants.CLI_INTERRUPT_FALLBACK_MS + "ms; terminating process tree");
                    if (handle != null) {
                        conn.markTerminated();
                        handle.interrupt();
                    }
                });
    }

    @Override
    public void dispose() {
        interrupt();
        clearPersistent();
    }

    static SessionNegotiatedCapabilities degradedCapabilities(
            SessionCapabilityDegradationReason reason) {
        return new SessionNegotiatedCapabilities(
                SessionCapabilityState.DEGRADED,
                SessionCapabilityChannel.KIMI_ACP,
                false,
                false,
                false,
                true,
                reason == null ? SessionCapabilityDegradationReason.ACP_RUNTIME_FAILED : reason);
    }

    static boolean hasThinkingCapability(ThinkingOptions options) {
        return options != null && options.supportedValues() != null
                && options.supportedValues().stream()
                .anyMatch(value -> value != null && !KimiAcpProtocol.THINKING_OFF.equalsIgnoreCase(value));
    }

    static SessionNegotiatedCapabilities negotiatedCapabilities(ThinkingOptions options) {
        return new SessionNegotiatedCapabilities(
                SessionCapabilityState.NEGOTIATED,
                SessionCapabilityChannel.KIMI_ACP,
                hasThinkingCapability(options),
                true,
                false,
                false,
                null);
    }

    private void updateNegotiatedCapabilities(SessionCapabilityState state,
                                              SessionCapabilityDegradationReason reason) {
        if (state == SessionCapabilityState.DEGRADED) {
            negotiatedCapabilities = degradedCapabilities(reason);
            return;
        }
        negotiatedCapabilities = negotiatedCapabilities(thinkingOptions);
    }

    /** 清除长驻状态(进程死/握手失败/turn 失效时,下 turn 重建)。 */
    private void clearPersistent() {
        KimiAcpConnection old = persistentConn;
        persistentConn = null;
        persistentSessionId = null;
        persistentHandle = null;
        persistentProcessGeneration = null;
        thinkingOptions = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
                // 关闭路径必须尽力终止,不覆盖原始 turn 错误。
            }
        }
    }

    private void recordLifecycle(LifecycleEventType type, Process process,
                                 CliSendRequest request, Long generation, String detail) {
        if (lifecycleService == null) {
            return;
        }
        lifecycleService.record(type,
                lifecycleService.metadata(LifecycleProcessKind.CLI_PERSISTENT,
                        request != null ? request.runtimeSessionEpoch() : null,
                        request != null ? request.responseTurnEpoch() : null,
                        generation),
                process != null ? process.pid() : -1L,
                detail);
    }
    private boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }
}
