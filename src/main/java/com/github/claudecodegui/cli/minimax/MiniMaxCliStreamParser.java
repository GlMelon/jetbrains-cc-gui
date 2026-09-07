package com.github.claudecodegui.cli.minimax;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * MiniMax Code(mcode)stream-json 事件流解析器({@code minimax exec --output-format stream-json})。
 * <p>
 * 事件映射(同源移植自上游 ai-bridge/services/minimax/message-service.js,实测 mcode 0.2.x):
 * <ul>
 *   <li>{@code delta.thinking} → 思考激活 + MSG_THINKING_DELTA;{@code delta.content} → MSG_CONTENT_DELTA;</li>
 *   <li>{@code delta.toolCalls[]}(status 1=start / 2=done)→ tool_use / tool_result(id 去重,
 *       start/done 非严格配平时 done 补发 tool_use);</li>
 *   <li>{@code message}(role=assistant 带 usage)→ MSG_USAGE;安全网:整段未流过 delta 的
 *       message 一次性补发全量 content/thinking(非流式回退);</li>
 *   <li>{@code exec.result}(sessionId/status)→ SESSION_ID;status 非 "succeeded" 且整轮
 *       无任何输出 → 记错误诊断(进程随即被 isCompletionMarkerLine 钩子终止,错误不能静默);</li>
 *   <li>heartbeat / session-status / generic 等忽略。</li>
 * </ul>
 * 每次发送构造新实例(非线程安全)。
 */
public class MiniMaxCliStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(MiniMaxCliStreamParser.class);
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_MESSAGE = "message";
    private static final String EVENT_RESULT = "exec.result";
    private static final String STATUS_SUCCEEDED = "succeeded";

    private final Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean sessionIdEmitted;
    private boolean hasError;
    private boolean receivedAnyEvent;
    private boolean thinkingActivated;
    private final StringBuilder assistantContent = new StringBuilder();
    private final StringBuilder errorDiagnostic = new StringBuilder();
    private final Set<String> seenToolUseIds = new HashSet<>();
    private final Set<String> seenToolResultIds = new HashSet<>();
    private final Set<String> streamedContentMessageIds = new HashSet<>();
    private final Set<String> streamedThinkingMessageIds = new HashSet<>();

    public MiniMaxCliStreamParser(CliSessionCallback callback) {
        this.callback = callback;
        this.emitter = new CliSectionEmitter(callback::onMessage);
    }

    @Override
    public String capturedSessionId() {
        return capturedSessionId;
    }

    @Override
    public String accumulatedText() {
        return assistantContent.toString();
    }

    @Override
    public boolean hasError() {
        return hasError;
    }

    @Override
    public boolean receivedAnyEvent() {
        return receivedAnyEvent;
    }

    @Override
    public boolean streamEnded() {
        // exec.result 即终态行(其后 CLI 空转由会话层终止),视为流已收尾。
        return capturedSessionId != null || hasError;
    }

    @Override
    public String errorDiagnostic() {
        return errorDiagnostic.toString();
    }

    @Override
    public void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        JsonObject event;
        try {
            event = gson.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[MiniMaxParser] non-JSON line ignored: " + preview(trimmed));
            return;
        }
        if (event == null || !event.has("type") || !event.get("type").isJsonPrimitive()) {
            return;
        }
        receivedAnyEvent = true;
        switch (event.get("type").getAsString()) {
            case EVENT_DELTA -> handleDelta(event);
            case EVENT_MESSAGE -> handleMessage(event);
            case EVENT_RESULT -> handleResult(event);
            default -> {
                // heartbeat / session-status / generic 等 UI 噪声忽略。
            }
        }
    }

    private void handleDelta(JsonObject event) {
        String messageId = primitiveString(event.get("messageId"));
        String thinking = primitiveString(event.get("thinking"));
        if (thinking != null && !thinking.isEmpty()) {
            if (messageId != null) {
                streamedThinkingMessageIds.add(messageId);
            }
            if (!thinkingActivated) {
                thinkingActivated = true;
                emitter.thinkingStart();
            }
            emitter.thinkingDelta(thinking);
        }
        String content = primitiveString(event.get("content"));
        if (content != null && !content.isEmpty()) {
            if (messageId != null) {
                streamedContentMessageIds.add(messageId);
            }
            emitter.contentDelta(assistantContent, content);
        }
        JsonElement toolCalls = event.get("toolCalls");
        if (toolCalls == null || !toolCalls.isJsonArray()) {
            return;
        }
        for (JsonElement el : toolCalls.getAsJsonArray()) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject call = el.getAsJsonObject();
            String id = primitiveString(call.get("id"));
            String name = primitiveString(call.get("name"));
            if (name == null || name.isEmpty()) {
                name = "tool";
            }
            String toolCallId = id != null && !id.isEmpty() ? id : "minimax-tool-" + name;
            int status = call.has("status") && call.get("status").isJsonPrimitive()
                    && call.get("status").getAsJsonPrimitive().isNumber()
                    ? call.get("status").getAsInt() : -1;
            if (status == 1) {
                emitToolUseOnce(toolCallId, name, call.get("input"));
            } else if (status == 2) {
                emitToolUseOnce(toolCallId, name, call.get("input"));
                emitToolResultOnce(toolCallId, extractToolOutputText(call.get("output")));
            }
        }
    }

    private void emitToolUseOnce(String toolCallId, String name, JsonElement rawInput) {
        if (!seenToolUseIds.add(toolCallId)) {
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", toolCallId);
        block.addProperty("name", name);
        block.add("input", parseToolArguments(rawInput));
        emitter.toolUse(block);
    }

    private void emitToolResultOnce(String toolCallId, String output) {
        if (!seenToolResultIds.add(toolCallId)) {
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", toolCallId);
        block.addProperty("content", output);
        emitter.toolResult(block);
    }

    private void handleMessage(JsonObject event) {
        JsonObject message = asObject(event, "message");
        if (message == null || !"assistant".equals(primitiveString(message.get("role")))) {
            return;
        }
        JsonObject usage = asObject(message, "usage");
        if (usage != null) {
            emitter.usage(gson.toJson(usage));
        }
        // 安全网:finished message 未流过任何 delta 时一次性补发全量(非流式回退)。
        String msgId = primitiveString(message.get("id"));
        if (msgId == null || msgId.isEmpty()) {
            return;
        }
        String content = primitiveString(message.get("content"));
        if (content != null && !content.isEmpty() && streamedContentMessageIds.add(msgId)) {
            emitter.contentDelta(assistantContent, content);
        }
        String thinking = primitiveString(message.get("thinking"));
        if (thinking != null && !thinking.isEmpty() && streamedThinkingMessageIds.add(msgId)) {
            if (!thinkingActivated) {
                thinkingActivated = true;
                emitter.thinkingStart();
            }
            emitter.thinkingDelta(thinking);
        }
    }

    private void handleResult(JsonObject event) {
        String sessionId = primitiveString(event.get("sessionId"));
        if (sessionId != null && !sessionId.isBlank()) {
            capturedSessionId = sessionId.trim();
            if (!sessionIdEmitted) {
                sessionIdEmitted = true;
                emitter.sessionId(capturedSessionId);
            }
        }
        String status = primitiveString(event.get("status"));
        // 非显式 succeeded 一律计失败:结果行后流即终止,失败运行不能静默结束。
        boolean failed = status != null && !STATUS_SUCCEEDED.equalsIgnoreCase(status.trim());
        boolean nothingStreamed = streamedContentMessageIds.isEmpty()
                && streamedThinkingMessageIds.isEmpty() && seenToolUseIds.isEmpty();
        if (failed && nothingStreamed) {
            String errorMessage = primitiveString(event.get("error"));
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = primitiveString(event.get("message"));
            }
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = "MiniMax CLI run failed (status: " + status + ")";
            }
            hasError = true;
            errorDiagnostic.append(errorMessage.trim());
            callback.onError(errorMessage.trim());
        }
    }

    /** tool output 结构化内容转文本(对称上游 extractToolOutputText)。 */
    private static String extractToolOutputText(JsonElement output) {
        if (output == null || output.isJsonNull()) {
            return "";
        }
        if (output.isJsonPrimitive()) {
            return output.getAsJsonPrimitive().isString() ? output.getAsString() : output.toString();
        }
        if (output.isJsonObject()) {
            JsonElement content = output.getAsJsonObject().get("content");
            if (content != null && content.isJsonPrimitive()) {
                return content.getAsString();
            }
            if (content != null && content.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonPrimitive()) {
                        sb.append(part.getAsString());
                    } else if (part.isJsonObject()) {
                        String text = primitiveString(part.getAsJsonObject().get("text"));
                        if (text != null) {
                            sb.append(text);
                        }
                    }
                }
                return sb.toString();
            }
        }
        try {
            return GsonHolder.GSON.toJson(output);
        } catch (Exception e) {
            return String.valueOf(output);
        }
    }

    /** tool_call input 归一:对象直传;字符串尝试 JSON.parse,失败包 {raw};空 → {}。 */
    private static JsonElement parseToolArguments(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return new JsonObject();
        }
        if (raw.isJsonObject()) {
            return raw;
        }
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("value", raw.toString());
            return wrapper;
        }
        String trimmed = raw.getAsString().trim();
        if (trimmed.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = GsonHolder.GSON.fromJson(trimmed, JsonElement.class);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed;
            }
            JsonObject wrapper = new JsonObject();
            wrapper.add("value", parsed != null ? parsed : raw);
            return wrapper;
        } catch (Exception e) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("raw", trimmed);
            return wrapper;
        }
    }

    private static JsonObject asObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static String primitiveString(JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String preview(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
