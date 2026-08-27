package com.github.claudecodegui.cli.kimi;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliSectionEmitter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Kimi Code CLI stream-json 事件流解析器(对齐 kimi -p --output-format stream-json,官方 schema)。
 * <p>
 * 事件为<b>快照式</b>消息行(kimi 常重复下发增长前缀的 assistant 快照):
 * <ul>
 *   <li>{@code {"role":"assistant","content":"..."}} → 快照合并取增量 → MSG_CONTENT_DELTA;</li>
 *   <li>{@code {"role":"assistant","tool_calls":[...]}} → 逐个发 tool_use
 *       (key = id|args 去重,防快照重放);</li>
 *   <li>{@code {"role":"tool","tool_call_id":..,"content":..}} → tool_result;</li>
 *   <li>{@code {"role":"meta","type":"session.resume_hint","session_id":"session_.."}} → SESSION_ID。</li>
 * </ul>
 * 官方限制:thinking 不写入 JSONL(走 stderr transcript 文本)——思考区暂不支持(有意差异,总则六记录);
 * usage 无独立事件。流结束标记由 CLI EOF 隐含,基类对「有事件缺收尾」补发 STREAM_END。
 */
public class KimiCliStreamParser implements CliStreamParser {

    private static final Logger LOG = Logger.getInstance(KimiCliStreamParser.class);
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String ROLE_META = "meta";

    private final com.google.gson.Gson gson = GsonHolder.GSON;
    private final CliSessionCallback callback;
    private final CliSectionEmitter emitter;

    private String capturedSessionId;
    private boolean sessionIdEmitted;
    private boolean hasError;
    private boolean receivedAnyEvent;
    /** assistant 快照累积(增量去重依据)。 */
    private final StringBuilder assistantContent = new StringBuilder();
    private final StringBuilder errorDiagnostic = new StringBuilder();
    /** 已发出的 tool_call 稳定 key(id|argsJson),防快照重复。 */
    private final java.util.Set<String> seenToolCallKeys = new java.util.HashSet<>();

    public KimiCliStreamParser(CliSessionCallback callback) {
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
        // stream-json 无显式结束事件:CLI 退出即流结束,由会话层补发收尾。
        return false;
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
        JsonObject value;
        try {
            value = gson.fromJson(trimmed, JsonObject.class);
        } catch (Exception e) {
            LOG.debug("[KimiParser] non-JSON line ignored: " + preview(trimmed));
            return;
        }
        if (value == null || !value.has("role") || !value.get("role").isJsonPrimitive()) {
            return;
        }
        receivedAnyEvent = true;
        switch (value.get("role").getAsString()) {
            case ROLE_ASSISTANT -> handleAssistant(value);
            case ROLE_TOOL -> handleToolResult(value);
            case ROLE_META -> handleMeta(value);
            default -> {
                // 其他 role 忽略
            }
        }
    }

    private void handleAssistant(JsonObject value) {
        JsonArray calls = value.has("tool_calls") && value.get("tool_calls").isJsonArray()
                ? value.getAsJsonArray("tool_calls") : null;
        if (calls != null && !calls.isEmpty()) {
            for (int i = 0; i < calls.size(); i++) {
                if (!calls.get(i).isJsonObject()) {
                    continue;
                }
                emitToolCall(calls.get(i).getAsJsonObject());
            }
        }
        String text = extractAssistantText(value.get("content"));
        if (text == null || text.isEmpty()) {
            return;
        }
        String delta = mergeSnapshotDelta(text);
        if (!delta.isEmpty()) {
            emitter.contentDelta(assistantContent, delta);
        }
    }

    private void emitToolCall(JsonObject call) {
        String id = primitiveString(call.get("id"));
        JsonObject fn = call.has("function") && call.get("function").isJsonObject()
                ? call.getAsJsonObject("function") : null;
        String name = firstNonBlank(
                fn != null ? primitiveString(fn.get("name")) : null,
                primitiveString(call.get("name")),
                "tool");
        JsonElement argsRaw = fn != null && fn.has("arguments")
                ? fn.get("arguments") : call.get("arguments");
        JsonObject input = parseArguments(argsRaw);

        // id 缺失时以 name|args 为合成标识(kimi 有时不回传 id)
        String stableId = id != null && !id.isBlank() ? id : ("kimi-tool-" + name);
        String key = stableId + "|" + gson.toJson(input);
        if (!seenToolCallKeys.add(key)) {
            return;
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", stableId);
        block.addProperty("name", name);
        block.add("input", input);
        emitter.toolUse(block);
    }

    private void handleToolResult(JsonObject value) {
        String toolCallId = primitiveString(value.get("tool_call_id"));
        if (toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        JsonElement contentEl = value.get("content");
        String content;
        if (contentEl == null || contentEl.isJsonNull()) {
            content = "";
        } else if (contentEl.isJsonPrimitive() && contentEl.getAsJsonPrimitive().isString()) {
            content = contentEl.getAsString();
        } else {
            try {
                content = gson.toJson(contentEl);
            } catch (Exception e) {
                content = String.valueOf(contentEl);
            }
        }
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("tool_use_id", toolCallId.trim());
        block.addProperty("is_error", false);
        block.addProperty("content", content);
        emitter.toolResult(block);
    }

    private void handleMeta(JsonObject value) {
        String metaType = primitiveString(value.get("type"));
        if (!"session.resume_hint".equals(metaType)) {
            return;
        }
        String sessionId = primitiveString(value.get("session_id"));
        if (sessionId != null && !sessionId.isBlank() && capturedSessionId == null) {
            capturedSessionId = sessionId.trim();
            if (!sessionIdEmitted) {
                sessionIdEmitted = true;
                emitter.sessionId(capturedSessionId);
            }
        }
    }

    /**
     * 快照合并(对称 JS mergeAssistantTextSnapshot):增长前缀→前缀差;完全相同→空;
     * 非前缀替换→换行分隔整体下发。返回空串表示无新增。
     */
    String mergeSnapshotDelta(String incoming) {
        String accumulated = assistantContent.toString();
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        if (accumulated.isEmpty()) {
            return incoming;
        }
        if (incoming.equals(accumulated)) {
            return "";
        }
        if (incoming.startsWith(accumulated)) {
            return incoming.substring(accumulated.length());
        }
        if (accumulated.startsWith(incoming)) {
            return "";
        }
        return "\n" + incoming;
    }

    /** content 字段:字符串原样;数组拼接 string/text/content 片段(对称 JS extractAssistantText)。 */
    private static String extractAssistantText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive() && content.getAsJsonPrimitive().isString()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : content.getAsJsonArray()) {
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                    sb.append(el.getAsString());
                } else if (el.isJsonObject()) {
                    JsonObject part = el.getAsJsonObject();
                    String piece = firstNonBlank(primitiveString(part.get("text")),
                            primitiveString(part.get("content")));
                    if (piece != null) {
                        sb.append(piece);
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** arguments:对象透传;字符串尝试 JSON.parse;其余 {value}/{raw}(对称 JS parseToolArguments)。 */
    private JsonObject parseArguments(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return new JsonObject();
        }
        if (raw.isJsonObject()) {
            return raw.getAsJsonObject();
        }
        if (raw.isJsonPrimitive()) {
            String text = raw.getAsString().trim();
            if (text.isEmpty()) {
                return new JsonObject();
            }
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(text);
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
                JsonObject wrap = new JsonObject();
                wrap.add("value", parsed);
                return wrap;
            } catch (Exception e) {
                JsonObject wrap = new JsonObject();
                wrap.addProperty("raw", text);
                return wrap;
            }
        }
        JsonObject wrap = new JsonObject();
        wrap.add("value", raw);
        return wrap;
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
        return null;
    }

    private static String preview(String text) {
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
