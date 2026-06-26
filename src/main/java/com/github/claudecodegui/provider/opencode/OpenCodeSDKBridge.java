package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.BaseSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenCode SDK bridge：通过 Node.js bridge 层与 OpenCode HTTP API 交互。
 * <p>
 * 复用 {@link BaseSDKBridge} 模板方法，通过 channel-manager.js 桥接 OpenCode 的
 * HTTP REST API（opencode serve）。
 */
public class OpenCodeSDKBridge extends BaseSDKBridge {

    public OpenCodeSDKBridge() {
        super(OpenCodeSDKBridge.class);
    }

    OpenCodeSDKBridge(Path sessionsDir) {
        super(OpenCodeSDKBridge.class);
    }

    @Override
    protected String getProviderName() {
        return CommonConstants.PROVIDER_OPENCODE;
    }

    @Override
    protected void configureProviderEnv(Map<String, String> env, String stdinJson) {
        // OpenCode 通过 HTTP API 通信，无需特殊环境变量配置
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
     * 通过 stdin 传递 JSON 参数，逐行解析 stdout 输出。
     *
     * @param channelId    channel ID
     * @param message      user message
     * @param sessionId    session/thread ID (may be null for first turn)
     * @param cwd          working directory
     * @param model        model name (may be null)
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
        List<String> command = buildSendCommand();
        String stdinJson = buildSendStdinJson(message, sessionId, cwd, model);
        return executeStreamingCommand(channelId, command, stdinJson, cwd, callback);
    }

    private List<String> buildSendCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeDetector.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(getProviderName());
        cmd.add("send");
        return cmd;
    }

    private String buildSendStdinJson(String message, String sessionId, String cwd, String model) {
        com.google.gson.JsonObject stdin = new com.google.gson.JsonObject();
        stdin.addProperty("message", message != null ? message : "");
        stdin.addProperty("threadId", sessionId != null ? sessionId : "");
        stdin.addProperty("cwd", cwd != null ? cwd : "");
        stdin.addProperty("model", model != null ? model : "");
        return gson.toJson(stdin);
    }
}
