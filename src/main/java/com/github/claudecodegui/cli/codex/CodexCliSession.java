package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.*;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Codex CLI 会话：每个 Tab 独立实例，使用 codex exec --json（one-shot per turn）。
 * 通过 resume --last 实现多轮连续对话。
 * 完全不依赖 SDK / ai-bridge。
 */
public class CodexCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(CodexCliSession.class);
    private static final String ENV_CODEX_SANDBOX_NETWORK_DISABLED = CliConstants.ENV_CODEX_SANDBOX_NETWORK_DISABLED;
    private static final Charset WINDOWS_CHINESE_CHARSET = Charset.forName("GBK");
    private static final Pattern POWERSHELL_PROFILE_ERROR_PATTERN = Pattern.compile(
            "(?i)(PowerShell_profile\\.ps1|running scripts is disabled on this system|PSSecurityException|UnauthorizedAccess)"
    );
    private static final Pattern DIAGNOSTIC_HEADER_PATTERN = Pattern.compile(
            "(?i)^\\s*[^\\s:][^:]{0,120}:\\s*$"
    );
    private static final Pattern DIAGNOSTIC_BODY_PATTERN = Pattern.compile(
            "(?i)^(?:Line \\||\\d+\\s*\\|\\s*.+|\\|\\s*[~^]+.*"
                    + "|\\|\\s*(?:Cannot find path|The term|Could not find|Cannot bind argument"
                    + "|A positional parameter cannot be found|The system cannot find the file specified"
                    + "|Access is denied|The directory name is invalid"
                    + "|The filename, directory name, or volume label syntax is incorrect"
                    + "|Unexpected token|Missing .+|ParserError|Exception|Error).*)$"
    );
    private static final Pattern DIAGNOSTIC_GENERIC_PATTERN = Pattern.compile(
            "(?i)^\\s*.*\\b(?:not recognized as the name of a cmdlet|cannot find path"
                    + "|could not find a part of the path|file .* cannot be loaded"
                    + "|cannot bind argument|a positional parameter cannot be found"
                    + "|the system cannot find the file specified|access is denied"
                    + "|the directory name is invalid"
                    + "|the filename, directory name, or volume label syntax is incorrect"
                    + "|unexpected token|missing .+|parsererror|exception).*$"
    );
    // 非 JSON 的 CLI 错误/诊断行（HTTP 错误、网络错误、重试等）
    private static final Pattern CLI_ERROR_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(?:error|failed|failure|exception|timeout|timed.out|retry|exceeded"
                    + "|refused|denied|unauthorized|forbidden|not.found|too.many.requests"
                    + "|rate.limit|quota|abort|fatal|panic|unexpected.status"
                    + "|[45]\\d{2}\\b)\\b"
    );
    private static final String UNSUPPORTED_IMAGE_MESSAGE = CliConstants.I18N_UNSUPPORTED_IMAGE;
    private static final Pattern UNSUPPORTED_IMAGE_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:image|images|vision|visual|multimodal|local_image|image_url)\\b"
    );
    private static final Pattern UNSUPPORTED_IMAGE_REASON_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:not|n't)\\s+support(?:ed|s)?\\b|\\bunsupported\\b"
                    + "|\\bvision[- ]?capable\\b|\\bmultimodal\\b.*\\brequired\\b"
                    + "|\\brequires?\\b.*\\bvision\\b|\\bonly\\s+supported\\b)"
    );
    private static final Pattern RATE_LIMIT_OR_QUOTA_PATTERN = Pattern.compile(
            "(?i)(?:\\b429\\b|too\\s+many\\s+requests|rate\\s*limit|quota|request\\s+rejected"
                    + "|使用上限|限额)"
    );

    private final String tabId;
    private final Gson gson = GsonHolder.GSON;
    private final CliAttachmentHandler attachmentHandler = new CliAttachmentHandler();
    private final CodexCliEventNormalizer eventNormalizer = new CodexCliEventNormalizer();
    private final McpGatewayService gatewayService;

    // 当前 thread_id（从 thread.started 事件获取）
    private volatile String threadId;
    // 当前活跃进程（用于中断）
    private volatile CliProcessHandle activeHandle;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    // 致命 provider/API 错误(如 502 重连死循环)标志:parseEvent 检测到时置位,
    // stdout 读取循环据此提前 break 并 destroyForcibly,避免子进程永不退出导致前端无限转圈。
    private volatile boolean fatalAbort;
    // MCP 连接失败(本地 server 未启动)已发非阻塞提示的标志:rmcp worker 可能重试多次刷屏,
    // 每回合只发一次 toast;同时所有匹配 MCP 失败的行/事件都不缓冲为回合错误(见 handleMcpFailure)。
    private volatile boolean mcpNoticeEmitted;
    // 为缺失 id/call_id 的事件项生成唯一 fallback id,避免同轮多个工具块塌缩成同一 id 被去重吞掉。
    private final AtomicLong fallbackIdSeq = new AtomicLong();
    private final Map<String, String> assistantTextByItemId = new HashMap<>();
    private final Map<String, String> reasoningTextByItemId = new HashMap<>();
    private final Set<String> emittedToolUseIds = new HashSet<>();
    private final Set<String> emittedToolResultIds = new HashSet<>();
    private final Set<String> emittedThinkingStartIds = new HashSet<>();
    /**
     * 缓冲的 agent_message 文本。
     *
     * WARNING—切勿让 handleAgentMessageItem 写入此字段:
     * ==============================================================
     * 2026-06-30 commit 2951c5f2 的缓冲设计将 agent_message 文本暂存于此,等到
     * turn.completed 才 flush 成 content_delta——导致纯文本回答无流式,用户报
     * "答完才一次性推送"。修复(本文件 handleAgentMessageItem)后恒为空。
     *
     * 保留该字段和 flushPending* 方法仅作为<安全网>,handleAgentMessageItem
     * 每次只传 delta 不写 pending。若未来某位 AI 看到"pending 从未被写入"就
     * 删除此字段+flush 方法+调用点——可安全删除,但千万不要反过来让
     * handleAgentMessageItem 重新写入 pending(断流式回归)。
     * ==============================================================
     */
    private String pendingAgentMessageText = "";
    private SegmentKind lastSegmentKind = SegmentKind.NONE;
    // turn.completed 后置位,其后非 JSON 行视为进程收尾噪声,不再作为正文 delta 输出。
    private volatile boolean turnCompleted;

    private enum SegmentKind {
        NONE,
        TEXT,
        THINKING,
        TOOL
    }

    public CodexCliSession(String tabId) {
        this(tabId, null);
    }

    public CodexCliSession(String tabId, McpGatewayService gatewayService) {
        this.tabId = tabId;
        this.gatewayService = gatewayService;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        prepareForSend();
        return CliSessionExecutor.runAsync(() -> {
            long sendStartNanos = System.nanoTime();
            List<File> tempFiles = new ArrayList<>();
            StringBuilder diagnostic = new StringBuilder();
            StringBuilder cliError = new StringBuilder();
            Process process = null;
            CliProcessHandle currentHandle = null;
            try {
                LOG.info("[CliConcurrencyDiag][CodexCliSession] send task started"
                        + ": tabId=" + tabId
                        + ", cwd=" + (request.cwd() != null ? request.cwd() : "(none)")
                        + ", thread=" + Thread.currentThread().getName());
                long attachmentsStartNanos = System.nanoTime();
                List<File> images = attachmentHandler.processForCodex(request.attachments(), tempFiles);
                LOG.info("[CliConcurrencyDiag][CodexCliSession] attachments prepared"
                        + ": tabId=" + tabId
                        + ", images=" + images.size()
                        + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                        + ", attachmentsMs=" + elapsedMillis(attachmentsStartNanos)
                        + ", thread=" + Thread.currentThread().getName());
                boolean requestHasImages = !images.isEmpty();
                callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.MCP_SYNCING.value());
                McpGatewayCliConfig gatewayConfig = buildGatewayConfig(request);
                callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());
                List<String> gatewayOverrideArgs = (gatewayConfig != null && gatewayConfig.usable())
                        ? gatewayConfig.overrideArgs() : List.of();
                LOG.info("[CliConcurrencyDiag][CodexCliSession] building command"
                        + ": tabId=" + tabId
                        + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                        + ", thread=" + Thread.currentThread().getName());
                List<String> cmd = buildCommand(request, images, gatewayOverrideArgs);
                byte[] promptInput = buildPromptInput(request);
                LOG.info("[CodexCliSession][" + tabId + "] Command: " + String.join(" ", cmd)
                        + ", stdinBytes=" + promptInput.length);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Map<String, String> cliEnv = pb.environment();
                cliEnv.clear();
                cliEnv.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
                cliEnv.putAll(CliSettings.readCodexCliEnvironment());
                cliEnv.put(CliConstants.ARG_NO_COLOR, "1");
                CliEnvironmentBuilder.configureProjectPath(cliEnv, request.cwd());
                // CLI mode must be allowed to reach the real Codex API even if the host
                // process was launched with sandbox-network restrictions.
                cliEnv.remove(ENV_CODEX_SANDBOX_NETWORK_DISABLED);
                CliEnvironmentBuilder.applyExtraEnv(cliEnv, CodexCliCommandUtils.sanitizeEnv(request.extraEnv()));
                // gateway 经 -c 命令行覆盖注入(overrideArgs,见 buildCommand),不再改 CODEX_HOME env:
                // CODEX_HOME 保持真实 ~/.codex,零临时 home(2026-07-02 重构)。

                // CWD 设置放在 pb.start() 紧前面，避免 TOCTOU 竞态：
                // 如果目录在 check 和 start 之间被删除，Windows CreateProcess 会报
                // "系统找不到指定的路径" (ERROR_PATH_NOT_FOUND)。
                if (request.cwd() != null && !request.cwd().isBlank()) {
                    File cwd = new File(request.cwd());
                    if (cwd.isDirectory()) {
                        pb.directory(cwd);
                    } else {
                        LOG.warn("[CodexCliSession][" + tabId + "] CWD does not exist, falling back to home: " + request.cwd());
                        File homeDir = new File(PlatformUtils.getHomeDirectory());
                        if (homeDir.isDirectory()) {
                            pb.directory(homeDir);
                        }
                    }
                }

                LOG.info("[CliConcurrencyDiag][CodexCliSession] starting process"
                        + ": tabId=" + tabId
                        + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                        + ", thread=" + Thread.currentThread().getName());
                process = pb.start();
                currentHandle = new CliProcessHandle(process, "codex-tab-" + tabId);
                activeHandle = currentHandle;
                LOG.info("[CliConcurrencyDiag][CodexCliSession] process started"
                        + ": tabId=" + tabId
                        + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                        + ", thread=" + Thread.currentThread().getName());
                // 将 prompt 通过 stdin 传给 Codex，避免 Windows 命令行长度限制。
                if (userInterrupted.get()) {
                    currentHandle.interrupt();
                } else {
                    try (OutputStream stdin = process.getOutputStream()) {
                        stdin.write(promptInput);
                        stdin.flush();
                    }
                }
                LOG.info("[CliConcurrencyDiag][CodexCliSession] prompt written to stdin"
                        + ": tabId=" + tabId
                        + ", stdinBytes=" + promptInput.length
                        + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                        + ", thread=" + Thread.currentThread().getName());
                StringBuilder assistantContent = new StringBuilder();
                // 逐行读取原始字节，先尝试 UTF-8 解码，失败则回退到 Windows 中文编码。
                // 这解决了 Node.js (UTF-8) 和 cmd.exe (GBK) 混合输出的编码问题。
                Process runningProcess = process;
                CompletableFuture<Void> outputDrain = CliProcessLifecycle.drainAsync(runningProcess, () -> {
                    try (InputStream rawIn = runningProcess.getInputStream()) {
                        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
                        byte[] readBuf = new byte[8192];
                        int n;
                        boolean firstOutputLogged = false;
                        while ((n = rawIn.read(readBuf)) != -1) {
                            for (int i = 0; i < n; i++) {
                                byte b = readBuf[i];
                                if (b == '\n') {
                                    if (!firstOutputLogged) {
                                        firstOutputLogged = true;
                                        LOG.info("[CliConcurrencyDiag][CodexCliSession] first stdout line buffer reached"
                                                + ": tabId=" + tabId
                                                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                                                + ", thread=" + Thread.currentThread().getName());
                                    }
                                    processLine(lineBuf, diagnostic, callback, assistantContent, cliError);
                                } else {
                                    lineBuf.write(b);
                                }
                            }
                            if (fatalAbort) {
                                CliProcessLifecycle.terminate(runningProcess);
                                break;
                            }
                        }
                        // 处理最后一行（无尾随换行符）
                        if (lineBuf.size() > 0) {
                            processLine(lineBuf, diagnostic, callback, assistantContent, cliError);
                        }
                    }
                });

                CliProcessLifecycle.Outcome outcome = CliProcessLifecycle.await(process, outputDrain);

                if (fatalAbort) {
                    // 致命 provider/API 错误(parseEvent 已 onError):强制终止卡死的重连进程,
                    // 跳过正常退出码诊断(避免二次报错),直接完成回调。
                    callback.onComplete(false, assistantContent.toString(), null);
                    return;
                }
                int exitCode = outcome.exitCode();
                LOG.info("[CliConcurrencyDiag][CodexCliSession] process exited"
                        + ": tabId=" + tabId
                        + ", exitCode=" + exitCode
                        + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                        + ", thread=" + Thread.currentThread().getName());

                if (outcome.timedOut() && !wasInterrupted()) {
                    String err = CliErrorFormatter.formatError("Codex", "CLI request timed out");
                    callback.onError(err);
                    callback.onComplete(false, assistantContent.toString(), err);
                } else if (wasInterrupted()) {
                    callback.onInterrupted(assistantContent.toString(), CliConstants.I18N_REQUEST_INTERRUPTED);
                } else if (exitCode == 0) {
                    if (shouldReportCliErrorOnExit0(cliError.toString(), turnCompleted)) {
                        String err = formatCodexError(cliError.toString(), requestHasImages);
                        callback.onError(err);
                        callback.onComplete(false, assistantContent.toString(), err);
                    } else {
                        // turn 成功完成(turnCompleted)或无诊断。任何缓冲诊断都是非致命噪声
                        // (工具调用失败/警告/turn 后收尾行)——不翻转成功 turn 为失败
                        // (shouldReportCliErrorOnExit0 总闸;handleMcpFailure/handleToolDiagnostic 已在 parse 阶段
                        // 把已知类别路由到 status/thinking 给用户可见性)。残留诊断仅记日志不丢,便于排查。
                        if (!cliError.isEmpty()) {
                            LOG.info("[CodexCliSession][" + tabId
                                    + "] non-fatal diagnostics during successful turn (not reported as failure): "
                                    + cliError);
                        }
                        // [安全网] handleAgentMessageItem 已立即流式,pending 恒空,此调用是 no-op
                        flushPendingAgentMessageAsContent(callback, assistantContent);
                        CliSectionEmitter emitter = sectionEmitter(callback);
                        emitter.streamEnd();
                        emitter.messageEnd();
                        callback.onComplete(true, assistantContent.toString(), null);
                    }
                } else if (shouldReportExitError(exitCode)) {
                    String err = buildExitError(exitCode, diagnostic, cliError, requestHasImages);
                    maybeResetThreadAfterResumeFailure(!cliError.isEmpty() ? cliError : diagnostic);
                    callback.onError(err);
                    callback.onComplete(false, assistantContent.toString(), err);
                }
            } catch (Exception e) {
                LOG.warn("[CodexCliSession][" + tabId + "] send failed", e);
                if (wasInterrupted()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    String err = CliErrorFormatter.formatError("Codex", e.getMessage());
                    callback.onError(err);
                    callback.onComplete(false, null, err);
                }
            } finally {
                CliProcessLifecycle.terminate(process);
                if (activeHandle == currentHandle) {
                    activeHandle = null;
                }
                cleanupTempFiles(tempFiles);
                userInterrupted.set(false);
            }
        });
    }

    public void interrupt() {
        userInterrupted.set(true);
        CliProcessHandle h = activeHandle;
        if (h != null) {
            long startNanos = System.nanoTime();
            h.interrupt();
            LOG.info("[TabPerf] CodexCliSession.interrupt returned in "
                    + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
        }
    }

    void prepareForSend() {
        userInterrupted.set(false);
        fatalAbort = false;
        mcpNoticeEmitted = false;
        lastSegmentKind = SegmentKind.NONE;
        pendingAgentMessageText = "";
        turnCompleted = false;
        // 清理上一轮累积的去重/增量状态,避免跨轮污染(例如降级回首轮模式后 item id 从 item_0 重新计数,
        // 会命中上一轮残留的 id 导致工具块被 emitXxxOnce 吞掉)。
        assistantTextByItemId.clear();
        reasoningTextByItemId.clear();
        emittedToolUseIds.clear();
        emittedToolResultIds.clear();
        emittedThinkingStartIds.clear();
    }

    boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }

    boolean shouldReportExitError(int exitCode) {
        return exitCode != 0 && !wasInterrupted();
    }

    /**
     * resume --last 失败时(thread 已损坏/被删),重置 threadId 使下一轮回到首轮模式,避免死循环。
     */
    private void maybeResetThreadAfterResumeFailure(CharSequence diagnostic) {
        if (threadId == null || diagnostic == null) {
            return;
        }
        String text = diagnostic.toString().toLowerCase(Locale.ROOT);
        boolean resumeFailure = text.contains("no previous") || text.contains("no last")
                || text.contains("session not found") || text.contains("no conversation")
                || text.contains("resume target") || text.contains("conversation not found");
        if (resumeFailure) {
            LOG.info("[CodexCliSession] resume --last failed, resetting threadId to fall back to first turn: tab=" + tabId);
            threadId = null;
        }
    }

    public void dispose() {
        long startNanos = System.nanoTime();
        interrupt();
        LOG.info("[TabPerf] CodexCliSession.dispose returned in "
                + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
    }

    // ── event parsing ────────────────────────────────────────────────────────
    // Official `codex exec --json` contract (docs/codex/docs/exec.md):
    // - reasoning -> thinking summary block
    // - agent_message -> assistant body text
    // - command_execution / mcp_tool_call -> tool timeline
    // - turn.failed / error -> provider diagnostic path
    //
    // This session intentionally treats event type as the primary routing signal.
    // We no longer reinterpret agent_message content as tool transcript based on
    // its text shape; tool output should come from structured tool events.

    /**
     * 判定 codex error 事件是否为致命(不可恢复、会导致重连死循环)错误。
     * <p>这类错误(provider 不支持的 model 触发 502、网关 5xx、unexpected status)
     * 会让 codex 进入 Reconnecting 死循环且子进程永不退出,必须立即上报并终止,
     * 而非静默缓冲等进程退出(进程不会退出)。
     */
    static boolean isFatalCodexError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("reconnecting")
                || lower.contains("bad gateway")
                || lower.contains("gateway timeout")
                || lower.contains("service unavailable")
                || lower.contains("unexpected status")
                || lower.matches(".*\\b[45]\\d{2}\\b.*");
    }

    /**
     * 判定 exit0 时是否应把缓冲的 cliError 报为回合失败。<b>深层隐患修复(2026-07-03)。</b>
     *
     * <p><b>原则:</b>codex 以 exit0 退出 = codex 自身宣告成功。若已发 {@code turn.completed}
     * (正式完成 turn),任何 stderr 缓冲诊断都是<b>非致命噪声</b>(工具调用失败/警告/turn 后收尾行),
     * 不得翻转成功 turn 为失败——否则用户看到 "Codex CLI 请求失败/This response stopped" 且
     * turn-fail 快照丢失 thinking 块(思考区消失)。
     *
     * <p><b>仅当 codex 未发 turn.completed 却以 exit0 结束且有诊断时</b>,才视为真正的静默失败
     * (中途 bail 出错,未走 turn.failed/error 事件流)。
     *
     * <p><b>零回归保证:</b>判定 {@code cliError 非空 && !turnCompleted} 是旧条件 {@code cliError 非空}
     * 的<b>严格收紧</b>(失败集 ⊆ 旧失败集),只能把旧误报转成功,不会把旧成功转失败。
     *
     * <p>这是 false-positive 陷阱的<b>总闸安全网</b>。{@link #handleMcpFailure}/{@link #handleToolDiagnostic}
     * 仍保留为<b>UX 层</b>(把已知非致命诊断路由到 status 提示/thinking 区给用户可见性,并保持 cliError 干净),
     * 三者 defense-in-depth:总闸兜底未知类别的非致命诊断不再误报。
     *
     * @param cliError      缓冲的诊断文本(可能为 null/空)
     * @param turnCompleted 是否已收到 codex turn.completed 事件
     * @return true 表示应作为回合失败上报(仅未完成 turn 且有诊断)
     */
    static boolean shouldReportCliErrorOnExit0(String cliError, boolean turnCompleted) {
        if (cliError == null || cliError.isEmpty()) {
            return false;
        }
        return !turnCompleted;
    }

    /**
     * MCP 连接失败(本地 server 未启动等)处理:识别后降级为非阻塞 status 提示(镜像重连处理),
     * 而非缓冲为回合错误 / 报错。每回合仅发一次 toast(rmcp worker 可能重试多次),但每次匹配都抑制。
     * <p>返回 true 表示文本命中 MCP 连接失败,调用方应跳过 cliError 缓冲 / onError。
     *
     * @param text     诊断行或事件 message(可能为 null)
     * @param callback 用于发 {@link CliConstants#CODEX_MSG_STATUS}
     * @return true 表示命中 MCP 连接失败(应抑制,不计入回合错误)
     */
    private boolean handleMcpFailure(String text, CliSessionCallback callback) {
        // GatewayDownMatcher 先判:[melon-gateway-down] 是 stdio-client 降级标记(更明确),
        // 命中发 GATEWAY_DOWN_NOTICE(区别 MCP_SKIPPED_NOTICE:整轮 gateway 工具降级 vs 单 server 跳过)。
        // best-effort:仅当 codex 透传 melon_gateway stderr 时命中,不命中不影响功能(5s 超时兜底)。
        if (GatewayDownMatcher.isGatewayDown(text)) {
            if (!mcpNoticeEmitted) {
                mcpNoticeEmitted = true;
                sectionEmitter(callback).status(GatewayDownMatcher.GATEWAY_DOWN_NOTICE);
            }
            return true;
        }
        if (!McpErrorMatcher.isMcpConnectionFailure(text)) {
            return false;
        }
        if (!mcpNoticeEmitted) {
            mcpNoticeEmitted = true;
            sectionEmitter(callback).status(McpErrorMatcher.MCP_SKIPPED_NOTICE);
        }
        return true;
    }

    /**
     * codex 工具执行层(codex_core::tools::router 等)单次调用失败的 tracing 日志判定。
     * <p>这类日志(如 shell 工具跑 PowerShell 列目录失败:"ERROR codex_core::tools::router:
     * error=`'pwsh.exe' -Command '...'")是<b>单次工具调用</b>的失败,codex 会把错误回灌给模型
     * 继续当前 turn,非回合致命。结构化 JSON 事件流(item.completed 的 command_execution/mcp_tool_call)
     * 已把工具结果(含错误)渲染为工具块,此 raw tracing 日志是冗余噪声。
     * <p><b>不得缓冲进 cliError:</b>否则 codex 正常完成 turn(exit 0)时,line 267-271 的
     * "exitCode==0 && cliError 非空" 分支会误报 "Codex CLI 请求失败 / This response stopped",
     * 且 turn-fail 快照丢失 thinking 块(思考区消失)。
     * <p>对称 {@link #handleMcpFailure} 的 MCP 连接失败降级模式,但工具执行错误经 JSON 事件流
     * 已有用户可见呈现,此处仅抑制 cliError 缓冲(可选路由到 thinking 保空白工具块可见性)。
     *
     * @param text 诊断行(可能为 null)
     * @return true 表示命中工具执行层日志(应抑制,不计入回合错误)
     */
    static boolean isNonFatalToolDiagnostic(String text) {
        if (text == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains("codex_core::tools::");
    }

    /**
     * 处理 codex 工具执行层 tracing 日志:抑制 cliError 缓冲(避免 exit0 误报回合失败),
     * 在 tool 段内路由到 thinking(对齐既有诊断→thinking 模式,保空白工具块时的可见性)。
     * 返回 true 表示已处理,调用方应跳过 cliError 缓冲。
     */
    private boolean handleToolDiagnostic(String line, CliSessionCallback callback) {
        if (!isNonFatalToolDiagnostic(line)) {
            return false;
        }
        if (lastSegmentKind == SegmentKind.TOOL) {
            emitThinkingText(callback, line + "\n");
        }
        return true;
    }

    private void parseEvent(
            String line,
            CliSessionCallback callback,
            StringBuilder assistantContent,
            StringBuilder cliError
    ) {
        try {
            if (shouldIgnoreRawLine(line)) {
                return;
            }
            if (isLikelyDiagnosticLine(line)) {
                if (handleMcpFailure(line, callback)) {
                    // MCP 连接失败(本地 server 未启动):降级为非阻塞提示,不缓冲为回合错误
                    return;
                }
                if (handleToolDiagnostic(line, callback)) {
                    // 工具执行层(codex_core::tools::router 等)单次调用失败:codex 回灌模型继续 turn,
                    // 非回合致命。抑制 cliError 缓冲(否则 exit0 误报"请求失败"+思考区消失)。
                    return;
                }
                if (cliError != null) {
                    CliErrorFormatter.appendDiagnosticLine(cliError, line);
                }
                return;
            }
            JsonObject event = gson.fromJson(line, JsonObject.class);
            if (event == null) {
                return;
            }
            String type = getString(event, CommonConstants.JSON_KEY_TYPE);
            if (type == null) {
                emitNormalizedAssistantBlock(eventNormalizer.unknown(null, null, event), callback);
                return;
            }

            switch (type) {
                case CliConstants.CODEX_EVENT_THREAD_STARTED -> {
                    String id = getString(event, "thread_id");
                    if (id != null) {
                        threadId = id;
                        sectionEmitter(callback).sessionId(id);
                    }
                    lastSegmentKind = SegmentKind.NONE;
                    CliSectionEmitter emitter = sectionEmitter(callback);
                    emitter.streamStart();
                    emitter.messageStart();
                }
                case CliConstants.CODEX_EVENT_TURN_STARTED -> {
                    lastSegmentKind = SegmentKind.NONE;
                    sectionEmitter(callback).messageStart();
                }
                case CliConstants.CODEX_EVENT_ITEM_STARTED, CliConstants.CODEX_EVENT_ITEM_UPDATED, CliConstants.CODEX_EVENT_ITEM_COMPLETED -> {
                    if (event.has("item") && event.get("item").isJsonObject()) {
                        JsonObject item = event.getAsJsonObject("item");
                        handleItem(type, item, callback, assistantContent);
                    }
                }
                case CliConstants.CODEX_EVENT_RESPONSE_ITEM -> {
                    if (event.has("payload") && event.get("payload").isJsonObject()) {
                        handleResponseItem(event.getAsJsonObject("payload"), callback, assistantContent);
                    }
                }
                case CliConstants.CODEX_EVENT_TURN_COMPLETED -> {
                    turnCompleted = true;
                    // [安全网] handleAgentMessageItem 已立即流式,pending 恒空,此调用是 no-op
                    flushPendingAgentMessageAsContent(callback, assistantContent);
                    if (event.has("usage") && event.get("usage").isJsonObject()) {
                        JsonObject usage = event.getAsJsonObject("usage");
                        CliSectionEmitter emitter = sectionEmitter(callback);
                        emitter.usage(usage.toString());
                        emitter.result(buildUsageResultMessage(usage).toString());
                    }
                }
                case CliConstants.CODEX_EVENT_TURN_FAILED -> {
                    String msg = extractErrorMessage(event, "Turn failed");
                    if (handleMcpFailure(msg, callback)) {
                        // MCP 连接失败:降级为非阻塞提示,不缓冲为回合错误
                    } else if (cliError != null) {
                        CliErrorFormatter.appendDiagnosticLine(cliError, msg);
                    } else {
                        callback.onError(formatCodexError(msg, false));
                    }
                }
                case CliConstants.CODEX_EVENT_ERROR -> {
                    String msg = getString(event, "message");
                    if (msg == null) {
                        msg = event.toString();
                    }
                    if (handleMcpFailure(msg, callback)) {
                        // MCP 连接失败:降级为非阻塞提示,不缓冲为回合错误
                    } else if (isFatalCodexError(msg)) {
                        // 致命 provider/API 错误(502 重连死循环等):codex 会反复重连且子进程永不退出,
                        // 静默缓冲会让前端无限停留在加载状态。立即上报并置 fatalAbort,
                        // 供 stdout 读取循环提前 break + destroyForcibly 终止卡死的进程。
                        fatalAbort = true;
                        callback.onError(formatCodexError(msg, false));
                    } else if (cliError != null) {
                        CliErrorFormatter.appendDiagnosticLine(cliError, msg);
                    } else {
                        callback.onError(formatCodexError(msg, false));
                    }
                }
                default -> {
                    emitNormalizedAssistantBlock(eventNormalizer.unknown(type, null, event), callback);
                }
            }
        } catch (Exception e) {
            // 非 JSON 行：当作纯文本 delta
            if (shouldIgnoreRawLine(line)) {
                return;
            }
            if (isLikelyDiagnosticLine(line)) {
                if (handleToolDiagnostic(line, callback)) {
                    // 工具执行层 tracing 日志:抑制 cliError 缓冲(避免 exit0 误报回合失败)。
                    return;
                }
                if (cliError != null) {
                    CliErrorFormatter.appendDiagnosticLine(cliError, line);
                }
                // 工具执行阶段的诊断信息（如路径错误）路由到 thinking 区域，
                // 避免用户看到空白的工具结果而不知道发生了什么。
                if (lastSegmentKind == SegmentKind.TOOL) {
                    emitThinkingText(callback, line + "\n");
                }
                return;
            }
            String text = line + "\n";
            if (lastSegmentKind == SegmentKind.TOOL) {
                emitThinkingText(callback, text);
            } else if (turnCompleted) {
                // turn 已完成,后续非 JSON 行是进程收尾噪声,静默忽略
                // (不进 cliError 以免 exitCode==0 时误报错,也不作为正文 delta)。
                LOG.debug("[CodexCliSession] ignoring post-turn non-JSON line: " + line);
            } else {
                emitContentDelta(callback, assistantContent, text);
            }
        }
    }

    /**
     * Route official item.* payloads by item type.
     * <p>
     * Mapping follows Codex `--json` docs:
     * - reasoning -> thinking area
     * - agent_message -> assistant response body
     * - command_execution / mcp_tool_call -> tool blocks
     */
    private void handleItem(
            String eventType,
            JsonObject item,
            CliSessionCallback callback,
            StringBuilder assistantContent
    ) {
        String itemType = getString(item, CommonConstants.JSON_KEY_TYPE);
        if (itemType == null) {
            emitNormalizedAssistantBlock(eventNormalizer.unknown(eventType, null, item), callback);
            return;
        }
        switch (itemType) {
            case CliConstants.CODEX_ITEM_REASONING, CliConstants.CODEX_ITEM_AGENT_REASONING -> handleReasoningItem(item, callback);
            case CliConstants.CODEX_ITEM_AGENT_MESSAGE -> handleAgentMessageItem(item, callback, assistantContent);
            case CliConstants.CODEX_ITEM_COMMAND_EXECUTION -> handleCommandExecutionItem(eventType, item, callback);
            case CliConstants.CODEX_ITEM_FUNCTION_CALL, CliConstants.CODEX_ITEM_TOOL_CALL -> handleFunctionCallPayload(item, callback);
            case CliConstants.CODEX_ITEM_CUSTOM_TOOL_CALL -> handleCustomToolCallPayload(item, callback);
            case CliConstants.CODEX_ITEM_FUNCTION_CALL_OUTPUT -> handleFunctionCallOutputPayload(item, callback);
            case CliConstants.CODEX_ITEM_MCP_TOOL_CALL -> {
                handleMcpToolCallItem(eventType, item, callback);
                emitNormalizedAssistantBlock(eventNormalizer.normalizeItem(eventType, item), callback);
            }
            default -> emitNormalizedAssistantBlock(eventNormalizer.normalizeItem(eventType, item), callback);
        }
    }

    private void emitNormalizedAssistantBlock(Optional<JsonObject> normalizedMessage, CliSessionCallback callback) {
        if (normalizedMessage.isEmpty()) {
            return;
        }
        markSegment(callback, SegmentKind.TOOL);
        sectionEmitter(callback).assistantRaw(normalizedMessage.get());
    }

    /**
     * Official reasoning items are rendered into the thinking area.
     * Codex documents reasoning as a summary of assistant thinking, not command output.
     */
    private void handleReasoningItem(JsonObject item, CliSessionCallback callback) {
        String text = firstNonBlank(
                getString(item, "text"),
                getString(item, "summary"),
                getString(item, "content")
        );
        if (text == null || text.isEmpty()) {
            return;
        }
        String id = stableItemId(item, "reasoning");
        markSegment(callback, SegmentKind.THINKING);
        // 首次遇到该 reasoning item 时发送 thinking 开始信号
        if (emittedThinkingStartIds.add(id)) {
            sectionEmitter(callback).thinkingStart();
        }
        String previous = reasoningTextByItemId.getOrDefault(id, "");
        String delta = appendedDelta(previous, text);
        reasoningTextByItemId.put(id, text);
        if (!delta.isEmpty()) {
            sectionEmitter(callback).thinkingDelta(delta);
        }
    }

    /**
     * 处理官方 agent_message items,组装到 assistant 正文。
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * ⚠ WARNING:此方法必须保持<立即流式>推送,不可回退到缓冲设计。
     * ═══════════════════════════════════════════════════════════════════════════
     * codex exec --json 契约定义 agent_message 为 assistant 正文,通过
     * item.updated 多次发送累积文本。appendedDelta 计算尾部增量,每次立即流式
     * push content_delta——这是前端实现打字机效果的前提。
     *
     * 2026-06-10 commit 2951c5f2 改为缓冲到 turn.completed 再 flush,
     * 导致纯文本回答完全无流式,用户报"答完才一次性显示"(issue #…)。
     * 2026-06-30 修复:始终立即推送,删除 shouldStreamAgentMessage 条件判定。
     *
     * 回滚此方法 = 直接复现该 bug。见 handleAgentMessageItem 方法体内
     * ═══════════════════════════════════════════════════════════════════════════
     * 的设计日志和 WARNING。
     */
    private void handleAgentMessageItem(
            JsonObject item,
            CliSessionCallback callback,
            StringBuilder assistantContent
    ) {
        String text = getString(item, "text");
        if (text == null || text.isEmpty()) {
            return;
        }
        String id = stableItemId(item, "agent_message");
        String previous = assistantTextByItemId.get(id);
        if (previous == null && assistantContent.length() > 0 && text.startsWith(assistantContent.toString())) {
            previous = assistantContent.toString();
        }
        if (previous == null) {
            previous = "";
        }
        String delta = appendedDelta(previous, text);
        assistantTextByItemId.put(id, text);
        // ═══════════════════════════════════════════════════════════════════════
        //  ⚠ WARNING—不可回滚此流式逻辑(见 2951c5f2→2026-06-30 修复):
        //  codex exec --json 契约定义 agent_message 为 assistant 正文。
        //  它通过 item.updated 多次发送累积文本,appendedDelta 可算出尾部增量。
        //  这里必须<每次立即流式推送 content_delta>,而非缓冲到 turn.completed
        //  一次性输出——后者正是用户报"codex 无流式答完才显示"的根因。
        //  历史上曾因"工具前协调文本可能含工具转录"而走缓冲→thinking 降级,
        //  这违反契约:agent_message 语义即正文,不因后续可能出现 tool 而降级。
        //  行为对齐 Claude CLI 与 ai-bridge SDK(codex-event-handler.js)路径。
        //  回滚此段 = 纯文本回答断流式回归。勿让 handleAgentMessageItem 写入
        //  pendingAgentMessageText(其已降级为恒空的安全网)。
        // ═══════════════════════════════════════════════════════════════════════
        pendingAgentMessageText = "";
        if (!delta.isEmpty()) {
            emitContentDelta(callback, assistantContent, delta);
        }
    }

    /**
     * Official command_execution items become tool_use/tool_result pairs.
     * Structured command output comes from aggregated_output/output/stdout/stderr,
     * not from agent_message.
     */
    private void handleCommandExecutionItem(String eventType, JsonObject item, CliSessionCallback callback) {
        String id = stableItemId(item, "command_execution");
        String command = extractCommand(item);
        // [安全网] handleAgentMessageItem 已立即流式,不再需 tool 前 flush 降级 thinking
        flushPendingAgentMessageAsThinking(callback);
        markSegment(callback, SegmentKind.TOOL);
        if ("item.started".equals(eventType) || "item.updated".equals(eventType)) {
            emitToolUseOnce(callback, id, "Bash", commandInput(command));
            return;
        }

        emitToolUseOnce(callback, id, "Bash", commandInput(command));
        emitToolResultOnce(callback, id, isItemError(item), extractCommandOutput(item));
    }

    /**
     * Official mcp_tool_call items are normalized into tool_use/tool_result pairs.
     */
    private void handleMcpToolCallItem(String eventType, JsonObject item, CliSessionCallback callback) {
        String id = stableItemId(item, "mcp_tool_call");
        String toolName = normalizeMcpToolName(getString(item, "server"), getString(item, "tool"));
        JsonObject input = item.has("arguments") && item.get("arguments").isJsonObject()
                ? item.getAsJsonObject("arguments")
                : new JsonObject();
        // [安全网] handleAgentMessageItem 已立即流式,此调用是 no-op
        flushPendingAgentMessageAsThinking(callback);
        markSegment(callback, SegmentKind.TOOL);
        if ("item.started".equals(eventType) || "item.updated".equals(eventType)) {
            emitToolUseOnce(callback, id, toolName, input);
            return;
        }

        boolean isError = isItemError(item) || item.has("error");
        emitToolUseOnce(callback, id, toolName, input);
        emitToolResultOnce(callback, id, isError, extractMcpResult(item));
    }

    /**
     * Compatibility path for response_item payloads.
     * These are not part of the primary exec.md event list, but the project keeps
     * them to recover tool calls from older/alternate Codex event shapes.
     */
    private void handleResponseItem(JsonObject payload, CliSessionCallback callback, StringBuilder assistantContent) {
        String payloadType = getString(payload, CommonConstants.JSON_KEY_TYPE);
        if (payloadType == null) {
            emitNormalizedAssistantBlock(eventNormalizer.normalizePayload(payload), callback);
            return;
        }
        switch (payloadType) {
            case CliConstants.CODEX_ITEM_REASONING, CliConstants.CODEX_ITEM_AGENT_REASONING -> handleReasoningItem(payload, callback);
            case CliConstants.CODEX_ITEM_AGENT_MESSAGE -> handleAgentMessageItem(payload, callback, assistantContent);
            case CliConstants.CODEX_PAYLOAD_FUNCTION_CALL -> handleFunctionCallPayload(payload, callback);
            case CliConstants.CODEX_PAYLOAD_FUNCTION_CALL_OUTPUT -> handleFunctionCallOutputPayload(payload, callback);
            case CliConstants.CODEX_PAYLOAD_CUSTOM_TOOL_CALL -> handleCustomToolCallPayload(payload, callback);
            default -> emitNormalizedAssistantBlock(eventNormalizer.normalizePayload(payload), callback);
        }
    }

    private void handleFunctionCallPayload(JsonObject payload, CliSessionCallback callback) {
        String name = firstNonBlank(getString(payload, "name"), getString(payload, CommonConstants.JSON_KEY_TOOL), "unknown");
        String id = stableItemId(payload, "unknown");
        JsonObject input = parseFunctionCallArguments(payload);
        // [安全网] handleAgentMessageItem 已立即流式,此调用是 no-op
        flushPendingAgentMessageAsThinking(callback);
        markSegment(callback, SegmentKind.TOOL);
        emitToolUseOnce(callback, id, name, input);
    }

    private void handleFunctionCallOutputPayload(JsonObject payload, CliSessionCallback callback) {
        String id = stableItemId(payload, "unknown");
        boolean isError = isItemError(payload);
        String output = firstNonBlank(getString(payload, "output"), getString(payload, "result"));
        // [安全网] handleAgentMessageItem 已立即流式,此调用是 no-op
        flushPendingAgentMessageAsThinking(callback);
        markSegment(callback, SegmentKind.TOOL);
        emitToolResultOnce(callback, id, isError, output != null ? output : "(no output)");
    }

    private void handleCustomToolCallPayload(JsonObject payload, CliSessionCallback callback) {
        String name = firstNonBlank(getString(payload, "name"), "unknown");
        String id = stableItemId(payload, "unknown");
        JsonObject input = new JsonObject();
        String toolInput = firstNonBlank(getString(payload, "input"), getString(payload, "arguments"));
        if (toolInput != null) {
            input.addProperty("patch", toolInput);
            input.addProperty("input", toolInput);
            String filePath = extractPatchFilePath(toolInput);
            if (filePath != null) {
                input.addProperty("file_path", filePath);
            }
        }
        // [安全网] handleAgentMessageItem 已立即流式,此调用是 no-op
        flushPendingAgentMessageAsThinking(callback);
        markSegment(callback, SegmentKind.TOOL);
        emitToolUseOnce(callback, id, name, input);
    }

    /**
     * 安全网:将缓冲的 agent_message 文本冲刷到 assistant 正文流。
     *
     * WARNING—此方法目前恒为 no-op(仅安全网):
     * ==============================================================
     * handleAgentMessageItem 已改为立即流式(不写 pendingAgentMessageText),
     * 故此方法直到末尾 `buildAssistantMessage` 之前都不会命中。
     * 保留它以保护边界:万一某条 agent_message 因前端尚未就绪等竞态未能流式,
     * turn.completed(或进程退出)时仍能兜底推送。
     *
     * 可安全删除此方法及其两处调用点(line 241 exit0 / line 440 turn.completed),
     * 但<千万不要>反过来从 handleAgentMessageItem 重新写 pendingAgentMessageText!
     * 那将直接回归"一次 push"而非流式,复现 2951c5f2 的回退=断流式 bug。
     * ==============================================================
     */
    private void flushPendingAgentMessageAsContent(CliSessionCallback callback, StringBuilder assistantContent) {
        if (pendingAgentMessageText == null || pendingAgentMessageText.isBlank()) {
            pendingAgentMessageText = "";
            return;
        }
        String text = pendingAgentMessageText;
        pendingAgentMessageText = "";

        String delta = appendedDelta(assistantContent.toString(), text);
        if (!delta.isEmpty()) {
            // 当已有正文且 text 不是其前缀时,这是一段独立的新正文(而非纯追加),
            // 把整段当 delta 追加会与已有正文拼接重复。此时先重置块再作为新内容输出。
            boolean freshBlock = assistantContent.length() > 0 && !text.startsWith(assistantContent.toString());
            if (freshBlock) {
                sectionEmitter(callback).blockReset();
                assistantContent.setLength(0);
                delta = text;
            }
            emitContentDelta(callback, assistantContent, delta);
        }
        sectionEmitter(callback).assistantRaw(buildAssistantMessage(text));
    }

    private void emitContentDelta(
            CliSessionCallback callback,
            StringBuilder assistantContent,
            String text
    ) {
        if (text == null || text.isEmpty()) {
            return;
        }
        markSegment(callback, SegmentKind.TEXT);
        sectionEmitter(callback).contentDelta(assistantContent, text);
    }

    private CliSectionEmitter sectionEmitter(CliSessionCallback callback) {
        return new CliSectionEmitter(callback::onMessage);
    }

    /**
     * 安全网:将缓冲文本冲刷到 thinking 通道。
     *
     * WARNING—此方法目前恒为 no-op(仅安全网):
     * ==============================================================
     * handleAgentMessageItem 已改为立即流式(不写 pendingAgentMessageText),
     * 且 agent_message 契约定义其仅作为正文,不因后续紧跟 tool 而降级为 thinking。
     * 保留此方法只因它在 5 个工具 handler 中有调用点,
     * 删除它需要同时删除 5 处调用且不影响任何逻辑(一律 no-op)。
     *
     * 可安全删除此方法及其 5 处调用点(handleCommandExecutionItem /
     * handleMcpToolCallItem / handleFunctionCallPayload /
     * handleFunctionCallOutputPayload / handleCustomToolCallPayload),
     * 但<千万不要>把它从"工具前清空"改回"工具前 flush",那会复现
     * 2951c5f2 的"agent_message 降级 thinking"过度设计。
     * ==============================================================
     */
    private void flushPendingAgentMessageAsThinking(CliSessionCallback callback) {
        if (pendingAgentMessageText == null || pendingAgentMessageText.isBlank()) {
            pendingAgentMessageText = "";
            return;
        }
        String text = pendingAgentMessageText;
        pendingAgentMessageText = "";
        emitThinkingText(callback, text);
    }

    /**
     * Emit text through the thinking channel.
     * Used for official reasoning items and for buffered pre-tool text flushed at a
     * structured tool boundary.
     */
    private void emitThinkingText(CliSessionCallback callback, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        markSegment(callback, SegmentKind.THINKING);
        CliSectionEmitter emitter = sectionEmitter(callback);
        emitter.thinkingStart();
        emitter.thinkingDelta(text);
    }

    private void markSegment(CliSessionCallback callback, SegmentKind nextKind) {
        if (nextKind == null || nextKind == SegmentKind.NONE) {
            return;
        }
        boolean crossesToolBoundary =
                (lastSegmentKind == SegmentKind.TOOL && nextKind != SegmentKind.TOOL)
                        || (lastSegmentKind != SegmentKind.NONE
                        && lastSegmentKind != SegmentKind.TOOL
                        && nextKind == SegmentKind.TOOL);
        if (crossesToolBoundary) {
            sectionEmitter(callback).blockReset();
        }
        lastSegmentKind = nextKind;
    }

    private JsonObject buildAssistantMessage(String text) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        content.add(block);
        message.add("content", content);
        raw.add("message", message);
        return raw;
    }

    private JsonObject buildToolUseMessage(String id, String name, JsonObject input) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", id);
        block.addProperty("name", name);
        block.add("input", input != null ? input : new JsonObject());
        content.add(block);
        message.add("content", content);
        raw.add("message", message);
        return raw;
    }

    private JsonObject buildToolResultMessage(String toolUseId, boolean isError, String contentText) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "user");
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", toolUseId);
        block.addProperty("is_error", isError);
        block.addProperty("content", contentText == null || contentText.isBlank() ? "(no output)" : contentText);
        content.add(block);
        message.add("content", content);
        raw.add("message", message);
        return raw;
    }

    private JsonObject buildUsageResultMessage(JsonObject usage) {
        JsonObject mappedUsage = new JsonObject();
        mappedUsage.addProperty("input_tokens", getInt(usage, "input_tokens"));
        mappedUsage.addProperty("output_tokens", getInt(usage, "output_tokens"));
        mappedUsage.addProperty("cache_creation_input_tokens", 0);
        mappedUsage.addProperty("cache_read_input_tokens", getInt(usage, "cached_input_tokens"));

        JsonObject raw = new JsonObject();
        raw.add("usage", mappedUsage);
        return raw;
    }

    private void emitToolUseOnce(CliSessionCallback callback, String id, String name, JsonObject input) {
        if (!emittedToolUseIds.add(id)) {
            return;
        }
        sectionEmitter(callback).assistantRaw(buildToolUseMessage(id, name, input));
    }

    private void emitToolResultOnce(CliSessionCallback callback, String id, boolean isError, String content) {
        if (!emittedToolResultIds.add(id)) {
            return;
        }
        sectionEmitter(callback).userRaw(buildToolResultMessage(id, isError, content));
    }

    private JsonObject parseFunctionCallArguments(JsonObject payload) {
        JsonObject empty = new JsonObject();
        if (!payload.has("arguments") || payload.get("arguments").isJsonNull()) {
            return empty;
        }
        JsonElement arguments = payload.get("arguments");
        if (arguments.isJsonObject()) {
            return arguments.getAsJsonObject();
        }
        if (!arguments.isJsonPrimitive()) {
            return empty;
        }
        try {
            JsonElement parsed = gson.fromJson(arguments.getAsString(), JsonElement.class);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : empty;
        } catch (Exception ignored) {
            return empty;
        }
    }

    private JsonObject commandInput(String command) {
        JsonObject input = new JsonObject();
        input.addProperty("command", command);
        input.addProperty("description", commandDescription(command));
        return input;
    }

    // ── command builder ──────────────────────────────────────────────────────

    /**
     * 构造 codex 命令。
     * 注意:codex 0.131.0 中 `codex exec` 与 `codex exec resume` 接受的 flag 集合是不同的:
     * - exec:        --color / -s --sandbox / -C --cd / --add-dir / -m / -i --image<FILE>... / --json
     * `-i, --image <FILE>...` 是 num_args=1.. 贪婪参数,后续位置参数 prompt 会被吞掉,
     * 因此带图片时需要在 prompt 前插入 `--` 分隔符。
     * - exec resume: 仅 --last / --all / -m / -i --image<FILE> / --json / -c / 各种 --dangerously-* /
     * --skip-git-repo-check / --ephemeral 等;
     * resume 子命令 *不接受* --color / --sandbox / -C / --add-dir。
     * resume 的 `-i, --image <FILE>` 是单值非贪婪,不需要 `--` 分隔符。
     * `-a, --ask-for-approval` 是 top-level 全局 flag,在 exec 与 exec resume 下都可用。
     * prompt 统一通过 stdin 传递，命令行末尾使用 `-` 显式要求 Codex 从 stdin 读取。
     */
    private List<String> buildCommand(CliSendRequest request, List<File> images,
                                      List<String> gatewayOverrideArgs) {
        CodexCliCommandUtils.PermissionSelection perm = CodexCliCommandUtils.selectPermission(
                request.permissionMode(), readSandboxMode(request.cwd()));

        List<String> cmd = new ArrayList<>();
        CodexCliCommandUtils.addCodexExecutable(cmd, CodexCliResolver.findExecutable());
        CodexCliCommandUtils.addCodexGlobalOptions(cmd, perm);

        if (threadId != null) {
            appendResumeArgs(cmd, request, images, gatewayOverrideArgs);
        } else {
            appendExecArgs(cmd, request, images, perm, gatewayOverrideArgs);
        }
        return cmd;
    }

    private McpGatewayCliConfig buildGatewayConfig(CliSendRequest request) {
        if (gatewayService == null) {
            return McpGatewayCliConfig.disabled("No MCP Gateway service");
        }
        return gatewayService.buildCliConfig(ProviderType.CODEX, tabId, request.cwd());
    }

    /**
     * 首次会话:codex exec ... [-- PROMPT]
     */
    private void appendExecArgs(List<String> cmd, CliSendRequest request, List<File> images,
                                CodexCliCommandUtils.PermissionSelection perm,
                                List<String> gatewayOverrideArgs) {
        cmd.add(CliConstants.CODEX_ARG_EXEC);
        cmd.add(CliConstants.CODEX_ARG_JSON);
        cmd.add(CliConstants.CODEX_ARG_COLOR);
        cmd.add(CliConstants.CODEX_ARG_NEVER);
        cmd.add(CliConstants.CODEX_ARG_SANDBOX);
        cmd.add(perm.sandbox());

        if (request.cwd() != null && !request.cwd().isBlank()) {
            cmd.add(CliConstants.CODEX_ARG_C);
            cmd.add(request.cwd());
        }
        if (request.model() != null && !request.model().isBlank()) {
            cmd.add(CliConstants.CODEX_ARG_M);
            cmd.add(request.model());
        }
        if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
            cmd.add(CliConstants.CODEX_ARG_C_CONFIG);
            cmd.add(codexConfigOverride(
                    CliConstants.CODEX_CONFIG_MODEL_REASONING_EFFORT,
                    request.reasoningEffort()
            ));
        }
        if (request.thinkingOutputEnabled()) {
            cmd.add(CliConstants.CODEX_ARG_C_CONFIG);
            cmd.add(codexConfigOverride(
                    CliConstants.CODEX_CONFIG_MODEL_REASONING_SUMMARY,
                    CliConstants.CODEX_REASONING_SUMMARY_AUTO
            ));
        }
        // gateway 注入:-c 命令行覆盖(melon_gateway 定义 + 逐个禁真实 server)。
        // 与 reasoning effort 的 -c 同位放置。args-array 经原生 codex.exe argv 直传可靠。
        if (gatewayOverrideArgs != null && !gatewayOverrideArgs.isEmpty()) {
            cmd.addAll(gatewayOverrideArgs);
        }
        for (File img : images) {
            cmd.add(CliConstants.CODEX_ARG_IMAGE);
            cmd.add(img.getAbsolutePath());
        }

        // exec 子命令的 --image 是贪婪参数,必须用 `--` 分隔位置参数 prompt。
        if (!images.isEmpty()) {
            cmd.add(CliConstants.CODEX_ARG_SEPARATOR);
        }
        cmd.add(CliConstants.CODEX_ARG_STDIN);
    }

    private static String codexConfigOverride(String key, String value) {
        return key + "=\"" + value + "\"";
    }

    /**
     * 续接会话:codex exec resume --last ... PROMPT
     */
    private void appendResumeArgs(List<String> cmd, CliSendRequest request, List<File> images,
                                 List<String> gatewayOverrideArgs) {
        cmd.add(CliConstants.CODEX_ARG_EXEC);
        cmd.add(CliConstants.CODEX_ARG_RESUME);
        cmd.add(CliConstants.CODEX_ARG_LAST);
        cmd.add(CliConstants.CODEX_ARG_JSON);

        if (request.model() != null && !request.model().isBlank()) {
            cmd.add(CliConstants.CODEX_ARG_M);
            cmd.add(request.model());
        }
        if (request.reasoningEffort() != null && !request.reasoningEffort().isBlank()) {
            cmd.add(CliConstants.CODEX_ARG_C_CONFIG);
            cmd.add(codexConfigOverride(
                    CliConstants.CODEX_CONFIG_MODEL_REASONING_EFFORT,
                    request.reasoningEffort()
            ));
        }
        if (request.thinkingOutputEnabled()) {
            cmd.add(CliConstants.CODEX_ARG_C_CONFIG);
            cmd.add(codexConfigOverride(
                    CliConstants.CODEX_CONFIG_MODEL_REASONING_SUMMARY,
                    CliConstants.CODEX_REASONING_SUMMARY_AUTO
            ));
        }
        // gateway 注入(同 exec 路径,-c exec resume 同样支持 -c,见调研文档 §5)。
        if (gatewayOverrideArgs != null && !gatewayOverrideArgs.isEmpty()) {
            cmd.addAll(gatewayOverrideArgs);
        }
        // resume 的 --image 是单值非贪婪,无需 `--` 分隔符。
        for (File img : images) {
            cmd.add(CliConstants.CODEX_ARG_I_CONFIG);
            cmd.add(img.getAbsolutePath());
        }
        cmd.add(CliConstants.CODEX_ARG_STDIN);
    }

    private byte[] buildPromptInput(CliSendRequest request) {
        return buildPrompt(request).getBytes(StandardCharsets.UTF_8);
    }

    private String buildPrompt(CliSendRequest request) {
        StringBuilder sb = new StringBuilder(request.message());
        if (request.openedFiles() != null && request.openedFiles().size() > 0) {
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

    private String readSandboxMode(String cwd) {
        return CliSettings.getCodexSandboxMode(cwd);
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private String stableItemId(JsonObject item, String fallback) {
        String id = firstNonBlank(getString(item, "id"), getString(item, "call_id"));
        return id != null ? id : fallback + "-" + fallbackIdSeq.incrementAndGet();
    }

    private static String appendedDelta(String previous, String next) {
        String oldText = previous != null ? previous : "";
        String newText = next != null ? next : "";
        if (newText.isEmpty() || newText.equals(oldText)) {
            return "";
        }
        if (oldText.isEmpty()) {
            return newText;
        }
        if (newText.startsWith(oldText)) {
            return newText.substring(oldText.length());
        }
        return newText;
    }

    private static String extractCommand(JsonObject item) {
        String command = firstNonBlank(
                getString(item, "command"),
                getString(item, "cmd"),
                getString(item, "program")
        );
        return command != null ? command : "(unknown command)";
    }

    private static String extractCommandOutput(JsonObject item) {
        String output = firstNonBlank(
                getString(item, "aggregated_output"),
                getString(item, "output"),
                getString(item, "stdout"),
                getString(item, "stderr"),
                getString(item, "result")
        );
        return output != null ? output : "(no output)";
    }

    private static String extractPatchFilePath(String patchText) {
        if (patchText == null || patchText.isBlank()) {
            return null;
        }
        String[] lines = patchText.split("\\R");
        for (String line : lines) {
            if (line.startsWith("*** Add File:") || line.startsWith("*** Update File:")) {
                String filePath = line.substring(line.indexOf(':') + 1).trim();
                if (!filePath.isBlank()) {
                    return filePath;
                }
            }
        }
        return null;
    }

    private static String extractMcpResult(JsonObject item) {
        if (item.has("error") && item.get("error").isJsonObject()) {
            String message = getString(item.getAsJsonObject("error"), "message");
            if (message != null) {
                return message;
            }
        }
        if (!item.has("result") || item.get("result").isJsonNull()) {
            return "(no output)";
        }
        JsonElement result = item.get("result");
        if (result.isJsonPrimitive()) {
            return result.getAsString();
        }
        if (result.isJsonObject()) {
            JsonObject resultObj = result.getAsJsonObject();
            if (resultObj.has("content") && resultObj.get("content").isJsonArray()) {
                JsonArray content = resultObj.getAsJsonArray("content");
                List<String> parts = new ArrayList<>();
                for (JsonElement element : content) {
                    if (element.isJsonObject()) {
                        JsonObject block = element.getAsJsonObject();
                        if (CommonConstants.BLOCK_TYPE_TEXT.equals(getString(block, "type"))) {
                            String text = getString(block, "text");
                            if (text != null && !text.isBlank()) {
                                parts.add(text);
                            }
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    return String.join("\n", parts);
                }
            }
            if (resultObj.has("structured_content")) {
                return resultObj.get("structured_content").toString();
            }
        }
        return result.toString();
    }

    private static String extractErrorMessage(JsonObject event, String fallback) {
        if (event.has("error")) {
            JsonElement error = event.get("error");
            if (error.isJsonObject()) {
                String message = getString(error.getAsJsonObject(), "message");
                if (message != null) {
                    return message;
                }
            } else if (error.isJsonPrimitive()) {
                return error.getAsString();
            }
        }
        String message = getString(event, "message");
        return message != null ? message : fallback;
    }

    private static boolean isItemError(JsonObject item) {
        String status = getString(item, "status");
        if (status != null && (CliConstants.CODEX_STATUS_FAILED.equalsIgnoreCase(status) || CliConstants.CODEX_STATUS_ERROR.equalsIgnoreCase(status))) {
            return true;
        }
        if (item.has("is_error") && item.get("is_error").isJsonPrimitive() && item.get("is_error").getAsBoolean()) {
            return true;
        }
        if (item.has("error") && !item.get("error").isJsonNull()) {
            return true;
        }
        if (item.has("exit_code") && item.get("exit_code").isJsonPrimitive()) {
            try {
                return item.get("exit_code").getAsInt() != 0;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static String normalizeMcpToolName(String server, String tool) {
        String normalizedServer = server == null || server.isBlank() ? "mcp" : sanitizeToolNamePart(server);
        String normalizedTool = tool == null || tool.isBlank() ? "tool" : sanitizeToolNamePart(tool);
        return "mcp__" + normalizedServer + "__" + normalizedTool;
    }

    private static String sanitizeToolNamePart(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String commandDescription(String command) {
        if (command == null || command.isBlank()) {
            return "Run command";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("git status")) {
            return "Check git status";
        }
        if (trimmed.startsWith("git diff")) {
            return "Inspect git diff";
        }
        if (trimmed.startsWith("ls") || trimmed.contains(" Get-ChildItem")) {
            return "List files";
        }
        return "Run command";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean shouldIgnoreRawLine(String line) {
        if (line == null) {
            return true;
        }
        String trimmed = line.trim();
        return trimmed.isEmpty() || "Reading additional input from stdin...".equals(trimmed);
    }

    private static boolean isLikelyDiagnosticLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        // 纯噪声行（只有 CLI 装饰字符）
        if (CliErrorFormatter.isCliNoiseLine(trimmed)) {
            return true;
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return false;
        }
        if ("Reading additional input from stdin...".equals(trimmed)) {
            return true;
        }
        // 去掉 CLI 前缀后检查错误关键词
        String cleaned = CliErrorFormatter.stripCliPrefix(trimmed);
        if (!cleaned.isEmpty() && CLI_ERROR_KEYWORD_PATTERN.matcher(cleaned).find()) {
            return true;
        }
        if (POWERSHELL_PROFILE_ERROR_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        if (DIAGNOSTIC_HEADER_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        if (DIAGNOSTIC_BODY_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        if (CliConstants.POWERSHELL_DIAGNOSTIC_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
            return true;
        }
        if (trimmed.matches("(?i)^\\S+\\s*:\\s*The term '.+' is not recognized as the name of a cmdlet.*")) {
            return true;
        }
        return DIAGNOSTIC_GENERIC_PATTERN.matcher(trimmed).matches();
    }

    private static String buildExitError(
            int exitCode,
            StringBuilder diagnostic,
            StringBuilder cliError,
            boolean requestHasImages
    ) {
        // cliError 仅包含错误/诊断相关行，优先使用
        CharSequence effectiveDiagnostic = (cliError != null && !cliError.isEmpty())
                ? cliError : diagnostic;
        if (isLikelyUnsupportedImageError(effectiveDiagnostic, requestHasImages)) {
            return UNSUPPORTED_IMAGE_MESSAGE + "\n\nDetails:\n" + effectiveDiagnostic;
        }
        return CliErrorFormatter.formatExitError("Codex", exitCode, effectiveDiagnostic);
    }

    private static String formatCodexError(String rawError, boolean requestHasImages) {
        if (isLikelyUnsupportedImageError(rawError, requestHasImages)) {
            return UNSUPPORTED_IMAGE_MESSAGE + "\n\nDetails:\n" + rawError;
        }
        return CliErrorFormatter.formatError("Codex", rawError);
    }

    private static boolean isLikelyUnsupportedImageError(CharSequence diagnostic, boolean requestHasImages) {
        if (!requestHasImages || diagnostic == null) {
            return false;
        }
        String details = diagnostic.toString();
        if (details.isBlank()) {
            return false;
        }
        if (RATE_LIMIT_OR_QUOTA_PATTERN.matcher(details).find()) {
            return false;
        }
        return UNSUPPORTED_IMAGE_CONTEXT_PATTERN.matcher(details).find()
                && UNSUPPORTED_IMAGE_REASON_PATTERN.matcher(details).find();
    }

    /**
     * 处理一行原始字节数据：解码为字符串后交给 parseEvent。
     * Windows 上 Node.js 管道输出是 UTF-8，但 cmd.exe 错误信息可能是 GBK 编码。
     */
    private void processLine(
            ByteArrayOutputStream lineBuf,
            StringBuilder diagnostic,
            CliSessionCallback callback,
            StringBuilder assistantContent,
            StringBuilder cliError
    ) {
        byte[] bytes = lineBuf.toByteArray();
        lineBuf.reset();
        // 去掉尾部 \r
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len--;
        }
        if (len == 0) {
            return;
        }

        String line = decodeLine(bytes, len);
        if (!line.isBlank()) {
            CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
            parseEvent(line, callback, assistantContent, cliError);
        }
    }

    /**
     * 先尝试 UTF-8 解码，如果遇到无效字节序列则回退到平台默认编码。
     * 这样可以同时正确处理 Node.js 的 UTF-8 JSON 输出和 cmd.exe 的 GBK 错误信息。
     */
    private static String decodeLine(byte[] bytes, int len) {
        CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer cb = utf8Decoder.decode(ByteBuffer.wrap(bytes, 0, len));
            return cb.toString();
        } catch (CharacterCodingException e) {
            // UTF-8 解码失败，优先按 Windows 中文控制台编码解析；IDE/JVM 可能强制 defaultCharset=UTF-8。
            Charset fallback = Charset.defaultCharset();
            if (WINDOWS_CHINESE_CHARSET.equals(fallback)) {
                return new String(bytes, 0, len, fallback);
            }
            String decoded = new String(bytes, 0, len, WINDOWS_CHINESE_CHARSET);
            if (!decoded.contains("\uFFFD")) {
                return decoded;
            }
            return new String(bytes, 0, len, fallback);
        }
    }

    private static void cleanupTempFiles(List<File> files) {
        for (File f : files) {
            try {
                if (f != null && f.exists()) {
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
