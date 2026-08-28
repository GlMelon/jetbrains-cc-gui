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
import com.github.claudecodegui.cli.common.CliProcessLifecycle;
import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Gson gson = GsonHolder.GSON;
    private final ProviderCliResolver resolver = new ProviderCliResolver(ProviderType.KIMI, "kimi");

    private volatile CliProcessHandle activeHandle;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private volatile String sessionId;

    // ── 长驻(一进程多 turn) ──────────────────────────────────────────────────
    // ACP 协议设计即为「一次 initialize,多 session/prompt」;run-once 每 turn spawn(~1-2s
    // node 冷启)是反 ACP 用法。长驻复用进程 + session,省 spawn+握手;interrupt 经
    // session/cancel notification 优雅取消 turn(实测 0.38:notification 后 stopReason=cancelled,
    // 不杀进程)。进程死/握手失败 → 清 persistent,下 turn 重建(session/new,上下文重建)。
    private volatile KimiAcpConnection persistentConn;
    private volatile String persistentSessionId;
    private volatile CliProcessHandle persistentHandle;

    public KimiAcpCliSession(String tabId, McpGatewayService gatewayService) {
        this.tabId = tabId;
        this.gatewayService = gatewayService;
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        return CliSessionExecutor.runAsync(() -> {
            StringBuilder diagnostic = new StringBuilder();
            try {
                // B13:续接失败时清空 sessionId 与长驻连接,重试一次首轮流程
                boolean retry = runTurn(request, callback, sessionId, diagnostic);
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
                activeHandle = null;
                userInterrupted.set(false);
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

        try {
            // ── 复用长驻连接 or 新建 ──
            KimiAcpConnection existing = persistentConn;
            if (existing != null && existing.isAlive() && persistentSessionId != null) {
                // 复用:省 spawn(~1-2s node 冷启)+ 握手 initialize/session/new
                conn = existing;
                resolvedSessionId = persistentSessionId;
                currentHandle = persistentHandle;
                activeHandle = currentHandle;
                LOG.debug("[KimiAcpCliSession][" + tabId + "] reusing persistent ACP connection, session=" + resolvedSessionId);
            } else {
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
                activeHandle = currentHandle;
                if (userInterrupted.get()) {
                    currentHandle.interrupt();
                }
                conn = new KimiAcpConnection(process,
                        parser::parseLine,
                        (method, params) -> handleServerRequest(method, params),
                        line -> CliErrorFormatter.appendDiagnosticLine(diagnostic, line));
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
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "ACP handshake failed: " + e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                    return false;
                }
                // 握手成功 → 存长驻(下 turn 复用,省 spawn+握手)
                persistentConn = conn;
                persistentSessionId = resolvedSessionId;
                persistentHandle = currentHandle;
                this.sessionId = resolvedSessionId;
            }

            // 开启思考(若用户启用)
            try {
                String thinkingValue = resolveThinkingValue(request);
                if (thinkingValue != null) {
                    setThinkingConfig(conn, resolvedSessionId, thinkingValue);
                }
            } catch (Exception e) {
                LOG.warn("[KimiAcpCliSession][" + tabId + "] set thinking config failed (non-fatal)", e);
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
                    String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL,
                            "kimi 未登录,请在终端运行 kimi 登录后重试 (-32000)");
                    callback.onError(err);
                    callback.onComplete(false, parser.accumulatedText(), err);
                    return false;
                }
                // prompt RPC 失败:session 失效或进程死 → 清长驻,下 turn 重建
                clearPersistent();
                String err = formatAcpError(e, conn);
                callback.onError(err);
                callback.onComplete(false, parser.accumulatedText(), err);
                return false;
            } catch (KimiAcpConnection.AcpTimeoutException e) {
                // 超时可能进程卡死 → 清长驻
                clearPersistent();
                String err = CliErrorFormatter.formatError(KIMI_PROVIDER_LABEL, "kimi 响应超时: " + e.getMessage());
                callback.onError(err);
                callback.onComplete(false, parser.accumulatedText(), err);
                return false;
            } catch (Exception e) {
                if (wasInterrupted()) {
                    // interrupt 经 session/cancel notification:prompt 应以 cancelled stopReason 正常结束;
                    // 若仍抛异常说明 cancel 未生效/进程问题 → 清长驻,下 turn 重建
                    clearPersistent();
                    callback.onInterrupted(parser.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
                    return false;
                }
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
        // 标题接入
        String title = parser.capturedTitle();
        if (title != null && !title.isBlank()) {
            JsonObject titlePayload = new JsonObject();
            if (parser.capturedSessionId() != null) {
                titlePayload.addProperty("sessionId", parser.capturedSessionId());
            }
            titlePayload.addProperty("title", title);
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
        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            // 续接:session/load(失败抛出,runTurn 判定 B13)
            JsonObject params = new JsonObject();
            params.addProperty(KimiAcpProtocol.FIELD_SESSION_ID, effectiveSessionId);
            params.addProperty(KimiAcpProtocol.FIELD_CWD, request.cwd());
            params.add(KimiAcpProtocol.FIELD_MCP_SERVERS, mcpServers);
            JsonObject result = conn.request(KimiAcpProtocol.METHOD_SESSION_LOAD, params, HANDSHAKE_TIMEOUT_MS);
            resolved = getString(result, KimiAcpProtocol.FIELD_SESSION_ID);
            if (resolved == null || resolved.isBlank()) {
                resolved = effectiveSessionId;
            }
        } else {
            // 首轮:session/new
            JsonObject params = new JsonObject();
            params.addProperty(KimiAcpProtocol.FIELD_CWD, request.cwd());
            params.add(KimiAcpProtocol.FIELD_MCP_SERVERS, mcpServers);
            JsonObject result = conn.request(KimiAcpProtocol.METHOD_SESSION_NEW, params, HANDSHAKE_TIMEOUT_MS);
            resolved = getString(result, KimiAcpProtocol.FIELD_SESSION_ID);
            if (resolved == null || resolved.isBlank()) {
                throw new IllegalStateException("session/new 未返回 sessionId");
            }
        }
        this.sessionId = resolved;
        parser.attachSessionId(resolved);
        return resolved;
    }

    private void setThinkingConfig(KimiAcpConnection conn, String sessionId, String value) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty(KimiAcpProtocol.FIELD_SESSION_ID, sessionId);
        params.addProperty(KimiAcpProtocol.FIELD_CONFIG_ID, KimiAcpProtocol.CONFIG_ID_THINKING);
        params.addProperty(KimiAcpProtocol.FIELD_VALUE, value);
        conn.request(KimiAcpProtocol.METHOD_SET_CONFIG_OPTION, params, SET_CONFIG_TIMEOUT_MS);
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
     */
    static String resolveThinkingValue(CliSendRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.thinkingOutputEnabled())) {
            return null;
        }
        return mapThinkingEffort(request.reasoningEffort());
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
                return;
            } catch (Exception e) {
                LOG.warn("[KimiAcpCliSession][" + tabId + "] session/cancel failed, falling back to process interrupt", e);
            }
        }
        // 退化:无长驻连接或 cancel 失败 → 杀进程(下 turn 重建)
        CliProcessHandle h = activeHandle;
        if (h != null) {
            h.interrupt();
        }
    }

    @Override
    public void dispose() {
        interrupt();
        // 关闭长驻连接(进程退出)
        KimiAcpConnection conn = persistentConn;
        if (conn != null) {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            persistentConn = null;
            persistentSessionId = null;
            persistentHandle = null;
        }
    }

    /** 清除长驻状态(进程死/握手失败/turn 失效时,下 turn 重建)。 */
    private void clearPersistent() {
        persistentConn = null;
        persistentSessionId = null;
        persistentHandle = null;
    }
    private boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }
}
