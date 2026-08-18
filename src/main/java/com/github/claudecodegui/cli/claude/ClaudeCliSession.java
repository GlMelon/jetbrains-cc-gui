package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.*;
import com.github.claudecodegui.cli.compatibility.CliCompatibilityDecision;
import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.history.ClaudeSessionEntrypointRewriter;
import com.github.claudecodegui.handler.history.SessionEntrypoint;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.provider.claude.ClaudeCliDetector;
import com.github.claudecodegui.provider.claude.ClaudeCliStreamParser;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.ui.toolwindow.TabPerformanceLogger;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Claude CLI 会话：每个 Tab 独立实例，使用 one-shot 模式（每轮消息启动独立进程）。
 * 通过 --resume 实现多轮连续对话，完全兼容 Windows。
 */
public class ClaudeCliSession implements CliSession {

    private static final Logger LOG = Logger.getInstance(ClaudeCliSession.class);

    private final String tabId;
    private final Gson gson = GsonHolder.GSON;
    private final CliAttachmentHandler attachmentHandler = new CliAttachmentHandler();
    private final CliMcpConfig mcpConfig;
    private final McpGatewayService gatewayService;
    private final ClaudeSessionEntrypointRewriter entrypointRewriter;
    private volatile String permissionDir;
    private volatile String cliPermissionSessionId;

    // 当前 session_id（从 stream-json 输出中获取）
    private volatile String sessionId;
    // readOutput 收到 result 事件后置位,避免 exitCode!=0 时重复发 onError/onComplete。
    private volatile boolean resultEmitted;
    // MCP 连接失败降级提示去重(每回合一次,prepareForSend 重置)。
    private volatile boolean mcpNoticeEmitted;
    // 当前活跃进程（用于中断）
    private volatile CliProcessHandle activeHandle;
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);

    // ── 长驻会话(设计文档 §3/§4):registry 为 null 时永远走 one-shot(自然降级) ──
    private static final String PROVIDER_CLAUDE = "claude";
    private final CliPersistentProcessRegistry registry;
    private final ClaudePersistentSendPath persistentSendPath;
    // 当前活跃长驻进程(用于 interrupt 分派 interruptTurn;轮结束后置空)
    private volatile CliPersistentProcess activePersistentProcess;

    public ClaudeCliSession(String tabId) {
        this(tabId, null, new ClaudeSessionEntrypointRewriter(), null);
    }

    public ClaudeCliSession(String tabId, McpGatewayService gatewayService) {
        this(tabId, gatewayService, new ClaudeSessionEntrypointRewriter(), null);
    }

    ClaudeCliSession(
            String tabId,
            McpGatewayService gatewayService,
            ClaudeSessionEntrypointRewriter entrypointRewriter
    ) {
        this(tabId, gatewayService, entrypointRewriter, null);
    }

    ClaudeCliSession(
            String tabId,
            McpGatewayService gatewayService,
            ClaudeSessionEntrypointRewriter entrypointRewriter,
            CliPersistentProcessRegistry registry
    ) {
        this.tabId = tabId;
        this.mcpConfig = new CliMcpConfig(tabId);
        this.gatewayService = gatewayService;
        this.entrypointRewriter = entrypointRewriter;
        this.registry = registry;
        this.persistentSendPath = new ClaudePersistentSendPath(this);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public void interrupt() {
        userInterrupted.set(true);
        // 长驻轮进行中:进程保留式中断(§4.3),写 interrupt control_request 即返回
        CliPersistentProcess persistent = activePersistentProcess;
        if (persistent != null) {
            long startNanos = System.nanoTime();
            persistent.interruptTurn();
            LOG.info("[TabPerf] ClaudeCliSession.interrupt (persistent) returned in " + TabPerformanceLogger.elapsedMillis(
                    startNanos) + "ms: tab=" + tabId);
            return;
        }
        CliProcessHandle h = activeHandle;
        if (h != null) {
            long startNanos = System.nanoTime();
            h.interrupt();
            LOG.info("[TabPerf] ClaudeCliSession.interrupt returned in " + TabPerformanceLogger.elapsedMillis(
                    startNanos) + "ms: tab=" + tabId);
        } else {
            LOG.info("[ClaudeCliSession] Interrupt requested before active process handle was available: tab=" + tabId);
        }
    }

    public void dispose() {
        long startNanos = System.nanoTime();
        interrupt();
        // 长驻槽位释放:tab 关闭 → 关闭并移除该 tab 的长驻进程(§3.2)
        CliPersistentProcessRegistry persistentRegistry = registry;
        if (persistentRegistry != null) {
            persistentRegistry.release(tabId, PROVIDER_CLAUDE);
        }
        long cleanupStartNanos = System.nanoTime();
        mcpConfig.cleanup();
        LOG.info("[TabPerf] ClaudeCliSession MCP cleanup returned in " + TabPerformanceLogger.elapsedMillis(
                cleanupStartNanos) + "ms: tab=" + tabId);
        LOG.info("[TabPerf] ClaudeCliSession.dispose returned in " + TabPerformanceLogger.elapsedMillis(startNanos) + "ms: tab=" + tabId);
    }

    public String getSessionId() {
        return sessionId;
    }

    // ── output reading ───────────────────────────────────────────────────────

    private static String previewLine(String line) {
        if (line == null) {
            return "";
        }
        String compact = line.replace('\n', ' ')
                .replace('\r', ' ');
        return compact.length() > 240 ? compact.substring(0, 240) + "..." : compact;
    }

    private static String previewPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        String compact = prompt.replace('\n', ' ')
                .replace('\r', ' ');
        return compact.length() > 500 ? compact.substring(0, 500) + "..." : compact;
    }

    private static String buildExitError(int exitCode, StringBuilder diagnostic) {
        return CliErrorFormatter.formatExitError("Claude", exitCode, diagnostic);
    }

    static boolean isResultLine(Gson gson, String line) {
        try {
            JsonObject obj = gson.fromJson(line, JsonObject.class);
            return obj != null && CliConstants.MSG_RESULT.equals(getString(obj, "type"));
        } catch (Exception e) {
            return false;
        }
    }

    // ── command builder ──────────────────────────────────────────────────────

    private List<String> buildCommand(String cliPath, CliSendRequest request, String prompt, List<String> addDirs,
                                      McpGatewayCliConfig gatewayConfig) {
        ClaudeCliModelResolver.ResolvedModel profile = ClaudeCliModelResolver.resolveProfile(
                request.model()
        );
        boolean useGateway = gatewayConfig != null && gatewayConfig.usable();
        return buildCommand(
                cliPath,
                request,
                addDirs,
                profile,
                useGateway || mcpConfig.hasServers(),
                useGateway ? gatewayConfig.configPath().toAbsolutePath().toString() : mcpConfig.getConfigFilePath(),
                sessionId
        );
    }

    static List<String> buildCommand(
            String cliPath,
            CliSendRequest request,
            List<String> addDirs,
            ClaudeCliModelResolver.ResolvedModel profile,
            boolean hasMcpServers,
            String mcpConfigFilePath,
            String currentSessionId
    ) {
        return buildCommand(cliPath, request, addDirs, profile, hasMcpServers,
                mcpConfigFilePath, currentSessionId, false);
    }

    /**
     * 命令构建完整版。{@code streamJsonInput}=true 时额外加 {@code --input-format stream-json},
     * 供长驻模式使用(设计文档 §4.1:stdin 走 stream-json user 消息行,保留 -p)。
     */
    static List<String> buildCommand(
            String cliPath,
            CliSendRequest request,
            List<String> addDirs,
            ClaudeCliModelResolver.ResolvedModel profile,
            boolean hasMcpServers,
            String mcpConfigFilePath,
            String currentSessionId,
            boolean streamJsonInput
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliPath);
        cmd.add(CliConstants.ARG_P);
        if (streamJsonInput) {
            cmd.add(CliConstants.ARG_INPUT_FORMAT);
            cmd.add(CliConstants.ARG_STREAM_JSON);
        }
        cmd.add(CliConstants.ARG_OUTPUT_FORMAT);
        cmd.add(CliConstants.ARG_STREAM_JSON);
        cmd.add(CliConstants.ARG_VERBOSE);
        if (profile.capabilities().supportsPartialMessages()) {
            cmd.add(CliConstants.ARG_INCLUDE_PARTIAL);
        }

        ClaudeCliPermissionMode.apply(cmd, request.permissionMode());

        String model = profile.model();
        if (model != null && !model.isBlank()) {
            cmd.add(CliConstants.ARG_MODEL);
            cmd.add(model);
        }

        if (profile.capabilities().supportsEffort()
                && request.reasoningEffort() != null && !request.reasoningEffort()
                .isBlank()) {
            cmd.add(CliConstants.ARG_EFFORT);
            cmd.add(request.reasoningEffort());
        }

        // per-tab MCP 配置
        if (profile.capabilities().supportsMcp() && hasMcpServers) {
            cmd.add(CliConstants.ARG_MCP_CONFIG);
            cmd.add(mcpConfigFilePath);
        }

        // 附件父目录授权，使 Claude 可以读取持久化目录下的图片
        if (profile.capabilities().supportsAddDir() && addDirs != null) {
            for (String dir : addDirs) {
                cmd.add(CliConstants.ARG_ADD_DIR);
                cmd.add(dir);
            }
        }

        // 续接已有会话（优先使用本地保存的 sessionId，其次用请求传入的）
        String resumeId = currentSessionId != null ? currentSessionId : request.sessionId();
        if (resumeId != null && !resumeId.isBlank()) {
            cmd.add(CliConstants.ARG_RESUME);
            cmd.add(resumeId);
        }

        return cmd;
    }

    private static void writePromptToStdin(Process process, String prompt) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(prompt != null ? prompt : "");
            writer.flush();
        }
    }

    private String buildPrompt(CliSendRequest request, List<CliAttachmentHandler.ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder(request.message() != null ? request.message() : "");

        // 附件：图片保留 [Image #N: path] 历史锚点，并显式提示 Claude CLI 用 Read 读取真实文件。
        int imageIndex = 0;
        for (CliAttachmentHandler.ContentBlock block : blocks) {
            if (block.kind() == CliAttachmentHandler.ContentBlock.Kind.IMAGE) {
                imageIndex++;
                String path = block.file()
                        .getAbsolutePath()
                        .replace('\\', '/');
                sb.append("\n\n[Image #")
                        .append(imageIndex)
                        .append(": ")
                        .append(path)
                        .append("]\n")
                        .append("Use the Read tool to inspect this image file, ")
                        .append("then answer using its visible content: ")
                        .append(path);
            } else if (block.text() != null) {
                sb.append("\n\n")
                        .append(block.text());
            }
        }

        if (request.openedFiles() != null && request.openedFiles()
                .size() > 0) {
            sb.append(CliConstants.PROMPT_OPENED_FILES)
                    .append(gson.toJson(request.openedFiles()));
        }
        if (request.fileTagPaths() != null && !request.fileTagPaths()
                .isEmpty()) {
            sb.append(CliConstants.PROMPT_REFERENCED);
            for (String p : request.fileTagPaths()) {
                sb.append("- ")
                        .append(p)
                        .append('\n');
            }
        }
        if (request.agentPrompt() != null && !request.agentPrompt()
                .isBlank()) {
            sb.append(CliConstants.PROMPT_AGENT_ROLE)
                    .append(request.agentPrompt());
        }
        return sb.toString();
    }

    /**
     * 收集图片附件所在的父目录（去重），用于 --add-dir 授权。
     */
    private List<String> collectAddDirs(List<CliAttachmentHandler.ContentBlock> blocks) {
        Set<String> dirs = new LinkedHashSet<>();
        for (CliAttachmentHandler.ContentBlock block : blocks) {
            if (block.kind() != CliAttachmentHandler.ContentBlock.Kind.IMAGE || block.file() == null) {
                continue;
            }
            File parent = block.file()
                    .getParentFile();
            if (parent != null && parent.isDirectory()) {
                dirs.add(parent.getAbsolutePath());
            }
        }
        return new ArrayList<>(dirs);
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

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key)
                .isJsonNull()) {
            return null;
        }
        return obj.get(key)
                .getAsString();
    }

    public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
        prepareForSend();
        return CliSessionExecutor.runAsync(() -> {
            long sendStartNanos = System.nanoTime();
            List<File> tempFiles = new ArrayList<>();
            try {
                LOG.info(String.format(
                        "[CliConcurrencyDiag][ClaudeCliSession] send task started: tabId=%s, requestSessionId=%s, currentSessionId=%s, cwd=%s, thread=%s",
                        tabId,
                        request.sessionId() != null ? request.sessionId() : "(new)",
                        sessionId != null ? sessionId : "(none)",
                        request.cwd() != null ? request.cwd() : "(none)",
                        Thread.currentThread().getName()));
                LOG.info("[CliConcurrencyDiag][ClaudeCliSession] resolving executable" + ": tabId=" + tabId + ", elapsedMs=" + elapsedMillis(
                        sendStartNanos) + ", thread=" + Thread.currentThread().getName());
                long cliResolveStartNanos = System.nanoTime();
                String cliPath = ClaudeCliDetector.getInstance()
                        .findCliExecutable();
                LOG.info("[CliConcurrencyDiag][ClaudeCliSession] executable resolved" + ": tabId=" + tabId + ", path=" + cliPath + ", elapsedMs=" + elapsedMillis(
                        sendStartNanos) + ", resolveMs=" + elapsedMillis(cliResolveStartNanos) + ", thread=" + Thread.currentThread().getName());
                if (cliPath == null) {
                    throw new IllegalStateException("Claude CLI not found");
                }

                // 解析附件:图片落盘以供 prompt 引用,文档读为文本
                String sessionKey = sessionId != null ? sessionId : "epoch-" + tabId;
                long attachmentsStartNanos = System.nanoTime();
                List<CliAttachmentHandler.ContentBlock> blocks = attachmentHandler.processForClaude(request.provider(), sessionKey,
                                                                                                    request.attachments(), tempFiles);
                LOG.info("[CliConcurrencyDiag][ClaudeCliSession] attachments prepared" + ": tabId=" + tabId + ", blocks=" + blocks.size() + ", elapsedMs=" + elapsedMillis(
                        sendStartNanos) + ", attachmentsMs=" + elapsedMillis(attachmentsStartNanos) + ", thread=" + Thread.currentThread().getName());

                String prompt = buildPrompt(request, blocks);
                List<String> addDirs = collectAddDirs(blocks);
                int imageBlockCount = 0;
                for (CliAttachmentHandler.ContentBlock block : blocks) {
                    if (block.kind() == CliAttachmentHandler.ContentBlock.Kind.IMAGE) {
                        imageBlockCount++;
                    }
                }
                LOG.debug(String.format(
                        "[ClaudeImageDiag][ClaudeCliSession] prompt prepared: tabId=%s, reqAtts=%d, blocks=%d, imgBlocks=%d, addDirs=%s, stdin=true, hasReadInstr=%s, preview=%s",
                        tabId,
                        request.attachments() != null ? request.attachments().size() : 0,
                        blocks.size(), imageBlockCount, addDirs,
                        prompt.contains(CliConstants.PROMPT_READ_IMAGE),
                        previewPrompt(prompt)));

                McpGatewayCliConfig gatewayConfig = buildGatewayConfig(request);

                // 长驻优先(设计文档 §3.2):命中/首建则本轮经长驻进程完成,失败静默降级 one-shot
                if (trySendPersistent(request, callback, cliPath, blocks, prompt, addDirs, gatewayConfig, sendStartNanos)) {
                    return;
                }

                sendOneShot(request, callback, cliPath, prompt, addDirs, gatewayConfig, sendStartNanos);
            } catch (Exception e) {
                LOG.warn("[ClaudeCliSession][" + tabId + "] send failed", e);
                if (userInterrupted.get()) {
                    callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
                } else {
                    callback.onError(e.getMessage());
                    callback.onComplete(false, null, e.getMessage());
                }
            } finally {
                cleanupTempFiles(tempFiles);
                userInterrupted.set(false);
            }
        });
    }

    /**
     * 长驻发送路径(设计文档 §3.2 静默加载策略)。返回 true 表示本轮已由长驻路径收尾
     * (成功/中断/报错皆算);返回 false 表示未使用长驻且消息尚未递交 CLI(门禁关/版本
     * 不兼容/未命中/startTurn 即时失败),上层继续走 one-shot,当前消息不受影响。
     * 已递交后的轮失败一律报错收尾不重发(transcript 交错防护)。
     */
    private boolean trySendPersistent(
            CliSendRequest request,
            CliSessionCallback callback,
            String cliPath,
            List<CliAttachmentHandler.ContentBlock> blocks,
            String prompt,
            List<String> addDirs,
            McpGatewayCliConfig gatewayConfig,
            long sendStartNanos
    ) {
        CliPersistentProcessRegistry registry = this.registry;
        if (registry == null || !CliPersistentFeatureFlags.isClaudeEnabled()) {
            LOG.info("[CliPathDecision] tab=" + tabId + ", path=one-shot, reason="
                    + CliConstants.PATH_REASON_FLAG_DISABLED);
            return false;
        }
        // 版本门禁(§6.16-3):CLI 版本按最新 compatibility manifest 不再兼容时长驻降级。
        // send 流程刚完成 findCliExecutable,此处 version 通常非空;null(未检测)不拦截。
        // 用 evaluate 而非 isVersionAccepted:后者对 AHEAD_ALLOWED(允许但警告)也打 WARN,
        // 每轮 send 重复刷屏;此处仅在真正 !allowed 降级时记一条。
        String cliVersion = ClaudeCliDetector.getInstance().getCachedCliVersion();
        if (cliVersion != null) {
            CliCompatibilityDecision decision =
                    CliCompatibilityService.getInstance().evaluate(ProviderType.CLAUDE, cliVersion);
            if (!decision.allowed()) {
                LOG.warn("[CliPathDecision] tab=" + tabId + ", path=one-shot, reason="
                        + CliConstants.PATH_REASON_VERSION_INCOMPATIBLE + ", cliVersion=" + cliVersion
                        + ", status=" + decision.status());
                return false;
            }
        }
        Map<String, String> env = buildCliEnvironment(request, gatewayConfig);
        CliProcessSpec spec = persistentSendPath.buildSpec(cliPath, request, addDirs, gatewayConfig, env);
        CliPersistentProcess process = registry.acquire(tabId, PROVIDER_CLAUDE, spec);
        if (process == null) {
            // 未命中(指纹漂移/回收后/崩溃槽/超限/冷却):当前消息 one-shot,后台按新 spec 静默重建
            LOG.info("[CliPathDecision] tab=" + tabId + ", path=one-shot, reason="
                    + CliConstants.PATH_REASON_REGISTRY_MISS);
            registry.rebuildInBackground(tabId, PROVIDER_CLAUDE, spec);
            return false;
        }

        ClaudePersistentSendPath.TurnContext turnContext = persistentSendPath.createTurnContext(callback, process);
        activePersistentProcess = process;
        CliPersistentProcess.TurnHandle turn = null;
        try {
            String messageLine = persistentSendPath.buildUserMessageLine(prompt, blocks);
            turn = process.startTurn(messageLine, turnContext.handler);
            LOG.info("[CliPathDecision] tab=" + tabId + ", path=persistent, turnId=" + turn.turnId());
            LOG.info("[CliConcurrencyDiag][ClaudeCliSession] persistent turn started" + ": tabId=" + tabId
                    + ", turnId=" + turn.turnId()
                    + ", elapsedMs=" + elapsedMillis(sendStartNanos) + ", pid=" + process.pid()
                    + ", thread=" + Thread.currentThread().getName());
            if (userInterrupted.get()) {
                // startTurn 与 interrupt() 的竞态窗口兜底:interrupt 可能先于轮登记到达
                process.interruptTurn();
            }
            SDKResult turnResult = turn.future().get();
            LOG.info("[CliConcurrencyDiag][ClaudeCliSession] persistent turn finished" + ": tabId=" + tabId
                    + ", turnId=" + turn.turnId()
                    + ", elapsedMs=" + elapsedMillis(sendStartNanos)
                    + ", success=" + (turnResult != null && turnResult.success)
                    + ", interrupted=" + (turnResult != null && turnResult.interrupted)
                    + ", thread=" + Thread.currentThread().getName());
            this.normalizeCliSessionEntrypoint(request);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onInterrupted(turnContext.assistantText(), CliConstants.I18N_REQUEST_INTERRUPTED);
            return true;
        } catch (ExecutionException e) {
            return handlePersistentTurnFailure(callback, process, spec, turnContext, turn, e.getCause());
        } catch (Exception e) {
            // startTurn 即时失败(进程死/写入失败):消息未递交,静默降级 one-shot + 后台重建
            LOG.warn("[ClaudeCliSession][" + tabId + "] persistent turn failed to start", e);
            LOG.info("[CliPathDecision] tab=" + tabId + ", path=one-shot, reason="
                    + CliConstants.PATH_REASON_START_FAILED);
            registry.rebuildInBackground(tabId, PROVIDER_CLAUDE, spec);
            return false;
        } finally {
            activePersistentProcess = null;
        }
    }

    /**
     * 长驻轮 future 异常收尾(进程崩溃/轮超时强杀/interrupt 兜底杀/EOF)。
     * 中断标记 → 按中断语义收尾;其余(消息已递交 CLI 后失败)一律报错收尾不重发:
     * CLI 消费 stdin 后即把 user 消息写入 transcript,静默 one-shot 重发会造成同文 user
     * 行交错,故零输出也不再重试(实施计划 §6.14 的防交错取舍)。下条消息经 acquire
     * 未命中自然走 one-shot 自愈;后台重建让长驻尽快恢复。
     */
    private boolean handlePersistentTurnFailure(
            CliSessionCallback callback,
            CliPersistentProcess process,
            CliProcessSpec spec,
            ClaudePersistentSendPath.TurnContext turnContext,
            CliPersistentProcess.TurnHandle turn,
            Throwable cause
    ) {
        String reason = cause != null ? cause.getMessage() : "unknown";
        LOG.warn("[ClaudeCliSession][" + tabId + "] persistent turn failed: pid=" + process.pid()
                + ", turnId=" + (turn != null ? turn.turnId() : "-")
                + ", reason=" + reason);
        if ((turn != null && turn.wasInterrupted()) || userInterrupted.get()) {
            // interrupt 3s 兜底杀进程等:按中断语义收尾
            callback.onInterrupted(turnContext.assistantText(), CliConstants.I18N_REQUEST_INTERRUPTED);
            return true;
        }
        registry.rebuildInBackground(tabId, PROVIDER_CLAUDE, spec);
        String err = CliErrorFormatter.formatError("Claude", reason);
        callback.onError(err);
        callback.onComplete(false, turnContext.producedOutput() ? turnContext.assistantText() : null, err);
        return true;
    }

    /** one-shot 发送(原有路径):每轮独立进程 + --resume 续接。 */
    private void sendOneShot(
            CliSendRequest request,
            CliSessionCallback callback,
            String cliPath,
            String prompt,
            List<String> addDirs,
            McpGatewayCliConfig gatewayConfig,
            long sendStartNanos
    ) {
        StringBuilder diagnostic = new StringBuilder();
        AtomicBoolean completedWithStructuredError = new AtomicBoolean(false);
        Process process = null;
        CliProcessHandle currentHandle = null;
        try {
            LOG.info("[CliConcurrencyDiag][ClaudeCliSession] building command" + ": tabId=" + tabId + ", elapsedMs=" + elapsedMillis(
                    sendStartNanos) + ", thread=" + Thread.currentThread().getName());
            List<String> cmd = buildCommand(cliPath, request, prompt, addDirs, gatewayConfig);
            LOG.info("[CliConcurrencyDiag][ClaudeCliSession] command prepared" + ": tabId=" + tabId + ", elapsedMs=" + elapsedMillis(
                    sendStartNanos) + ", thread=" + Thread.currentThread().getName());
            LOG.info("[ClaudeCliSession][" + tabId + "] Command (prompt via stdin): " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> cliEnv = pb.environment();
            cliEnv.clear();
            cliEnv.putAll(buildCliEnvironment(request, gatewayConfig));

            // CWD 设置放在 pb.start() 紧前面，避免 TOCTOU 竞态：
            // 如果目录在 check 和 start 之间被删除，Windows CreateProcess 会报
            // "系统找不到指定的路径" (ERROR_PATH_NOT_FOUND)。
            if (request.cwd() != null && !request.cwd().isBlank()) {
                File cwd = new File(request.cwd());
                if (cwd.isDirectory()) {
                    pb.directory(cwd);
                } else {
                    LOG.warn("[ClaudeCliSession][" + tabId + "] CWD does not exist, falling back to home: " + request.cwd());
                    File homeDir = new File(PlatformUtils.getHomeDirectory());
                    if (homeDir.isDirectory()) {
                        pb.directory(homeDir);
                    }
                }
            }

            LOG.info("[CliConcurrencyDiag][ClaudeCliSession] starting process" + ": tabId=" + tabId + ", elapsedMs=" + elapsedMillis(
                    sendStartNanos) + ", thread=" + Thread.currentThread()
                    .getName());
            process = pb.start();
            currentHandle = new CliProcessHandle(process, "claude-tab-" + tabId);
            activeHandle = currentHandle;
            LOG.info("[CliConcurrencyDiag][ClaudeCliSession] process started" + ": tabId=" + tabId + ", elapsedMs=" + elapsedMillis(
                    sendStartNanos) + ", thread=" + Thread.currentThread()
                    .getName());
            if (userInterrupted.get()) {
                currentHandle.interrupt();
            } else {
                writePromptToStdin(process, prompt);
            }
            LOG.debug(
                    "[CliConcurrencyDiag][ClaudeCliSession] prompt written to stdin" + ": tabId=" + tabId + ", promptChars=" + prompt.length() + ", elapsedMs=" + elapsedMillis(
                            sendStartNanos) + ", thread=" + Thread.currentThread()
                            .getName());
            AtomicBoolean interruptHandled = new AtomicBoolean(false);
            Process runningProcess = process;
            CompletableFuture<Void> outputDrain = CliProcessLifecycle.drainAsync(
                    runningProcess,
                    () -> readOutput(runningProcess, callback, diagnostic, sendStartNanos,
                            completedWithStructuredError, interruptHandled)
            );
            CliProcessLifecycle.Outcome outcome = CliProcessLifecycle.await(process, outputDrain);
            int exitCode = outcome.exitCode();
            boolean interrupted = wasInterrupted();
            LOG.info(
                    "[CliConcurrencyDiag][ClaudeCliSession] process exited" + ": tabId=" + tabId + ", exitCode=" + exitCode + ", " +
                            "interrupted=" + interrupted + ", elapsedMs=" + elapsedMillis(
                             sendStartNanos) + ", thread=" + Thread.currentThread()
                             .getName());
            this.normalizeCliSessionEntrypoint(request);

            if (outcome.timedOut() && !interrupted && !resultEmitted) {
                String err = "Claude CLI request timed out";
                callback.onError(err);
                callback.onComplete(false, null, err);
            } else if (shouldEmitInterruptedCompletion(interruptHandled)) {
                callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
            } else if (shouldReportExitError(exitCode, completedWithStructuredError.get())) {
                String err = buildExitError(exitCode, diagnostic);
                maybeResetSessionAfterResumeFailure(diagnostic);
                callback.onError(err);
                callback.onComplete(false, null, err);
            }
        } catch (Exception e) {
            LOG.warn("[ClaudeCliSession][" + tabId + "] one-shot send failed", e);
            if (wasInterrupted()) {
                callback.onInterrupted(null, CliConstants.I18N_REQUEST_INTERRUPTED);
            } else {
                callback.onError(e.getMessage());
                callback.onComplete(false, null, e.getMessage());
            }
        } finally {
            CliProcessLifecycle.terminate(process);
            if (activeHandle == currentHandle) {
                activeHandle = null;
            }
        }
    }

    /**
     * CLI 环境链(one-shot 与长驻共用):基础环境 + 用户覆盖 + 模型通道 + 权限/项目路径 +
     * extra env + MCP gateway 注入。
     */
    private Map<String, String> buildCliEnvironment(CliSendRequest request, McpGatewayCliConfig gatewayConfig) {
        Map<String, String> cliEnv = new HashMap<>();
        cliEnv.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
        cliEnv.putAll(CliSettings.readClaudeCliEnvironment());
        cliEnv.put(CliConstants.ENV_CLAUDE_ENABLE_SDK_FILE_CHECKPOINTING, CliConstants.ENV_TRUE);
        configureRequestModelEnvironment(cliEnv, request, ClaudeCliModelResolver.resolveProfile(request.model()));
        cliEnv.put(CliConstants.ARG_NO_COLOR, "1");
        CliEnvironmentBuilder.configureClaudePermissionEnv(
                cliEnv,
                getPermissionDirectory(),
                getPermissionSessionId(request),
                getPermissionSafetyNetMs()
        );
        CliEnvironmentBuilder.configureProjectPath(cliEnv, request.cwd());
        CliEnvironmentBuilder.applyExtraEnv(cliEnv, request.extraEnv());
        if (gatewayConfig != null && gatewayConfig.usable()) {
            cliEnv.putAll(gatewayConfig.environment());
        }
        return cliEnv;
    }

    private void normalizeCliSessionEntrypoint(CliSendRequest request) {
        String completedSessionId = this.sessionId;
        if ((completedSessionId == null || completedSessionId.isBlank()) && request != null) {
            completedSessionId = request.sessionId();
        }

        ClaudeSessionEntrypointRewriter.RewriteResult result;
        try {
            result = this.entrypointRewriter.rewrite(
                    completedSessionId,
                    request != null ? request.cwd() : null,
                    Set.of(SessionEntrypoint.SDK_CLI),
                    SessionEntrypoint.CLI
            );
        } catch (Exception e) {
            LOG.warn("[ClaudeCliSession] Failed to normalize Claude session entrypoint: tab=" + tabId, e);
            return;
        }

        switch (result.status()) {
            case REWRITTEN -> LOG.info("[ClaudeCliSession] Normalized Claude CLI session entrypoint: tab="
                    + tabId + ", sessionId=" + completedSessionId + ", modifiedLines=" + result.modifiedCount());
            case ALREADY_TARGET -> LOG.debug("[ClaudeCliSession] Claude session entrypoint is already CLI: tab="
                    + tabId + ", sessionId=" + completedSessionId);
            case SOURCE_NOT_ACCEPTED -> LOG.debug("[ClaudeCliSession] Claude session entrypoint was not sdk-cli: tab="
                    + tabId + ", sessionId=" + completedSessionId);
            default -> LOG.warn("[ClaudeCliSession] Claude session entrypoint normalization skipped: tab="
                    + tabId + ", sessionId=" + completedSessionId + ", status=" + result.status());
        }
    }

    static void configureRequestModelEnvironment(
            Map<String, String> cliEnv,
            CliSendRequest request,
            ClaudeCliModelResolver.ResolvedModel profile
    ) {
        if (cliEnv == null || profile == null || profile.model() == null || profile.model().isBlank()) {
            return;
        }

        String resolvedModel = profile.model().trim();
        cliEnv.put(CommonConstants.ENV_ANTHROPIC_MODEL, resolvedModel);

        String selectedModel = request != null ? request.model() : null;
        ClaudeRole role = ClaudeRole.fromModelId(selectedModel);
        if (role != null) {
            // 角色模型:写入该角色的全部模型覆盖通道(含 fallback,如 Fable→Opus、Haiku→SMALL_FAST)
            role.applyModelEnv(cliEnv, resolvedModel);
        } else {
            // 非显式角色模型:写入默认 sonnet 通道
            cliEnv.put(CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL, resolvedModel);
        }
    }

    void prepareForSend() {
        userInterrupted.set(false);
        resultEmitted = false;
        mcpNoticeEmitted = false;
    }

    // ── 长驻路径(ClaudePersistentSendPath)包私有访问面 ─────────────────────

    Gson gson() {
        return gson;
    }

    CliMcpConfig mcpConfig() {
        return mcpConfig;
    }

    String tabId() {
        return tabId;
    }

    /** 长驻/one-shot 输出回调发现 session_id 时回填(session 续接与进程面板元数据共用入口)。 */
    void recordCliSessionId(String id) {
        if (id != null && !id.isBlank()) {
            this.sessionId = id;
        }
    }

    boolean isUserInterrupted() {
        return userInterrupted.get();
    }

    /**
     * MCP 连接失败(本地 server 未启动 / 连接被拒 / 传输关闭)处理:识别后降级为非阻塞
     * status 提示,而非缓冲为回合错误 / 报错。每回合仅发一次 toast(rmcp worker 可能
     * 重试多次刷屏),但每次匹配都抑制。对称 Codex {@code handleMcpFailure}。
     * <p>返回 true 表示文本命中 MCP 连接失败,调用方应跳过 diagnostic 缓冲 / onError。
     *
     * @param text     诊断行或事件 message(可能为 null)
     * @param callback 用于发 {@link CliConstants#CODEX_MSG_STATUS}
     * @return true 表示命中 MCP 连接失败(应抑制,不计入回合错误)
     */
    boolean handleMcpFailure(String text, CliSessionCallback callback) {
        // GatewayDownMatcher 先判:[melon-gateway-down] 是 stdio-client 降级标记(更明确),
        // 命中发 GATEWAY_DOWN_NOTICE(区别 MCP_SKIPPED_NOTICE:整轮 gateway 工具降级 vs 单 server 跳过)。
        // best-effort:仅当 claude 透传 melon_gateway stderr 时命中,不命中不影响功能(5s 超时兜底)。
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

    private CliSectionEmitter sectionEmitter(CliSessionCallback callback) {
        return new CliSectionEmitter(callback::onMessage);
    }

    private McpGatewayCliConfig buildGatewayConfig(CliSendRequest request) {
        if (gatewayService == null) {
            return McpGatewayCliConfig.disabled("No MCP Gateway service");
        }
        return gatewayService.buildCliConfig(ProviderType.CLAUDE, tabId, request.cwd());
    }

    private void readOutput(Process process, CliSessionCallback callback, StringBuilder diagnostic, long sendStartNanos,
                             AtomicBoolean completedWithStructuredError, AtomicBoolean interruptHandled) throws Exception {
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser(gson);
        parser.resetState();
        StringBuilder assistantContent = new StringBuilder();
        SDKResult result = new SDKResult();
        AtomicBoolean hadError = new AtomicBoolean(false);
        AtomicBoolean firstOutputLogged = new AtomicBoolean(false);

        MessageCallback mcb = new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                if (CliConstants.MSG_SESSION_ID.equals(type) && content != null && !content.isBlank()) {
                    sessionId = content;
                }
                callback.onMessage(type, content);
            }

            @Override
            public void onError(String error) {
                if (handleMcpFailure(error, callback)) {
                    // MCP 连接失败(本地 server 未启动):降级为非阻塞提示,不计入回合错误
                    return;
                }
                hadError.set(true);
                callback.onError(error);
            }

            @Override
            public void onComplete(SDKResult r) {
                // 由 readOutput 统一触发
            }
        };

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstOutputLogged.compareAndSet(false, true)) {
                    LOG.info(
                            "[CliConcurrencyDiag][ClaudeCliSession] first stdout line" + ": tabId=" + tabId + ", elapsedMs=" + elapsedMillis(
                                    sendStartNanos) + ", preview=" + previewLine(line) + ", thread=" + Thread.currentThread()
                                    .getName());
                }
                if (line.isBlank()) {
                    continue;
                }
                if (handleMcpFailure(line, callback)) {
                    // MCP 连接失败的非 JSON 噪声 / 签名行:降级为非阻塞提示,不污染 diagnostic
                    continue;
                }
                CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
                parser.parseLine(line, mcb, result, assistantContent, hadError, false);

                // result 事件 = 本轮结束
                if (isResultLine(gson, line) && !resultEmitted) {
                    if (sessionId != null) {
                        callback.onMessage(CliConstants.MSG_SESSION_ID, sessionId);
                    }
                    boolean success = !hadError.get() && result.success;
                    resultEmitted = true;
                    completedWithStructuredError.set(!success && result.error != null && !result.error.isBlank());
                    callback.onComplete(success, success ? assistantContent.toString() : null, success ? null : result.error);
                }
            }
        }

        if (resultEmitted) {
            return;
        }

        // 进程 stdout 结束但没有 result 事件
        boolean interrupted = wasInterrupted();
        if (interrupted) {
            interruptHandled.set(true);
            callback.onInterrupted(assistantContent.toString(), CliConstants.I18N_REQUEST_INTERRUPTED);
        } else if (!hadError.get() && !assistantContent.isEmpty()) {
            callback.onMessage(CliConstants.MSG_STREAM_END, "");
            callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
            callback.onComplete(true, assistantContent.toString(), null);
        } else {
            callback.onComplete(result.success, assistantContent.toString(), result.error);
        }
    }

    boolean wasInterrupted() {
        CliProcessHandle handle = activeHandle;
        return userInterrupted.get() || (handle != null && handle.wasInterrupted());
    }

    boolean shouldEmitInterruptedCompletion(AtomicBoolean interruptHandled) {
        return wasInterrupted() && (interruptHandled == null || !interruptHandled.get());
    }

    boolean shouldReportExitError(int exitCode, boolean completedWithStructuredError) {
        // resultEmitted:readOutput 已因 result 事件发过 onComplete,不再因 exitCode!=0 重复发错误。
        return exitCode != 0 && !completedWithStructuredError && !wasInterrupted() && !resultEmitted;
    }

    /**
     * --resume 失败时(会话已损坏/不存在),重置 sessionId 使下一轮重新开始,避免死循环。
     * <p>防御纵深:除显式 resume 失败关键词外,额外校验 sessionId 是否为合法 UUID。
     * 跨 provider 污染(如 OpenCode 的 ses_ 前缀)若绕过 setProvider 隔离再次混入,
     * Claude CLI 会报 "ses_xxx is not a UUID" 并崩溃,此处关键词+格式双保险使其自愈。
     */
    void maybeResetSessionAfterResumeFailure(CharSequence diagnostic) {
        if (sessionId == null) {
            return;
        }
        // 格式校验:Claude sessionId 必为 UUID,非 UUID 直接重置(如 ses_ 污染)
        if (!isValidClaudeSessionId(sessionId)) {
            LOG.warn("[ClaudeCliSession] sessionId is not a valid UUID, resetting to start fresh: tab=" + tabId
                    + ", sessionId=" + sessionId);
            sessionId = null;
            return;
        }
        if (diagnostic == null) {
            return;
        }
        String text = diagnostic.toString().toLowerCase(Locale.ROOT);
        boolean resumeFailure = text.contains("no conversation") || text.contains("conversation not found")
                || text.contains("session not found")
                || text.contains("not a uuid")
                || text.contains("resume") && (text.contains("not found") || text.contains("fail"));
        if (resumeFailure) {
            LOG.info("[ClaudeCliSession] --resume failed, resetting sessionId to start fresh: tab=" + tabId);
            sessionId = null;
        }
    }

    private static boolean isValidClaudeSessionId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            // UUID.fromString 宽松(允许非标准分隔),用严格格式串兜底
            UUID.fromString(id);
            return id.length() == 36 && id.charAt(8) == '-' && id.charAt(13) == '-'
                    && id.charAt(18) == '-' && id.charAt(23) == '-';
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String getPermissionDirectory() {
        String cached = permissionDir;
        if (cached != null) {
            return cached;
        }
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "claude-permission");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            LOG.warn("[ClaudeCliSession] Failed to prepare permission dir: " + dir + " (" + e.getMessage() + ")");
        }
        permissionDir = dir.toAbsolutePath().toString();
        return permissionDir;
    }

    private String getPermissionSessionId(CliSendRequest request) {
        if (request.permissionSessionId() != null && !request.permissionSessionId().isBlank()) {
            return request.permissionSessionId();
        }
        String cached = cliPermissionSessionId;
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        String generated = java.util.UUID.randomUUID().toString();
        cliPermissionSessionId = generated;
        return generated;
    }

    private long getPermissionSafetyNetMs() {
        return CliSettings.getClaudePermissionSafetyNetMs();
    }

}
