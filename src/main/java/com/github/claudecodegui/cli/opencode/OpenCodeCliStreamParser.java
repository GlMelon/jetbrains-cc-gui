package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * §15.4 / §7.3:OpenCode {@code opencode run --format json} 事件流解析器。
 * <p>
 * 按真实事件 schema 解析(样本实捕自 opencode v1.17.11),输出统一 MSG_* 协议,
 * 经 {@link com.github.claudecodegui.session.CodexMessageHandler} 消费。
 * 每次发送(含 B13 失效重试)构造新实例,持有本次运行的全部可变状态。
 * <p>
 * 事件映射(详见设计 §7.3):
 * <ul>
 *   <li>{@code step_start}(首轮) → {@link CliConstants#MSG_STREAM_START};并从顶层 {@code sessionID}
 *       提取并下发 {@link CliConstants#MSG_SESSION_ID}(仅首轮一次)</li>
 *   <li>{@code text} → {@link CliConstants#MSG_CONTENT_DELTA}({@code part.text})</li>
 *   <li>{@code tool_use} → {@link CommonConstants#MSG_TYPE_TOOL_USE}(tool_use 原始块)+
 *       {@link CommonConstants#MSG_TYPE_TOOL_RESULT}(tool_result 原始块)</li>
 *   <li>{@code step_finish} → {@link CliConstants#MSG_RESULT}(归一 usage,因 handler 无 MSG_USAGE case,
 *       usage 必须经 MSG_RESULT 走 handleResultMessage);{@code part.reason=="stop"} 追加
 *       {@link CliConstants#MSG_STREAM_END}+{@link CliConstants#MSG_MESSAGE_END},
 *       {@code "tool-calls"} 不结束流(后续还有 step)</li>
 *   <li>{@code error} → 收集到 {@link #errorDiagnostic()},由会话层在结束时上报</li>
 * </ul>
 */
public class OpenCodeCliStreamParser {

    private static final Logger LOG = Logger.getInstance(OpenCodeCliStreamParser.class);
    private static final String EVENT_STEP_START = "step_start";
    private static final String EVENT_TEXT = "text";
    private static final String EVENT_TOOL_USE = "tool_use";
    private static final String EVENT_STEP_FINISH = "step_finish";
    private static final String EVENT_ERROR = "error";
    private static final String REASON_STOP = "stop";

    private final Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;

    private String capturedSessionId;
    private boolean streamStarted;
    private boolean streamEnded;
    private boolean sessionIdEmitted;
    private boolean hasError;
    private final StringBuilder errorDiagnostic = new StringBuilder();
    private final StringBuilder assistantContent = new StringBuilder();

    public OpenCodeCliStreamParser(CliSessionCallback callback) {
        this.callback = callback;
    }

    /** 本次运行捕获到的 session id(从事件流顶层 sessionID 提取),供会话层缓存与续接。 */
    public String capturedSessionId() {
        return capturedSessionId;
    }

    /** 累积的 assistant 文本(供会话层 onComplete 的 finalResult)。 */
    public String accumulatedText() {
        return assistantContent.toString();
    }

    public boolean hasError() {
        return hasError;
    }

    /** 本次运行是否已收到 step_finish(reason=stop)(会话层据此判断是否需补发 stream_end)。 */
    public boolean streamEnded() {
        return streamEnded;
    }

    public String errorDiagnostic() {
        return errorDiagnostic.toString();
    }

    /** B13 失效重试时调用:重试视为新一轮,首轮 step_start 重新触发 stream_start/session_id,清空累积。 */
    void resetForRetry() {
        streamStarted = false;
        streamEnded = false;
        sessionIdEmitted = false;
        hasError = false;
        errorDiagnostic.setLength(0);
        assistantContent.setLength(0);
    }

    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return;
        }
        JsonObject event;
        try {
            event = gson.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[OpenCodeParser] non-JSON line ignored: " + trimmed);
            return;
        }
        if (event == null) {
            return;
        }
        String type = getString(event, "type");
        if (type == null) {
            return;
        }
        // sessionID 顶层存在则尽早捕获(每个事件都带),供首轮 step_start 下发
        captureSessionId(event);

        switch (type) {
            case EVENT_STEP_START -> handleStepStart();
            case EVENT_TEXT -> handleText(event);
            case EVENT_TOOL_USE -> handleToolUse(event);
            case EVENT_STEP_FINISH -> handleStepFinish(event);
            case EVENT_ERROR -> handleError(event);
            default -> {
                // 忽略未知事件类型(message_start/step_reasoning 等占位事件)
            }
        }
    }

    private void captureSessionId(JsonObject event) {
        if (capturedSessionId != null) {
            return;
        }
        String id = getString(event, "sessionID");
        if (id == null && event.has("part") && event.get("part").isJsonObject()) {
            id = getString(event.getAsJsonObject("part"), "sessionID");
        }
        if (id != null && !id.isBlank()) {
            capturedSessionId = id;
        }
    }

    private void handleStepStart() {
        if (!streamStarted) {
            streamStarted = true;
            callback.onMessage(CliConstants.MSG_STREAM_START, "");
        }
        if (!sessionIdEmitted && capturedSessionId != null) {
            sessionIdEmitted = true;
            callback.onMessage(CliConstants.MSG_SESSION_ID, capturedSessionId);
        }
    }

    private void handleText(JsonObject event) {
        JsonObject part = asObject(event, "part");
        if (part == null) {
            return;
        }
        String text = getString(part, "text");
        if (text != null && !text.isEmpty()) {
            assistantContent.append(text);
            callback.onMessage(CliConstants.MSG_CONTENT_DELTA, text);
        }
    }

    private void handleToolUse(JsonObject event) {
        JsonObject part = asObject(event, "part");
        if (part == null) {
            return;
        }
        String tool = firstNonBlank(getString(part, "tool"), getString(part, "name"), "unknown");
        String callId = firstNonBlank(getString(part, "callID"), getString(part, "id"), "call_" + System.nanoTime());
        JsonObject state = asObject(part, "state");
        JsonObject input = asObject(state, "input");
        String output = getString(state, "output");
        boolean isError = isErrorState(state);

        // tool_use 原始块(Anthropic schema,CodexMessageHandler.handleToolUse 经 wrapAsAssistantRaw 包装)
        JsonObject toolUseBlock = new JsonObject();
        toolUseBlock.addProperty("type", "tool_use");
        toolUseBlock.addProperty("id", callId);
        toolUseBlock.addProperty("name", tool);
        toolUseBlock.add("input", input);
        callback.onMessage(CommonConstants.MSG_TYPE_TOOL_USE, toolUseBlock.toString());

        // tool_result 原始块(CodexMessageHandler.handleToolResult 经 wrapAsUserRaw 包装)
        JsonObject toolResultBlock = new JsonObject();
        toolResultBlock.addProperty("type", "tool_result");
        toolResultBlock.addProperty("tool_use_id", callId);
        toolResultBlock.addProperty("is_error", isError);
        toolResultBlock.addProperty("content", output != null ? output : "(running)");
        callback.onMessage(CommonConstants.MSG_TYPE_TOOL_RESULT, toolResultBlock.toString());
    }

    private void handleStepFinish(JsonObject event) {
        JsonObject part = asObject(event, "part");
        if (part == null) {
            return;
        }
        // usage 经 MSG_RESULT 下发(CodexMessageHandler 无 MSG_USAGE case,usage 必须经 MSG_RESULT)
        JsonObject tokens = asObject(part, "tokens");
        if (tokens != null) {
            JsonObject usage = buildUsage(tokens);
            JsonObject resultWrapper = new JsonObject();
            resultWrapper.add("usage", usage);
            callback.onMessage(CliConstants.MSG_RESULT, resultWrapper.toString());
        }
        if (REASON_STOP.equals(getString(part, "reason"))) {
            streamEnded = true;
            callback.onMessage(CliConstants.MSG_STREAM_END, "");
            callback.onMessage(CliConstants.MSG_MESSAGE_END, "");
        }
    }

    private void handleError(JsonObject event) {
        hasError = true;
        String message = null;
        JsonObject err = asObject(event, "error");
        if (err != null) {
            JsonObject data = asObject(err, "data");
            if (data != null) {
                message = getString(data, "message");
            }
            if (message == null) {
                message = getString(err, "message");
            }
        }
        if (message == null) {
            message = getString(event, "message");
        }
        if (message == null) {
            message = event.toString();
        }
        if (errorDiagnostic.length() > 0) {
            errorDiagnostic.append('\n');
        }
        errorDiagnostic.append(message);
    }

    private JsonObject buildUsage(JsonObject tokens) {
        JsonObject cache = asObject(tokens, "cache");
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", getInt(tokens, "input"));
        usage.addProperty("output_tokens", getInt(tokens, "output"));
        usage.addProperty("cache_read_input_tokens", getInt(cache, "read"));
        usage.addProperty("cache_creation_input_tokens", getInt(cache, "write"));
        return usage;
    }

    private static boolean isErrorState(JsonObject state) {
        if (state == null) {
            return false;
        }
        String status = getString(state, "status");
        if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            return true;
        }
        if (state.has("error") && !state.get("error").isJsonNull()) {
            return true;
        }
        JsonObject metadata = asObject(state, "metadata");
        if (metadata != null && metadata.has("exit") && metadata.get("exit").isJsonPrimitive()) {
            try {
                return metadata.get("exit").getAsInt() != 0;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static JsonObject asObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
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

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
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
}
