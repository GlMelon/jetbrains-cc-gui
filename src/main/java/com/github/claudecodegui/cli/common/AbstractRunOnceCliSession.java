package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.github.claudecodegui.util.CliTempDir;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.service.lifecycle.LifecycleEventType;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.service.lifecycle.LifecycleProcessKind;
import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次性 CLI 会话模板基类(grok/kimi/pi/opencode 共用)。
 * <p>
 * 合并自原 GrokCliSession/KimiCliSession/PiCliSession 三胞胎(归一化后仅 ProviderType 一行之差)
 * 与 OpenCodeCliSession(真实差异仅 3 处,均经钩子保留)。会话骨架:每轮 send spawn
 * {@code <cli> run "<msg>" --format json} 一次性子进程,从事件流提取 sessionID 并以
 * {@code -s} 续接实现多轮对话(设计 §7)。
 * <p>
 * 实现要点(对应设计 §7.1-7.5 / B1,B9,B13,B14,B15):
 * <ul>
 *   <li>B1:不预创建 session。{@code <cli> run} 隐式创建会话,sessionID 从事件流提取。</li>
 *   <li>B9:消息作为位置参数传入,不再双写 stdin(消除 createSession/prompt 双写)。</li>
 *   <li>B13:续接(-s)失败时(session 失效),清空 sessionId 重试一次首轮流程。</li>
 *   <li>B14:进程经 {@link CliProcessHandle} 管理,interrupt 走 PlatformUtils.terminateProcess(替代裸 destroyForcibly)。</li>
 *   <li>B15:能力透传(model/-m、reasoningEffort/--variant、图片附件/-f、permissionMode bypass→
 *       --auto、cwd/--dir;opencode 另有 --thinking)。</li>
 * </ul>
 * 子类职责:
 * <ul>
 *   <li>必须:{@link #createParser(CliSessionCallback)} 绑定协议解析器
 *       (opencode 用 {@code OpenCodeCliStreamParser},grok/kimi/pi 各绑定自有方言解析器,
 *       并按需覆写 {@code buildRunCommand} 为原生 CLI 参数布局);</li>
 *   <li>可选:覆写 {@link #dispatchLine}(行分流,默认 marker 版)、
 *       {@link #buildRunCommand}(命令布局,默认 opencode 版)、
 *       {@link #appendExtraRunFlags}(能力 flag 追加)、{@link #npmDir()}(npm 包目录)、
 *       {@link #onStartAuxiliary}/{@link #onStopAuxiliary}(辅助监视器,grok 工具尾随用)。</li>
 * </ul>
 */
public abstract class AbstractRunOnceCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(AbstractRunOnceCliSession.class);
    private static final Charset WINDOWS_CHINESE_CHARSET = Charset.forName("GBK");

    protected final String tabId;
    protected final ProviderType providerType;
    private final Gson gson = GsonHolder.GSON;
    private final CliAttachmentHandler attachmentHandler = new CliAttachmentHandler();
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;

    // 当前 session ID(从事件流提取,续接时以 -s 传入)
    private volatile String sessionId;
    // 当前活跃进程(用于中断)
    private volatile CliProcessHandle activeHandle;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    // CLI 可执行解析器(懒初始化,npmDir 钩子依赖子类状态故不在构造期创建)
    private volatile ProviderCliResolver resolver;

    protected AbstractRunOnceCliSession(ProviderType providerType, String tabId, McpGatewayService gatewayService) {
        this(providerType, tabId, gatewayService, null);
    }

    protected AbstractRunOnceCliSession(ProviderType providerType, String tabId,
                                        McpGatewayService gatewayService,
                                        LifecycleObservabilityService lifecycleService) {
        this.providerType = providerType;
        this.tabId = tabId;
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
    }

    // ── 钩子 ──────────────────────────────────────────────────────────────────

    /** 创建本次运行的协议解析器(marker / NDJSON 由子类决定)。 */
    protected abstract CliStreamParser createParser(CliSessionCallback callback);

    /** provider 显示名(日志、错误格式化、进程名),默认取 {@link ProviderType#displayLabel()}。 */
    protected String providerLabel() {
        return providerType.displayLabel();
    }

    /** npm 全局结构下的包目录名,默认取裸名(grok/kimi/pi);opencode 为 "opencode-ai" 须覆写。 */
    protected String npmDir() {
        return providerType.cliCommand();
    }

    /**
     * 事件行分流(在行缓冲/解码之后调用)。默认 marker 协议:所有行交解析器,
     * 非 {@code [} 行(启动 banner / 错误噪声)额外收集到 diagnostic。
     * NDJSON 协议(opencode)覆写:非 {@code {} 行发 MCP 降级提示 + 收集 diagnostic 且不进解析器。
     */
    protected void dispatchLine(String line, CliStreamParser parser, StringBuilder diagnostic) {
        parser.parseLine(line);
        if (!line.trim().startsWith("[")) {
            CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
        }
    }

    /** 能力 flag 追加点(variant 之后、附件之前)。默认空;opencode 覆写加 --thinking。 */
    protected void appendExtraRunFlags(CliSendRequest request, List<String> cmd) {
        // 默认无额外 flag
    }

    /**
     * 辅助监视器启动钩子:进程已就绪且 stdout drain 开启前调用一次。默认空;
     * grok 覆写以尾随 chat_history.jsonl 工具事件(stdout 无工具事件,见 GrokToolHistoryTailer)。
     */
    protected void onStartAuxiliary(Process process, CliStreamParser parser) {
        // 默认无辅助监视器
    }

    /**
     * 辅助监视器停止钩子:await 返回后、结果判定(timeout/interrupt/成功补发流结束)前调用,
     * 保证尾部信号先于流结束判定进入解析器。实现必须幂等(异常路径可能未 start)。
     */
    protected void onStopAuxiliary() {
        // 默认无辅助监视器
    }

    // ── 会话骨架(全部公共) ─────────────────────────────────────────────────────

    @Override
    public com.github.claudecodegui.session.SessionNegotiatedCapabilities capabilities() {
        return com.github.claudecodegui.session.SessionNegotiatedCapabilities.cli(true, true, false);
    }

    @Override
    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        userInterrupted.set(false);
        return CliSessionExecutor.runAsync(() -> {
            long sendStartNanos = System.nanoTime();
            List<File> tempFiles = new ArrayList<>();
            StringBuilder diagnostic = new StringBuilder();
            try {
                LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] send task started"
                        + ": tabId=" + tabId
                        + ", sessionId=" + (sessionId != null ? sessionId : "(none)")
                        + ", cwd=" + (request.cwd() != null ? request.cwd() : "(none)")
                        + ", thread=" + Thread.currentThread().getName());
                // 图片附件物化为磁盘文件(跨重试复用同一批临时文件,finally 统一清理)
                List<File> attachFiles;
                try {
                    long attachmentsStartNanos = System.nanoTime();
                    attachFiles = attachmentHandler.processForCodex(request.attachments(), tempFiles);
                    LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] attachments prepared"
                            + ": tabId=" + tabId
                            + ", files=" + attachFiles.size()
                            + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                            + ", attachmentsMs=" + elapsedMillis(attachmentsStartNanos)
                            + ", thread=" + Thread.currentThread().getName());
                } catch (Exception e) {
                    LOG.warn("[" + sessionTag() + "][" + tabId + "] process attachments failed", e);
                    attachFiles = List.of();
                }

                // 续接 id 与 claude/codex(--resume 语义)及 kimi ACP 通道对齐:优先 request.sessionId()
                // (前端/state 持有的会话 id,历史回load、插件重启后仍指向用户认可的会话),
                // 实例字段(同 tab 连续聊天回写/首轮提取)仅作兜底。此前只看实例字段:
                // 历史点击/重启后新实例预分配新 id,用户会话被静默另起炉灶。
                String requestedSessionId = request.sessionId();
                String effectiveSessionId = requestedSessionId != null && !requestedSessionId.isBlank()
                        ? requestedSessionId.trim() : sessionId;
                // B13:续接失败时清空 sessionId 重试一次首轮流程(设计 §7.4)。
                boolean retry = runOnce(request, callback, effectiveSessionId, attachFiles, diagnostic, sendStartNanos);
                if (retry) {
                    LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] continuation session invalidated; retrying as fresh turn"
                            + ": tabId=" + tabId
                            + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                            + ", thread=" + Thread.currentThread().getName());
                    sessionId = null;
                    diagnostic.setLength(0);
                    recordLifecycle(LifecycleEventType.FALLBACK, null, request, null,
                            "continuation session invalidated; retrying as fresh one-shot turn");
                    if (!userInterrupted.get()) {
                        runOnce(request, callback, null, attachFiles, diagnostic, sendStartNanos);
                    }
                }
            } catch (Exception | LinkageError e) {
                // LinkageError(如 NoClassDefFoundError)同样按 turn 失败收尾:静态初始化
                // 失败的类(见 CliCompatibilityService.getInstance 防御)抛出的是 Error,
                // 仅 catch Exception 会静默穿透,前端流式 footer 永久悬挂、无任何日志。
                LOG.warn("[" + sessionTag() + "][" + tabId + "] send failed", e);
                if (wasInterrupted()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    String err = CliErrorFormatter.formatError(providerLabel(), e.getMessage());
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
     * 执行一次 {@code <cli> run}。返回 true 表示发生了续接 session 失效、应作为首轮重试
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
        CliStreamParser parser = createParser(callback);
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.MCP_SYNCING.value());
        McpGatewayCliConfig gatewayConfig = buildGatewayConfig(request);
        callback.onMessage(CliConstants.MSG_RESPONSE_PHASE, AssistantResponsePhase.CONNECTING.value());
        String effectiveSessionDisplay = effectiveSessionId != null ? effectiveSessionId : "(new)";
        LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] building command"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        List<String> cmd = buildRunCommand(request, effectiveSessionId, attachFiles);
        LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] command prepared"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        LOG.info("[" + sessionTag() + "][" + tabId + "] Command: " + String.join(" ", cmd));

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
                LOG.warn("[" + sessionTag() + "][" + tabId + "] CWD does not exist, falling back to home: " + request.cwd());
                File homeDir = new File(PlatformUtils.getHomeDirectory());
                if (homeDir.isDirectory()) {
                    pb.directory(homeDir);
                }
            }
        }

        // 可靠 stdin EOF:redirectInput 重定向到空设备(NUL / /dev/null),在 OS 句柄层给子进程
        // 空 stdin(立即 EOF)。经 .cmd → cmd.exe 包装时管道 close 的 EOF 传播不可靠
        // (stdin 打开=挂起 exit0 空;redirectInput=快速返回事件流,对照实验验证)。
        pb.redirectInput(stdinNullSink());

        LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] starting process"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        Process process = pb.start();
        ProcessManager processManager = NodeService.getInstance().getProcessManager();
        String processToken = processManager.registerAuxiliaryProcess(process);
        if (processToken == null) {
            throw new IllegalStateException("Project is closing; one-shot CLI process was rejected");
        }
        Long processGeneration = lifecycleService != null
                ? lifecycleService.nextProcessGeneration() : null;
        recordLifecycle(LifecycleEventType.SPAWN, process, request, processGeneration, "one-shot CLI spawned");
        recordLifecycle(LifecycleEventType.STDIN_CLOSE, process, request, processGeneration,
                "one-shot stdin redirected to EOF");
        LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] process started"
                + ": tabId=" + tabId
                + ", effectiveSessionId=" + effectiveSessionDisplay
                + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                + ", thread=" + Thread.currentThread().getName());
        CliProcessHandle currentHandle = new CliProcessHandle(process, providerType.cliCommand() + "-tab-" + tabId);
        activeHandle = currentHandle;

        try {
            onStartAuxiliary(process, parser);
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
                                    LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] first stdout line buffer reached"
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
                recordLifecycle(LifecycleEventType.STDOUT_EOF, process, request, processGeneration,
                        "one-shot stdout EOF");
            });

            CliProcessLifecycle.Outcome outcome = CliProcessLifecycle.await(process, outputDrain);
            int exitCode = outcome.exitCode();
            recordLifecycle(LifecycleEventType.EXIT, process, request, processGeneration,
                    "one-shot CLI exited: " + exitCode);
            LOG.info("[CliConcurrencyDiag][" + sessionTag() + "] process exited"
                    + ": tabId=" + tabId
                    + ", effectiveSessionId=" + effectiveSessionDisplay
                    + ", exitCode=" + exitCode
                    + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                    + ", thread=" + Thread.currentThread().getName());
            // 停辅助监视器并做最终信号 drain(须先于流结束判定:尾部 tool_result 应在 stream_end 之前进入)
            onStopAuxiliary();

            if (outcome.timedOut() && !wasInterrupted()) {
                String err = CliErrorFormatter.formatError(providerLabel(), "CLI request timed out");
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
                        String cliName = providerType.cliCommand();
                        String err = CliErrorFormatter.formatError(providerLabel(),
                                "进程退出但未返回任何内容(exit=0,无事件流)。"
                                        + "常见原因:子进程阻塞读 stdin,或 " + cliName + "/provider 内部错误。"
                                        + "请在命令行执行 " + cliName + " run 并重定向空 stdin 对照验证,检查 " + cliName + " 配置与 provider。");
                        callback.onError(err);
                        callback.onComplete(false, parser.accumulatedText(), err);
                        return false;
                    }
                    // 异常路径未收到流结束标记但有事件/内容,补发流结束以解除前端阻塞
                    callback.onMessage(CliConstants.MSG_STREAM_END, "");
                    callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
                }
                callback.onComplete(true, parser.accumulatedText(), null);
                return false;
            }

            // 错误路径:优先取解析器收集的 error 诊断,回退到非协议噪声行
            String errDiag = parser.errorDiagnostic();
            if (errDiag == null || errDiag.isEmpty()) {
                errDiag = diagnostic.toString();
            }

            // B13:仅续接(-s)场景检测 session 失效 → 重试首轮
            if (effectiveSessionId != null && looksLikeSessionInvalidation(errDiag)) {
                return true;
            }

            String err = errDiag.isBlank()
                    ? CliErrorFormatter.formatExitError(providerLabel(), exitCode, diagnostic)
                    : CliErrorFormatter.formatError(providerLabel(), errDiag);
            callback.onError(err);
            callback.onComplete(false, parser.accumulatedText(), err);
            return false;
        } finally {
            if (process.isAlive()) {
                recordLifecycle(LifecycleEventType.TERMINATE, process, request, processGeneration,
                        "one-shot process terminated");
            }
            CliProcessLifecycle.terminate(process);
            processManager.unregisterAuxiliaryProcess(processToken, process);
            if (activeHandle == currentHandle) {
                activeHandle = null;
            }
        }
    }

    private void recordLifecycle(LifecycleEventType type, Process process, CliSendRequest request,
                                 Long processGeneration, String detail) {
        if (lifecycleService == null) {
            return;
        }
        lifecycleService.record(type,
                lifecycleService.metadata(LifecycleProcessKind.CLI_ONE_SHOT,
                        request != null ? request.runtimeSessionEpoch() : null,
                        request != null ? request.responseTurnEpoch() : null,
                        processGeneration),
                process != null ? process.pid() : -1L, detail);
    }

    @Override
    public void interrupt() {
        userInterrupted.set(true);
        CliProcessHandle h = activeHandle;
        if (h != null) {
            long startNanos = System.nanoTime();
            h.interrupt();
            LOG.info("[TabPerf] " + sessionTag() + ".interrupt returned in "
                    + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
        }
    }

    @Override
    public void dispose() {
        interrupt();
    }

    // ── 内部支撑 ──────────────────────────────────────────────────────────────

    private String sessionTag() {
        return providerLabel() + "CliSession";
    }

    private boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }

    private McpGatewayCliConfig buildGatewayConfig(CliSendRequest request) {
        if (gatewayService == null) {
            return McpGatewayCliConfig.disabled("No MCP Gateway service");
        }
        return gatewayService.buildCliConfig(providerType, tabId, request.cwd());
    }

    /** CLI 可执行解析器(含 npm 全局结构原生 exe 解析/版本门控),方言子类共用。 */
    protected ProviderCliResolver resolver() {
        ProviderCliResolver r = resolver;
        if (r == null) {
            synchronized (this) {
                if (resolver == null) {
                    resolver = new ProviderCliResolver(providerType, npmDir());
                }
                r = resolver;
            }
        }
        return r;
    }

    // ── command builder ──────────────────────────────────────────────────────

    /**
     * 构造 {@code <cli> run "<msg>" --format json [-s sessionId] [能力 flags]}。
     * 消息作为 run 之后的首个位置参数(B9:消除旧实现的 stdin 双写)。
     */
    public List<String> buildRunCommand(CliSendRequest request, String effectiveSessionId, List<File> attachFiles) {
        String executable = resolver().findExecutable();
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
        String variant = mapReasoningVariant(request.reasoningEffort());
        if (variant != null) {
            cmd.add(CliConstants.OPENCODE_ARG_VARIANT);
            cmd.add(variant);
        }
        appendExtraRunFlags(request, cmd);
        if (attachFiles != null) {
            for (File f : attachFiles) {
                if (f != null) {
                    cmd.add(CliConstants.OPENCODE_ARG_FILE);
                    cmd.add(f.getAbsolutePath());
                }
            }
        }
        if (CommonConstants.PERMISSION_MODE_BYPASS.equals(request.permissionMode())) {
            // bypass 等价物:--auto 自动批准未被 deny 的请求(opencode 官方语义,grok/kimi/pi 同构)。
            cmd.add(CliConstants.OPENCODE_ARG_AUTO);
        }
        if (request.cwd() != null && !request.cwd().isBlank()) {
            cmd.add(CliConstants.OPENCODE_ARG_DIR);
            cmd.add(request.cwd());
        }
        return cmd;
    }

    /**
     * reasoningEffort → --variant(设计 §7.2,四 provider 一致):
     * low→minimal, medium→省略(默认), high→high, xhigh/max→max。
     */
    static String mapReasoningVariant(String reasoningEffort) {
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

    /**
     * prompt 位置参数文本组合(消息 + 打开文件 + @文件引用 + agent 角色),
     * 由各方言 {@code buildRunCommand} 复用(opencode 默认布局与 grok/kimi/pi 方言共用)。
     */
    protected String buildPromptText(CliSendRequest request) {
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
     * argv 选项注入防御:位置参数文本以 {@code -} 或 {@code @} 开头时前置空格(对称 ai-bridge
     * safePromptArg),防止用户消息被 CLI 解析为 flag 或文件参数。
     * <p>前导 {@code @} 与 {@code -} 同源防御:pi/omp 的 parseArgs 把任何以 {@code @} 开头的
     * argv token 归类为文件参数(fileArgs → processFileArguments → "Error: File not found" + exit 1);
     * 前置空格使 token 留在 messages 中,mention 仍可解析(omp 的 mention 正则允许 @ 前空白)。
     */
    protected static String safePromptArg(String text) {
        if (text != null && (text.startsWith("-") || text.startsWith("@"))) {
            return " " + text;
        }
        return text == null ? "" : text;
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

    private void processLine(CliOutputLimits.LineBuffer lineBuf, CliStreamParser parser, StringBuilder diagnostic) {
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
        dispatchLine(line, parser, diagnostic);
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
        } catch (java.nio.charset.CharacterCodingException e) {
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
     * 供 {@link ProcessBuilder#redirectInput} 在 OS 句柄层给子进程空 stdin(立即 EOF)。
     */
    private static File stdinNullSink() {
        return new File(PlatformUtils.isWindows() ? "NUL" : "/dev/null");
    }

    /**
     * 判定是否"静默空失败":进程 exit0,但整轮未解析到任何有效事件
     * ({@code !receivedAnyEvent},即无 sessionID / 文本 / 流结束标记)。
     * 此组合表明 CLI 未产出事件流(典型:阻塞读 stdin / 内部静默错误),
     * 应上报错误而非静默空完成。
     */
    public static boolean isSilentEmptyFailure(CliStreamParser parser) {
        return !parser.receivedAnyEvent();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
