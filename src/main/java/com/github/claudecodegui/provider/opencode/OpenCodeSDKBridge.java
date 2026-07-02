package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.McpErrorMatcher;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenCode SDK bridge：通过 Node.js bridge 层与 OpenCode HTTP API 交互。
 * <p>
 * 复用 {@link BaseSDKBridge} 模板方法，通过 channel-manager.js 桥接 OpenCode 的
 * HTTP REST API（opencode serve）。serve 守护进程由 {@link OpenCodeDaemonCoordinator}
 * 统一管理(B18),bridge 把 baseUrl 经 stdin 注入每次 channel 调用。
 */
public class OpenCodeSDKBridge extends BaseSDKBridge {
    private static final int HISTORY_QUERY_TIMEOUT_SECONDS = 30;

    /** §15.7 B18:OpenCode serve 守护进程协调器(懒启动/健康探测/销毁)。 */
    private final OpenCodeDaemonCoordinator daemonCoordinator;

    /**
     * §gateway:SDK MCP Gateway 服务(对称 Claude/Codex SDK bridge)。透传给 DaemonCoordinator,
     * serve 启动期固化 melon_gateway(env 注入)+ revision 漂移重启。null(历史/no-arg 构造)→ serve 不带 gateway。
     */
    private final McpGatewayService mcpGatewayService;
    /** §gateway:project 根路径,透传给 coordinator 用于 buildSdkServeConfig 定位 gateway 进程 + MCP 收集。 */
    private final String projectPath;

    /**
     * §abort:channelId → opencode threadId(sessionId)映射。
     * <p>
     * send 入口建立:interruptChannel(channelId) 用 state.getChannelId(),而 send 传的是
     * state.getSessionId()(两者是 state 不同字段),故需显式映射,interrupt 时查表恢复 threadId 触发 abort。
     * send 完成(whenComplete)或 interrupt 时移除(ConcurrentHashMap.remove 幂等)。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> channelThreads =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * §gateway:主构造——经 ProjectBridgeRegistry 注入 project-scoped McpGatewayService + project 根路径,
     * 透传给 DaemonCoordinator 用于 SDK 调用模式下 serve 启动期注入 gateway env(对称 Claude/Codex SDK bridge)。
     */
    public OpenCodeSDKBridge(McpGatewayService mcpGatewayService, String projectPath) {
        super(OpenCodeSDKBridge.class);
        this.mcpGatewayService = mcpGatewayService;
        this.projectPath = projectPath;
        this.daemonCoordinator = new OpenCodeDaemonCoordinator(LOG, mcpGatewayService, projectPath);
    }

    public OpenCodeSDKBridge() {
        this(null, null);
    }

    OpenCodeSDKBridge(Path sessionsDir) {
        this(null, null);
    }

    @Override
    protected String getProviderName() {
        return CommonConstants.PROVIDER_OPENCODE;
    }

    @Override
    protected void configureProviderEnv(Map<String, String> env, String stdinJson) {
        // §15.7 B2:启用 stdin 读取开关,对称 Claude(CLAUDE_USE_STDIN)/ Codex(CODEX_USE_STDIN)。
        // channel-manager.js → opencode-channel.js → stdin-utils.js 仅在 OPENCODE_USE_STDIN=true 时读取 stdin;
        // 缺失此开关则 readStdinData 返回 null → message/baseUrl 等字段全部丢失。
        env.put(CliConstants.ENV_OPENCODE_USE_STDIN, "true");
    }

    @Override
    protected void processOutputLine(
            String line,
            MessageCallback callback,
            SDKResult result,
            StringBuilder assistantContent,
            AtomicBoolean hadSendError,
            AtomicReference<String> lastNodeError
    ) {
        // OpenCode 的输出解析由 Node.js bridge 层处理
        // 这里处理 bridge 层透传的 NDJSON 行
        if (line == null || line.isBlank()) {
            return;
        }

        try {
            com.google.gson.JsonObject event = gson.fromJson(line, com.google.gson.JsonObject.class);
            if (event == null) {
                return;
            }

            String type = event.has("type") ? event.get("type").getAsString() : null;
            if (type == null) {
                return;
            }

            switch (type) {
                case CliConstants.MSG_SESSION_ID -> {
                    String sessionId = event.has("session_id") ? event.get("session_id").getAsString() : null;
                    if (sessionId != null) {
                        callback.onMessage(CliConstants.MSG_SESSION_ID, sessionId);
                    }
                }
                case CliConstants.MSG_STREAM_START -> callback.onMessage(CliConstants.MSG_STREAM_START, "");
                case CliConstants.MSG_STREAM_END -> callback.onMessage(CliConstants.MSG_STREAM_END, "");
                case CliConstants.MSG_MESSAGE_START -> callback.onMessage(CliConstants.MSG_MESSAGE_START, "");
                case CliConstants.MSG_MESSAGE_END -> callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
                case CliConstants.MSG_CONTENT_DELTA -> {
                    String text = event.has("text") ? event.get("text").getAsString() : "";
                    if (!text.isEmpty()) {
                        assistantContent.append(text);
                        callback.onMessage(CliConstants.MSG_CONTENT_DELTA, text);
                    }
                }
                case CliConstants.MSG_THINKING_DELTA -> {
                    String text = event.has("text") ? event.get("text").getAsString() : "";
                    callback.onMessage(CliConstants.MSG_THINKING_DELTA, text);
                }
                // 断点C 修复:首个 reasoning 合成的思考激活态(对称 CLI thinkingStart),
                // 点亮前端"思考中"指示灯(CodexMessageHandler.handleThinkingMessage)。
                case CommonConstants.MSG_TYPE_THINKING -> callback.onMessage(CommonConstants.MSG_TYPE_THINKING, "");
                // 断点B 修复:tool part 归一出的 tool_use/tool_result(对称 CLI handleToolUse),
                // 使 CodexMessageHandler 渲染可折叠工具卡(而非 assistant 乱码 JSON)。
                // content 是 Anthropic schema 的 block JSON 字符串(event-mapper emitToolBlocks 产出)。
                case CommonConstants.MSG_TYPE_TOOL_USE -> {
                    String content = event.has("content") ? event.get("content").getAsString() : event.toString();
                    callback.onMessage(CommonConstants.MSG_TYPE_TOOL_USE, content);
                }
                case CommonConstants.MSG_TYPE_TOOL_RESULT -> {
                    String content = event.has("content") ? event.get("content").getAsString() : event.toString();
                    callback.onMessage(CommonConstants.MSG_TYPE_TOOL_RESULT, content);
                }
                case CommonConstants.MSG_TYPE_ASSISTANT -> {
                    String content = event.toString();
                    callback.onMessage(CommonConstants.MSG_TYPE_ASSISTANT, content);
                }
                case CommonConstants.MSG_TYPE_USER -> {
                    String content = event.toString();
                    callback.onMessage(CommonConstants.MSG_TYPE_USER, content);
                }
                case CliConstants.MSG_USAGE -> {
                    String content = event.toString();
                    callback.onMessage(CliConstants.MSG_USAGE, content);
                }
                case CliConstants.MSG_RESULT -> {
                    String content = event.toString();
                    callback.onMessage(CliConstants.MSG_RESULT, content);
                }
                case CommonConstants.MSG_TYPE_ERROR -> {
                    String message = event.has("message") ? event.get("message").getAsString() : "Unknown error";
                    if (McpErrorMatcher.isMcpConnectionFailure(message)) {
                        // MCP 连接失败(本地 server 未启动):降级为非阻塞 status 提示,不标记 hadSendError/报错。
                        // 镜像 Codex SDK [SEND_ERROR]、OpenCode CLI handleError 的降级处理(Principle 6 对称)。
                        callback.onMessage(CliConstants.CODEX_MSG_STATUS, McpErrorMatcher.MCP_SKIPPED_NOTICE);
                    } else {
                        hadSendError.set(true);
                        lastNodeError.set(message);
                        callback.onError(message);
                    }
                }
                default -> {
                    // Ignore unknown event types
                }
            }
        } catch (Exception e) {
            // Not JSON, ignore
        }
    }

    /**
     * §abort:对称 Claude/Codex SDK 的 interruptChannel override。
     * <p>
     * Claude/Codex 在 interrupt 时给常驻 DaemonBridge 发 sendAbort(确定性取消当前 request);OpenCode 无
     * 常驻 daemon(serve 是 HTTP,send 是 per-process node),仅杀 send 进程(super)依赖客户端断开副作用——
     * opencode serve 可能继续生成,导致 token 泄漏 + 会话状态不一致。此 override 在 super 前 spawn 一次性
     * channel-manager.js abort 命令,经 opencode-channel.js → message-service.abortSession →
     * client.session.abort 显式取消 serve 侧生成(确定性,对称 sendAbort)。
     */
    @Override
    public void interruptChannel(String channelId) {
        String threadId = channelThreads.remove(channelId);
        if (threadId != null && !threadId.isBlank()) {
            LOG.info("[OpenCodeSDKBridge] Triggering opencode abort for channel: " + channelId);
            try {
                triggerAbort(threadId);
            } catch (Exception e) {
                LOG.warn("[OpenCodeSDKBridge] Abort trigger failed: " + e.getMessage());
            }
        }
        // per-process fallback:杀 send 进程(覆盖 abort spawn 失败 / 早期无 threadId 场景)
        super.interruptChannel(channelId);
    }

    /**
     * Spawn channel-manager.js abort 命令(fire-and-forget)。复用 executeStreamingCommand 的进程管理
     * (env/node/bridgeDir/drain/cleanup),no-op callback 丢弃 abort 进程输出。abort 是短生命周期 HTTP POST
     * (channel-manager.js 对 opencode 强制 100ms 后 exit),CompletableFuture 异步不阻塞 interrupt 调用方。
     */
    private void triggerAbort(String threadId) {
        String baseUrl;
        try {
            baseUrl = resolveBaseUrl();
        } catch (Exception e) {
            baseUrl = null;
        }
        List<String> command = buildAbortCommand();
        String stdinJson = buildAbortStdinJson(threadId, baseUrl);
        MessageCallback noop = new MessageCallback() {
            @Override public void onMessage(String type, String content) { /* discard */ }
            @Override public void onError(String error) { /* best-effort,忽略 */ }
            @Override public void onComplete(SDKResult result) { /* discard */ }
        };
        executeStreamingCommand("opencode-abort-" + threadId, command, stdinJson, null, noop);
    }

    /**
     * Send a message via OpenCode SDK bridge (Node.js channel-manager.js)。
     * <p>
     * 构建 [node, channel-manager.js, opencode, send] 命令，
     * 通过 stdin 传递 7+ 字段 JSON 参数(B11),逐行解析 stdout 输出。
     *
     * @param channelId    channel ID
     * @param message      user message
     * @param sessionId    session/thread ID (may be null for first turn)
     * @param cwd          working directory
     * @param model        model name (provider/model,may be null)
     * @param callback     message callback
     * @return async result
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            String model,
            MessageCallback callback
    ) {
        return sendMessage(channelId, message, sessionId, cwd, model,
                null, null, null, callback);
    }

    /**
     * §15.7 B11:全字段 sendMessage。permissionMode/reasoningEffort/attachments 与
     * Claude/Codex 对齐;baseUrl 由 DaemonCoordinator 解析后注入。
     */
    public CompletableFuture<SDKResult> sendMessage(
            String channelId,
            String message,
            String sessionId,
            String cwd,
            String model,
            String permissionMode,
            String reasoningEffort,
            List<ClaudeSession.Attachment> attachments,
            MessageCallback callback
    ) {
        // §abort:建立 channelId→threadId 映射,interrupt 时据此触发 opencode abort(对称 Claude/Codex sendAbort)。
        if (sessionId != null && !sessionId.isBlank()) {
            channelThreads.put(channelId, sessionId);
        }
        // baseUrl:由 DaemonCoordinator 懒启动 serve 后返回;serve 不可用时为 null(下游兜底默认 URL)
        String baseUrl = resolveBaseUrl();
        List<String> command = buildSendCommand();
        String stdinJson = buildSendStdinJson(message, sessionId, cwd, permissionMode,
                model, reasoningEffort, attachments, baseUrl);
        // whenComplete 清理映射:send 正常完成/异常时移除(interrupt 时另移除,remove 幂等),防映射累积。
        return executeStreamingCommand(channelId, command, stdinJson, cwd, callback)
                .whenComplete((result, ex) -> channelThreads.remove(channelId));
    }

    private String resolveBaseUrl() {
        try {
            return daemonCoordinator.getServerUrl();
        } catch (Exception e) {
            LOG.debug("[OpenCode] DaemonCoordinator getServerUrl failed: " + e.getMessage());
            return null;
        }
    }

    /** §15.7 B18:IDE dispose 时销毁常驻 serve 进程。 */
    public void shutdownDaemon() {
        try {
            daemonCoordinator.shutdownServer();
        } catch (Exception e) {
            LOG.debug("[OpenCode] shutdownDaemon failed: " + e.getMessage());
        }
    }

    /**
     * IDE dispose 时先停常驻 serve 进程,再由 super 清理 send 进程映射。
     * 对称 Claude/Codex SDK 的 cleanupAllProcesses override:OpenCode serve 是常驻进程
     * (DaemonCoordinator),不 override 则 dispose 时仅清 send 进程、serve 泄漏(端口/资源,§15.7 B18)。
     */
    @Override
    public void cleanupAllProcesses() {
        shutdownDaemon();
        super.cleanupAllProcesses();
    }

    /**
     * Read persisted OpenCode session history from the local OpenCode database.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            File bridgeDir = getDirectoryResolver().findSdkDir();
            if (bridgeDir == null) {
                LOG.warn("[OpenCode] Bridge directory not ready, cannot load history");
                return List.of();
            }

            List<String> command = buildGetSessionCommand();
            JsonObject stdin = new JsonObject();
            stdin.addProperty("sessionId", sessionId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            configureProviderEnv(env, stdin.toString());
            String node = nodeDetector.findNodeExecutable();
            envConfigurator.updateProcessEnvironment(pb, node);

            StringBuilder output = new StringBuilder();
            String channelId = ProcessManager.newChannelId("opencode-history-query");
            Process process = null;
            CompletableFuture<Void> outputFuture = null;
            try {
                process = pb.start();
                processManager.registerProcess(channelId, process);
                Process runningProcess = process;
                outputFuture = CompletableFuture.runAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    } catch (Exception e) {
                        LOG.debug("[OpenCode] getSession output drain failed: " + e.getMessage());
                    }
                });

                try (OutputStream stdinStream = process.getOutputStream()) {
                    stdinStream.write(GsonHolder.GSON.toJson(stdin).getBytes(StandardCharsets.UTF_8));
                    stdinStream.flush();
                }

                if (!process.waitFor(HISTORY_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                    LOG.warn("[OpenCode] getSession timed out for session: " + sessionId);
                    return List.of();
                }
                waitForOutputDrain(outputFuture);
            } finally {
                if (outputFuture != null && !outputFuture.isDone()) {
                    outputFuture.cancel(true);
                }
                if (process != null) {
                    processManager.unregisterProcess(channelId, process);
                }
            }

            JsonObject result = extractLastJsonObject(output.toString());
            if (result == null || !result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result != null && result.has("error") && !result.get("error").isJsonNull()
                        ? result.get("error").getAsString()
                        : "unknown error";
                LOG.warn("[OpenCode] Failed to load session history: " + error);
                return List.of();
            }
            if (!result.has("messages") || !result.get("messages").isJsonArray()) {
                return List.of();
            }

            List<JsonObject> messages = new ArrayList<>();
            JsonArray array = result.getAsJsonArray("messages");
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    messages.add(array.get(i).getAsJsonObject());
                }
            }
            return messages;
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to load session history: " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 枚举 OpenCode 会话(对称 Codex CodexHistoryReader.getSessionsForProjectAsJson)。
     * <p>
     * spawn channel-manager.js opencode listSessions,stdin 注入 projectPath(空串返回全部),
     * 字段映射由 ai-bridge/services/opencode/history-service.js 完成(对齐 Codex SessionInfo)。
     * 返回原始 JSON 字符串({success, sessions, sessionCount}),交由 OpenCodeHistoryProviderAdapter
     * 透传给前端(与 Claude/Codex 历史路径一致,均返回 String);失败返回空串(adapter 归一化兜底)。
     *
     * @param projectPath 项目根路径,null/空返回全部会话
     * @return 原始 JSON 字符串;失败返回空串(不抛)
     */
    public String getSessionList(String projectPath) {
        try {
            File bridgeDir = getDirectoryResolver().findSdkDir();
            if (bridgeDir == null) {
                LOG.warn("[OpenCode] Bridge directory not ready, cannot list sessions");
                return "";
            }

            List<String> command = buildListSessionsCommand();
            JsonObject stdin = new JsonObject();
            stdin.addProperty("projectPath", projectPath != null ? projectPath : "");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            configureProviderEnv(env, stdin.toString());
            String node = nodeDetector.findNodeExecutable();
            envConfigurator.updateProcessEnvironment(pb, node);

            StringBuilder output = new StringBuilder();
            String channelId = ProcessManager.newChannelId("opencode-session-list");
            Process process = null;
            CompletableFuture<Void> outputFuture = null;
            try {
                process = pb.start();
                processManager.registerProcess(channelId, process);
                Process runningProcess = process;
                outputFuture = CompletableFuture.runAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    } catch (Exception e) {
                        LOG.debug("[OpenCode] listSessions output drain failed: " + e.getMessage());
                    }
                });

                try (OutputStream stdinStream = process.getOutputStream()) {
                    stdinStream.write(GsonHolder.GSON.toJson(stdin).getBytes(StandardCharsets.UTF_8));
                    stdinStream.flush();
                }

                if (!process.waitFor(HISTORY_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                    LOG.warn("[OpenCode] listSessions timed out");
                    return "";
                }
                waitForOutputDrain(outputFuture);
            } finally {
                if (outputFuture != null && !outputFuture.isDone()) {
                    outputFuture.cancel(true);
                }
                if (process != null) {
                    processManager.unregisterProcess(channelId, process);
                }
            }

            JsonObject result = extractLastJsonObject(output.toString());
            if (result == null || !result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result != null && result.has("error") && !result.get("error").isJsonNull()
                        ? result.get("error").getAsString()
                        : "unknown error";
                LOG.warn("[OpenCode] Failed to list sessions: " + error);
                return "";
            }
            return result.toString();
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to list sessions: " + e.getMessage(), e);
            return "";
        }
    }

    private void waitForOutputDrain(CompletableFuture<Void> outputFuture) {
        if (outputFuture == null) {
            return;
        }
        try {
            outputFuture.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.debug("[OpenCode] getSession output drain did not finish cleanly: " + e.getMessage());
        }
    }

    private JsonObject extractLastJsonObject(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    return gson.fromJson(line, JsonObject.class);
                } catch (Exception ignored) {
                    // Try earlier lines.
                }
            }
        }
        return null;
    }

    List<String> buildSendCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeDetector.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(getProviderName());
        cmd.add("send");
        return cmd;
    }

    List<String> buildGetSessionCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeDetector.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(getProviderName());
        cmd.add("getSession");
        return cmd;
    }

    /**
     * 会话枚举命令(对称 buildGetSessionCommand,仅末位参 getSession→listSessions)。
     */
    List<String> buildListSessionsCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeDetector.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(getProviderName());
        cmd.add("listSessions");
        return cmd;
    }

    /**
     * §15.7 B11:构建 8 字段 stdin JSON(纯函数,static 便于无 Platform 上下文单测)。
     * attachments 物化为临时文件后以路径数组透传(OpenCode 文件 part schema 待 §16 实测验证,
     * message-service 暂以路径透传,SDK 侧保守跳过 image part)。
     */
    static String buildSendStdinJson(String message, String sessionId, String cwd,
                                     String permissionMode, String model, String reasoningEffort,
                                     List<ClaudeSession.Attachment> attachments, String baseUrl) {
        com.google.gson.JsonObject stdin = new com.google.gson.JsonObject();
        stdin.addProperty("message", message != null ? message : "");
        stdin.addProperty("threadId", sessionId != null ? sessionId : "");
        stdin.addProperty("cwd", cwd != null ? cwd : "");
        stdin.addProperty("permissionMode", permissionMode != null ? permissionMode : "");
        stdin.addProperty("model", model != null ? model : "");
        stdin.addProperty("reasoningEffort", reasoningEffort != null ? reasoningEffort : "");
        // attachments:image 物化为临时文件,以路径数组透传
        com.google.gson.JsonArray attachArr = new com.google.gson.JsonArray();
        if (attachments != null && !attachments.isEmpty()) {
            List<File> tempFiles = new ArrayList<>();
            try {
                CliAttachmentHandler handler = new CliAttachmentHandler();
                List<File> files = handler.processForCodex(attachments, tempFiles);
                for (File f : files) {
                    attachArr.add(f.getAbsolutePath());
                }
            } catch (Exception e) {
                // 物化失败不阻断主流程(图片附件可选)
            }
        }
        stdin.add("attachments", attachArr);
        // baseUrl:null/空回退到 DaemonCoordinator 默认 URL(测试与 serve 不可用场景)
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl : OpenCodeDaemonCoordinator.defaultServerUrl();
        stdin.addProperty("baseUrl", effectiveBaseUrl);
        return GsonHolder.GSON.toJson(stdin);
    }

    /**
     * §abort:构建 channel-manager.js abort 命令(对称 buildSendCommand,仅末位参数 send→abort)。
     */
    List<String> buildAbortCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeDetector.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(getProviderName());
        cmd.add("abort");
        return cmd;
    }

    /**
     * §abort:构建 abort stdin JSON(2 字段:threadId/baseUrl,对齐 opencode-channel.js abort 契约)。
     * 纯函数 static,便于无 Platform 上下文单测(对称 buildSendStdinJson)。
     */
    static String buildAbortStdinJson(String threadId, String baseUrl) {
        com.google.gson.JsonObject stdin = new com.google.gson.JsonObject();
        stdin.addProperty("threadId", threadId != null ? threadId : "");
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl : OpenCodeDaemonCoordinator.defaultServerUrl();
        stdin.addProperty("baseUrl", effectiveBaseUrl);
        return GsonHolder.GSON.toJson(stdin);
    }

}
