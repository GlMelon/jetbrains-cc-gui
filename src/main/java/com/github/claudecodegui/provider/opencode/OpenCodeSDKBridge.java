package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;

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
import java.util.concurrent.TimeUnit;
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

    /** §15.7 B18:OpenCode serve 守护进程协调器(懒启动/健康探测/销毁)。 */
    private final OpenCodeDaemonCoordinator daemonCoordinator;

    /** §15.8 §11:动态刷新 OpenCode 模型列表的超时(ms)。 */
    private static final long LIST_MODELS_TIMEOUT_MS = 15000L;

    public OpenCodeSDKBridge() {
        super(OpenCodeSDKBridge.class);
        this.daemonCoordinator = new OpenCodeDaemonCoordinator(LOG);
    }

    OpenCodeSDKBridge(Path sessionsDir) {
        super(OpenCodeSDKBridge.class);
        this.daemonCoordinator = new OpenCodeDaemonCoordinator(LOG);
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
                    hadSendError.set(true);
                    lastNodeError.set(message);
                    callback.onError(message);
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
        // baseUrl:由 DaemonCoordinator 懒启动 serve 后返回;serve 不可用时为 null(下游兜底默认 URL)
        String baseUrl = resolveBaseUrl();
        List<String> command = buildSendCommand();
        String stdinJson = buildSendStdinJson(message, sessionId, cwd, permissionMode,
                model, reasoningEffort, attachments, baseUrl);
        return executeStreamingCommand(channelId, command, stdinJson, cwd, callback);
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

    List<String> buildSendCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeDetector.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(getProviderName());
        cmd.add("send");
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
     * §15.8 §11:查询 OpenCode serve 已配置 provider 的模型列表(能力层,前端 UI defer)。
     * <p>
     * 走 channel {@code opencode listModels}(对称 Codex {@code getMcpServerTools} 非流式模式),
     * 读 stdout 含 {@code success} 的 JSON 行,返回 {success, models:[{provider,model,...}]}。
     * channel-manager 对 opencode provider 已 force-exit,HTTP/SSE 连接由其兜底释放。
     *
     * @return {success:true, models:[...]} 或 {success:false, error, models:[]}
     */
    public CompletableFuture<com.google.gson.JsonObject> listModels() {
        return CompletableFuture.supplyAsync(() -> {
            String channelId = ProcessManager.newChannelId("__opencode_list_models__");
            Process process = null;
            LOG.info("[OpenCodeListModels] starting");
            try {
                File bridgeDir = getDirectoryResolver().findSdkDir();
                if (bridgeDir == null || !bridgeDir.exists()) {
                    return modelsError("Bridge directory not ready");
                }
                String node = nodeDetector.getNodeExecutable();
                String stdinJson = buildListModelsStdinJson(resolveBaseUrl());

                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(CHANNEL_SCRIPT);
                command.add(getProviderName());
                command.add("listModels");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(bridgeDir);
                pb.redirectErrorStream(true);
                envConfigurator.updateProcessEnvironment(pb, node);
                pb.environment().put(CliConstants.ENV_OPENCODE_USE_STDIN, "true");

                process = pb.start();
                processManager.registerProcess(channelId, process);
                final Process finalProcess = process;

                try (OutputStream stdin = finalProcess.getOutputStream()) {
                    stdin.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }

                StringBuilder output = new StringBuilder();
                AtomicReference<String> resultJson = new AtomicReference<>(null);
                AtomicBoolean readerDone = new AtomicBoolean(false);

                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                            // listModels 输出单对象 NDJSON,捕获最后含 success 的行(成功/失败均带 success)
                            if (line.contains("\"success\"")) {
                                resultJson.set(line.trim());
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("[OpenCodeListModels] reader exception: " + e.getMessage());
                    } finally {
                        readerDone.set(true);
                    }
                });
                readerThread.start();

                long deadline = System.currentTimeMillis() + LIST_MODELS_TIMEOUT_MS;
                while (!readerDone.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }

                String captured = resultJson.get();
                if (captured != null) {
                    try {
                        com.google.gson.JsonObject result = gson.fromJson(captured, com.google.gson.JsonObject.class);
                        if (result != null) {
                            return result;
                        }
                    } catch (Exception e) {
                        LOG.debug("[OpenCodeListModels] parse failed: " + e.getMessage());
                    }
                }
                return modelsError("Failed to list OpenCode models");
            } catch (Exception e) {
                LOG.error("[OpenCodeListModels] exception: " + e.getMessage(), e);
                return modelsError(e.getMessage());
            } finally {
                if (process != null) {
                    try {
                        if (process.isAlive()) {
                            PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                        }
                    } finally {
                        processManager.unregisterProcess(channelId, process);
                    }
                }
            }
        });
    }

    /** 构造 listModels 失败结果 {success:false, error, models:[]}。 */
    private static com.google.gson.JsonObject modelsError(String message) {
        com.google.gson.JsonObject err = new com.google.gson.JsonObject();
        err.addProperty("success", false);
        err.addProperty("error", message != null ? message : "Unknown error");
        err.add("models", new com.google.gson.JsonArray());
        return err;
    }

    /**
     * §15.8 §11:构建 listModels 的 stdin JSON(纯函数,static 便于无 Platform 上下文单测)。
     * 仅 baseUrl 字段;空/缺失回退 DaemonCoordinator 默认 URL。
     */
    static String buildListModelsStdinJson(String baseUrl) {
        com.google.gson.JsonObject stdin = new com.google.gson.JsonObject();
        String effectiveBaseUrl = (baseUrl != null && !baseUrl.isBlank())
                ? baseUrl : OpenCodeDaemonCoordinator.defaultServerUrl();
        stdin.addProperty("baseUrl", effectiveBaseUrl);
        return GsonHolder.GSON.toJson(stdin);
    }
}
