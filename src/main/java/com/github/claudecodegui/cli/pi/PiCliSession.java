package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.util.CliTempDir;
import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.*;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pi CLI 会话:每个 Tab 独立实例,使用 {@code pi run "<msg>" --format json}
 * (one-shot per turn,marker 事件流)。通过从事件流提取 {@code sessionID} 并以 {@code -s} 续接实现多轮对话。
 * 完全不依赖 SDK / ai-bridge(设计 §7)。
 * <p>
 * 实现要点(对应设计 §7.1-7.5 / B1,B9,B13,B14,B15):
 * <ul>
 *   <li>B1:不预创建 session。{@code pi run} 隐式创建会话,sessionID 从事件流 [SESSION_ID] 提取。</li>
 *   <li>B9:消息作为位置参数传入,不再双写 stdin(消除 createSession/prompt 双写)。</li>
 *   <li>B13:续接(-s)失败时(session 失效),清空 sessionId 重试一次首轮流程。</li>
 *   <li>B14:进程经 {@link CliProcessHandle} 管理,interrupt 走 PlatformUtils.terminateProcess(替代裸 destroyForcibly)。</li>
 *   <li>B15:能力透传(model/-m、reasoningEffort/--variant、图片附件/-f、permissionMode bypass→
 *       --auto、cwd/--dir)。</li>
 * </ul>
 * 事件解析委托 {@link PiCliStreamParser}。
 */
public class PiCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(PiCliSession.class);
    private static final Charset WINDOWS_CHINESE_CHARSET = Charset.forName("GBK");

    private final String tabId;
    private final Gson gson = GsonHolder.GSON;
    private final CliAttachmentHandler attachmentHandler = new CliAttachmentHandler();
    private final McpGatewayService gatewayService;

    // 当前 session ID(从事件流 [SESSION_ID] 标记提取,续接时以 -s 传入)
    private volatile String sessionId;
    // 当前活跃进程(用于中断)
    private volatile CliProcessHandle activeHandle;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);

    public PiCliSession(String tabId) {
        this(tabId, null);
    }

    public PiCliSession(String tabId, McpGatewayService gatewayService) {
        this.tabId = tabId;
        this.gatewayService = gatewayService;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        return CliSessionExecutor.runAsync(() -> {
            long sendStartNanos = System.nanoTime();
            List<File> tempFiles = new ArrayList<>();
            StringBuilder diagnostic = new StringBuilder();
            try {
                LOG.info("[CliConcurrencyDiag][PiCliSession] send task started"
                        + ": tabId=" + tabId
                        + ", sessionId=" + (sessionId != null ? sessionId : "(none)")
                        + ", cwd=" + (request.cwd() != null ? request.cwd() : "(none)")
                        + ", thread=" + Thread.currentThread().getName());
                // 图片附件物化为磁盘文件
                List<File> attachFiles;
                try {
                    long attachmentsStartNanos = System.nanoTime();
                    attachFiles = attachmentHandler.processForCodex(request.attachments(), tempFiles);
                    LOG.info("[CliConcurrencyDiag][PiCliSession] attachments prepared"
                            + ": tabId=" + tabId
                            + ", files=" + attachFiles.size()
                            + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                            + ", attachmentsMs=" + elapsedMillis(attachmentsStartNanos)
                            + ", thread=" + Thread.currentThread().getName());
                } catch (Exception e) {
                    LOG.warn("[PiCliSession][" + tabId + "] process attachments failed", e);
                    attachFiles = List.of();
                }

                // B13:续接失败时清空 sessionId 重试一次首轮流程(设计 §7.4)。
                boolean retry = runOnce(request, callback, sessionId, attachFiles, diagnostic, sendStartNanos);
                if (retry) {
                    LOG.info("[CliConcurrencyDiag][PiCliSession] continuation session invalidated; retrying as fresh turn"
                            + ": tabId=" + tabId
                            + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                            + ", thread=" + Thread.currentThread().getName());
                    sessionId = null;
                    diagnostic.setLength(0);
                    if (!userInterrupted.get()) {
                        runOnce(request, callback, null, attachFiles, diagnostic, sendStartNanos);
                    }
                }
            } catch (Exception e) {
                LOG.warn("[PiCliSession][" + tabId + "] send failed", e);
                if (wasInterrupted()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    String err = CliErrorFormatter.formatError("Pi", e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                }
            } finally {
                activeHandle = null;
                userInterrupted.set(false);
                CliTempDir.deleteFilesQuietly(tempFiles);
            }
        });
    }

    /**
     * 执行一次 {@code pi run}。返回 true 表示发生了续接 session 失效、应作为首轮重试
     * (此时**未**调用 onComplete/onError,交由调用方重试后报告);false 表示已报告最终结果或被中断。
     *
     * @param effectiveSessionId 续接 session id;null 表示首轮(不加 -s)
     * @param attachFiles        已物化的图片附件文件(-f 透传)
     */
    private boolean runOnce(
            CliSendRequest request,
            CliSessionCallback callback,
            String effectiveSessionId,
            List<File> attachFiles,
            StringBuilder diagnostic,
            long sendStartNanos
    ) throws Exception {
        PiCliStreamParser parser = new PiCliStreamParser(callback);
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.MCP_SYNCING.value());
        McpGatewayCliConfig gatewayConfig = buildGatewayConfig(request);
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());
        String effectiveSessionDisplay = effectiveSessionId != null ? effectiveSessionId : "(new)";
        LOG.info("[CliConcurrencyDiag][PiCliSession] building command"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        List<String> cmd = buildRunCommand(request, effectiveSessionId, attachFiles);
        LOG.info("[CliConcurrencyDiag][PiCliSession] command prepared"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        LOG.info("[PiCliSession][" + tabId + "] Command: " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
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
            } else {
                LOG.warn("[PiCliSession][" + tabId + "] CWD does not exist, falling back to home: " + request.cwd());
                File homeDir = new File(PlatformUtils.getHomeDirectory());
                if (homeDir.isDirectory()) {
                    pb.directory(homeDir);
                }
            }
        }

        // 可靠 stdin EOF:redirectInput 重定向到空设备(NUL / /dev/null)
        pb.redirectInput(stdinNullSink());

        LOG.info("[CliConcurrencyDiag][PiCliSession] starting process"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        Process process = pb.start();
        LOG.info("[CliConcurrencyDiag][PiCliSession] process started"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        CliProcessHandle currentHandle = new CliProcessHandle(process, "pi-tab-" + tabId);
        activeHandle = currentHandle;

        try {
            if (userInterrupted.get()) {
                currentHandle.interrupt();
            }
            CompletableFuture<Void> outputDrain = CliProcessLifecycle.drainAsync(process, () -> {
                try (InputStream rawIn = process.getInputStream()) {
                    CliOutputLimits.LineBuffer lineBuf = new CliOutputLimits.LineBuffer();
                    byte[] readBuf = new byte[8192];
                    int n;
                    boolean firstOutputLogged = false;
                    while ((n = rawIn.read(readBuf)) != -1) {
                        for (int i = 0; i < n; i++) {
                            byte b = readBuf[i];
                            if (b == '\n') {
                                if (!firstOutputLogged) {
                                    firstOutputLogged = true;
                                    LOG.info("[CliConcurrencyDiag][PiCliSession] first stdout line buffer reached"
                                            + ": tabId=" + tabId
                                            + ", effectiveSessionId=" + effectiveSessionDisplay
                                            + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                                            + ", thread=" + Thread.currentThread().getName());
                                }
                                processLine(lineBuf, parser, diagnostic);
                            } else {
                                lineBuf.write(b);
                            }
                        }
                    }
                    if (lineBuf.size() > 0) {
                        processLine(lineBuf, parser, diagnostic);
                    }
                }
            });

            CliProcessLifecycle.Outcome outcome = CliProcessLifecycle.await(process, outputDrain);
            int exitCode = outcome.exitCode();
        LOG.info("[CliConcurrencyDiag][PiCliSession] process exited"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", exitCode=" + exitCode
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());

        if (outcome.timedOut() && !wasInterrupted()) {
            String err = CliErrorFormatter.formatError("Pi", "CLI request timed out");
            callback.onError(err);
            callback.onComplete(false, parser.accumulatedText(), err);
            return false;
        }

        if (wasInterrupted()) {
            callback.onInterrupted(parser.accumulatedText(), CliConstants.I18N_REQUEST_INTERRUPTED);
            return false;
        }

        // 从事件流捕获 session id(首轮提取后续接复用)
        if (parser.capturedSessionId() != null) {
            sessionId = parser.capturedSessionId();
        }

        if (exitCode == 0 && !parser.hasError()) {
            if (!parser.streamEnded()) {
                if (isSilentEmptyFailure(parser)) {
                    String err = CliErrorFormatter.formatError("Pi",
                            "进程退出但未返回任何内容(exit=0,无事件流)。"
                                    + "常见原因:子进程阻塞读 stdin,或 pi/provider 内部错误。"
                                    + "请在命令行执行 pi run 并重定向空 stdin 对照验证,检查 pi 配置与 provider。");
                    callback.onError(err);
                    callback.onComplete(false, parser.accumulatedText(), err);
                    return false;
                }
                // 异常路径未收到 [STREAM_END] 但有事件/内容,补发流结束以解除前端阻塞
                callback.onMessage(CliConstants.MSG_STREAM_END, "");
                callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
            }
            callback.onComplete(true, parser.accumulatedText(), null);
            return false;
        }

        // 错误路径:优先取解析器收集的 error 诊断,回退到非 JSON 噪声行
        String errDiag = parser.errorDiagnostic();
        if (errDiag == null || errDiag.isEmpty()) {
            errDiag = diagnostic.toString();
        }

        // B13:仅续接(-s)场景检测 session 失效 → 重试首轮
        if (effectiveSessionId != null && looksLikeSessionInvalidation(errDiag)) {
            return true;
        }

        String err = errDiag.isBlank()
                ? CliErrorFormatter.formatExitError("Pi", exitCode, diagnostic)
                : CliErrorFormatter.formatError("Pi", errDiag);
        callback.onError(err);
        callback.onComplete(false, parser.accumulatedText(), err);
        return false;
        } finally {
            CliProcessLifecycle.terminate(process);
            if (activeHandle == currentHandle) {
                activeHandle = null;
            }
        }
    }

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        CliProcessHandle h = activeHandle;
        if (h != null) {
            long startNanos = System.nanoTime();
            h.interrupt();
            LOG.info("[TabPerf] PiCliSession.interrupt returned in "
                    + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
        }
    }

    @Override
    public void dispose() {
        interrupt();
    }

    private boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }

    private McpGatewayCliConfig buildGatewayConfig(CliSendRequest request) {
        if (gatewayService == null) {
            return McpGatewayCliConfig.disabled("No MCP Gateway service");
        }
        return gatewayService.buildCliConfig(ProviderType.PI, tabId, request.cwd());
    }

    // ── command builder ──────────────────────────────────────────────────────

    /**
     * 构造 {@code pi run "<msg>" --format json [-s sessionId] [能力 flags]}。
     * 消息作为 run 之后的首个位置参数(B9:消除旧实现的 stdin 双写)。
     */
    List<String> buildRunCommand(CliSendRequest request, String effectiveSessionId, List<File> attachFiles) {
        String executable = PiCliResolver.findExecutable();
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add(CliConstants.OPENCODE_ARG_RUN);
        cmd.add(buildPromptText(request));          // 位置参数:消息
        cmd.add(CliConstants.OPENCODE_ARG_FORMAT);
        cmd.add(CliConstants.OPENCODE_FORMAT_JSON);

        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            cmd.add(CliConstants.OPENCODE_ARG_SESSION);
            cmd.add(effectiveSessionId);
        }

        // B15:能力透传
        String model = firstNonBlank(request.actualModel(), request.model());
        if (model != null && !model.isBlank()) {
            cmd.add(CliConstants.OPENCODE_ARG_MODEL);
            cmd.add(model);
        }
        String variant = piVariant(request.reasoningEffort());
        if (variant != null) {
            cmd.add(CliConstants.OPENCODE_ARG_VARIANT);
            cmd.add(variant);
        }
        if (attachFiles != null) {
            for (File f : attachFiles) {
                if (f != null) {
                    cmd.add(CliConstants.OPENCODE_ARG_FILE);
                    cmd.add(f.getAbsolutePath());
                }
            }
        }
        if (CommonConstants.PERMISSION_MODE_BYPASS.equals(request.permissionMode())) {
            cmd.add(CliConstants.OPENCODE_ARG_AUTO);
        }
        if (request.cwd() != null && !request.cwd().isBlank()) {
            cmd.add(CliConstants.OPENCODE_ARG_DIR);
            cmd.add(request.cwd());
        }
        return cmd;
    }

    /**
     * reasoningEffort → pi --variant(设计 §7.2):
     * low→minimal, medium→省略(默认), high→high, xhigh/max→max。
     */
    private static String piVariant(String reasoningEffort) {
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return null;
        }
        return switch (reasoningEffort.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> "minimal";
            case "medium" -> null;
            case "high" -> "high";
            case "xhigh", "max" -> "max";
            default -> null;
        };
    }

    private String buildPromptText(CliSendRequest request) {
        StringBuilder sb = new StringBuilder(request.message());
        if (request.openedFiles() != null && !request.openedFiles().isJsonNull() && request.openedFiles().size() > 0) {
            sb.append(CliConstants.PROMPT_OPENED_FILES).append(gson.toJson(request.openedFiles()));
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

    /**
     * B13:判断错误诊断是否指向 session 失效(续接 -s 命中已不存在的 session)。
     * 关键词基于常见 session 失效表述,保守匹配避免误重试。
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

    // ── output line handling ──────────────────────────────────────────────────

    private void processLine(CliOutputLimits.LineBuffer lineBuf, PiCliStreamParser parser, StringBuilder diagnostic) {
        if (lineBuf.isTruncated()) {
            lineBuf.reset();
            throw new IllegalStateException("CLI stdout line exceeded " + CliOutputLimits.MAX_LINE_BYTES + " bytes");
        }
        byte[] bytes = lineBuf.toByteArray();
        lineBuf.reset();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        if (len == 0) {
            return;
        }
        String line = decodeLine(bytes, len);
        if (line == null || line.isBlank()) {
            return;
        }
        // 非 marker 行(启动 banner / 错误噪声)收集到 diagnostic,供错误上报;marker 行交解析器
        parser.parseLine(line);
        if (!line.trim().startsWith("[")) {
            CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
        }
    }

    /**
     * 先尝试 UTF-8 解码,无效字节序列回退到 Windows 中文编码
     * (Node.js 管道 UTF-8 vs cmd.exe GBK 错误信息混合)。
     */
    private static String decodeLine(byte[] bytes, int len) {
        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = utf8Decoder.decode(ByteBuffer.wrap(bytes, 0, len));
            return cb.toString();
        } catch (CharacterCodingException e) {
            Charset fallback = Charset.defaultCharset();
            if (WINDOWS_CHINESE_CHARSET.equals(fallback)) {
                return new String(bytes, 0, len, fallback);
            }
            String decoded = new String(bytes, 0, len, WINDOWS_CHINESE_CHARSET);
            if (!decoded.contains("�")) {
                return decoded;
            }
            return new String(bytes, 0, len, fallback);
        }
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

    /**
     * 子进程 stdin 空设备:Windows {@code NUL} / Unix {@code /dev/null},
     * 供 {@link ProcessBuilder#redirectInput} 在 OS 句柄层给 pi 空 stdin(立即 EOF)。
     */
    private static File stdinNullSink() {
        return new File(PlatformUtils.isWindows() ? "NUL" : "/dev/null");
    }

    /**
     * 判定是否"静默空失败":pi 进程 exit0,但整轮未解析到任何有效事件
     * ({@code !receivedAnyEvent},即无 sessionID / 文本 / [STREAM_END])。
     * 此组合表明 pi 未产出事件流(典型:阻塞读 stdin / 内部静默错误),
     * 应上报错误而非静默空完成。
     */
    static boolean isSilentEmptyFailure(PiCliStreamParser parser) {
        return !parser.receivedAnyEvent();
    }
}
