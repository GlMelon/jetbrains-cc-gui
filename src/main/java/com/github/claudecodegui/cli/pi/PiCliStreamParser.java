package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Pi CLI JSON 事件流模式解析器({@code pi --print --mode json},官方 AgentEvent schema)。
 * <p>
 * 事件映射(见 ai-bridge/services/pi/message-service.js 同源移植):
 * <ul>
 *   <li>{@code {"type":"session","id":..}} → SESSION_ID(仅首轮一次);</li>
 *   <li>{@code message_update.assistantMessageEvent} delta-only:
 *       {@code text_delta} → MSG_CONTENT_DELTA;{@code thinking_delta} → 思考激活 +
 *       MSG_THINKING_DELTA(pi thinking 为一等公民);</li>
 *   <li>{@code tool_execution_start}(toolCallId/toolName/args) → tool_use(pending);</li>
 *   <li>{@code tool_execution_end}(toolCallId/result/isError) → tool_result(start/end 天然配对);</li>
 *   <li>{@code message_end.message.usage} → MSG_USAGE;</li>
 *   <li>{@code turn_end}/{@code agent_end}/{@code compaction_*} 等忽略。</li>
 * </ul>
 * 每次发送构造新实例(非线程安全)。
 */
public class PiCliStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(PiCliStreamParser.class);
    private static final String EVENT_SESSION = "session";
    private static final String EVENT_MESSAGE_UPDATE = "message_update";
    private static final String EVENT_TOOL_START = "tool_execution_start";
    private static final String EVENT_TOOL_END = "tool_execution_end";
    private static final String EVENT_MESSAGE_END = "message_end";

    private final com.google.gson.Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean sessionIdEmitted;
    private boolean hasError;
    private boolean receivedAnyEvent;
    private boolean thinkingActivated;
    private final StringBuilder assistantContent = new StringBuilder();
    private final StringBuilder errorDiagnostic = new StringBuilder();

    public PiCliStreamParser(CliSessionCallback callback) {
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
        // pi --mode json 无显式 turn 流结束事件(turn_end 不代表最终轮):
        // CLI 退出即结束,由会话层对「有事件缺收尾」补发 STREAM_END。
        return false;
    }

    @Override
    public String errorDiagnostic() {
        return errorDiagnostic.toString();
    }

    /**
     * MCP 连接失败降级:pi 故意不内置 MCP(设计原则),无此故障面;非 JSON 噪声走 diagnostic。
     * (覆写仅为契约自文档化——接口默认即 false,此处不覆写,由 dispatchLine 直接收集。)
     */

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
            LOG.debug("[PiParser] non-JSON line ignored: " + preview(trimmed));
            return;
        }
        if (event == null || !event.has("type") || !event.get("type").isJsonPrimitive()) {
            return;
        }
        receivedAnyEvent = true;
        switch (event.get("type").getAsString()) {
            case EVENT_SESSION -> handleSession(event);
            case EVENT_MESSAGE_UPDATE -> handleMessageUpdate(event);
            case EVENT_TOOL_START -> handleToolStart(event);
            case EVENT_TOOL_END -> handleToolEnd(event);
            case EVENT_MESSAGE_END -> handleUsageFromMessageEnd(event);
            default -> {
                // 其余事件(turn_start/turn_end/agent_*/compaction_*)忽略。
            }
        }
    }

    private void handleSession(JsonObject event) {
        String id = primitiveString(event.get("id"));
        if (id != null && !id.isBlank() && capturedSessionId == null) {
            capturedSessionId = id.trim();
            if (!sessionIdEmitted) {
                sessionIdEmitted = true;
                emitter.sessionId(capturedSessionId);
            }
        }
    }

    /** message_update 的 assistantMessageEvent 为 delta-only(text/thinking),contentIndex 定位。 */
    private void handleMessageUpdate(JsonObject event) {
        JsonObject update = asObject(event, "assistantMessageEvent");
        if (update == null || !update.has("type") || !update.get("type").isJsonPrimitive()) {
            return;
        }
        String type = update.get("type").getAsString();
        switch (type) {
            case "text_delta" -> {
                String delta = primitiveString(update.get("delta"));
                if (delta != null && !delta.isEmpty()) {
                    emitter.contentDelta(assistantContent, delta);
                }
            }
            case "thinking_delta" -> {
                if (!thinkingActivated) {
                    thinkingActivated = true;
                    emitter.thinkingStart();
                }
                String delta = primitiveString(update.get("delta"));
                if (delta != null && !delta.isEmpty()) {
                    emitter.thinkingDelta(delta);
                }
            }
            default -> {
                // tool_call 相关 delta(toolCallArgumentsDelta 等):参数在 start 事件取全量,忽略增量。
            }
        }
    }

    private void handleToolStart(JsonObject event) {
        String callId = firstNonBlank(primitiveString(event.get("toolCallId")), "");
        String name = firstNonBlank(primitiveString(event.get("toolName")), "unknown");
        JsonObject args = asObject(event, "args");
        if (args == null) {
            args = new JsonObject();
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", callId.isEmpty() ? "pi-tool-" + System.nanoTime() : callId);
        block.addProperty("name", name);
        block.add("input", args);
        emitter.toolUse(block);
    }

    private void handleToolEnd(JsonObject event) {
        String callId = primitiveString(event.get("toolCallId"));
        if (callId == null || callId.isBlank()) {
            return;
        }
        boolean isError = event.has("isError") && !event.get("isError").isJsonNull()
                && event.get("isError").getAsBoolean();
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", callId);
        block.addProperty("is_error", isError);
        block.addProperty("content", extractResultText(event.get("result")));
        emitter.toolResult(block);
    }

    /** message_end.message(role=assistant).usage → MSG_USAGE 归一 Claude schema 字段名不变透传。 */
    private void handleUsageFromMessageEnd(JsonObject event) {
        try {
            JsonObject message = asObject(event, "message");
            if (message == null || !"assistant".equals(primitiveString(message.get("role")))) {
                return;
            }
            JsonObject usage = asObject(message, "usage");
            if (usage != null) {
                emitter.usage(gson.toJson(usage));
            }
        } catch (Exception e) {
            LOG.debug("[PiParser] usage extraction failed: " + e.getMessage());
        }
    }

    /** result 结构化内容转文本(数组拼接 text 片段,否则 JSON 序列化;对称 JS extractToolResultText)。 */
    private static String extractResultText(JsonElement resultEl) {
        if (resultEl == null || resultEl.isJsonNull()) {
            return "";
        }
        if (resultEl.isJsonPrimitive() && resultEl.getAsJsonPrimitive().isString()) {
            return resultEl.getAsString();
        }
        if (resultEl.isJsonObject()) {
            JsonObject obj = resultEl.getAsJsonObject();
            JsonElement content = obj.get("content");
            if (content != null && content.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (var el : content.getAsJsonArray()) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        sb.append(el.getAsString());
                    } else if (el.isJsonObject()) {
                        String piece = primitiveString(el.getAsJsonObject().get("text"));
                        if (piece != null) {
                            sb.append(piece);
                        }
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        }
        try {
            return GsonHolder.GSON.toJson(resultEl);
        } catch (Exception e) {
            return String.valueOf(resultEl);
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

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String preview(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
