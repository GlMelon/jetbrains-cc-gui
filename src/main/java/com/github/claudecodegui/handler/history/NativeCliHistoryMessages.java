package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 原生 CLI provider(grok/kimi/pi)历史行 → 前端 Claude 兼容消息的共享构造器。
 * <p>
 * 目标形状(与 CodexMessageConverter.convertCodexMessageToFrontend 一致):
 * <pre>
 *   {"type":"user"|"assistant","content":"&lt;text&gt;"[,"raw":{"role":...,"content":[...]}}]}
 * </pre>
 * 块({@code text}/{@code tool_use}/{@code tool_result}/{@code thinking})挂 {@code raw.content}
 * 数组——前端 normalizeBlocks、MessageParser.hasToolResult 等全链路只读这个位置;
 * ⚠️ 不要放顶层 contentBlocks:该字段无任何消费者,历史回显会静默丢失思考/工具区
 * (2026-08-28 kimi 实测教训)。供各 HistoryReader 复用,避免每家手写一套块拼装。
 */
public final class NativeCliHistoryMessages {

    private static final int TITLE_PREVIEW_CHARS = 60;

    private NativeCliHistoryMessages() {
    }

    /** 纯文本用户消息;空文本返回 null(空行无意义)。 */
    public static JsonObject userText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonObject front = new JsonObject();
        front.addProperty("type", "user");
        front.addProperty("content", text);
        return front;
    }

    /**
     * assistant 消息:文本与块至少要有一样,否则返回 null。
     */
    public static JsonObject assistant(String text, List<JsonObject> contentBlocks) {
        boolean hasText = text != null && !text.isEmpty();
        if (!hasText && (contentBlocks == null || contentBlocks.isEmpty())) {
            return null;
        }
        JsonObject front = new JsonObject();
        front.addProperty("type", "assistant");
        front.addProperty("content", text == null ? "" : text);
        if (contentBlocks != null && !contentBlocks.isEmpty()) {
            JsonArray arr = new JsonArray();
            contentBlocks.forEach(arr::add);
            front.add("raw", rawEnvelope("assistant", arr));
        }
        return front;
    }

    /** 工具结果承载消息(tool_result 只能挂在 user 消息的 raw.content 块上)。 */
    public static JsonObject toolResultMessage(String toolUseId, String content, boolean isError) {
        JsonObject front = new JsonObject();
        front.addProperty("type", "user");
        front.addProperty("content", "");
        JsonArray arr = new JsonArray();
        arr.add(toolResultBlock(toolUseId, content == null ? "" : content, isError));
        front.add("raw", rawEnvelope("user", arr));
        return front;
    }

    /**
     * Claude 兼容 raw 信封:raw.content=块数组(前端 normalizeBlocks、MessageParser.hasToolResult
     * 等全链路既定读取位置)。自拼消息的 HistoryReader(如 grok)也经此包装,保证形状单一。
     */
    public static JsonObject rawEnvelope(String role, JsonArray blocks) {
        JsonObject raw = new JsonObject();
        raw.addProperty("role", role);
        raw.add("content", blocks);
        return raw;
    }

    public static JsonObject toolUseBlock(String id, String name, JsonObject input) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_USE);
        if (id != null && !id.isBlank()) {
            block.addProperty(CommonConstants.JSON_KEY_ID, id);
        }
        if (name != null && !name.isBlank()) {
            block.addProperty(CommonConstants.JSON_KEY_NAME, name);
        }
        block.add(CommonConstants.JSON_KEY_INPUT, input == null ? new JsonObject() : input);
        return block;
    }

    public static JsonObject toolResultBlock(String toolUseId, String content, boolean isError) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TOOL_RESULT);
        if (toolUseId != null && !toolUseId.isBlank()) {
            block.addProperty(CommonConstants.JSON_KEY_TOOL_USE_ID, toolUseId);
        }
        block.addProperty(CommonConstants.JSON_KEY_IS_ERROR, isError);
        block.addProperty(CommonConstants.JSON_KEY_CONTENT, content == null ? "" : content);
        return block;
    }

    public static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_TEXT);
        block.addProperty(CommonConstants.JSON_KEY_TEXT, text);
        return block;
    }

    /** thinking wire 块(pi thinking content → Claude 兼容形状)。 */
    public static JsonObject thinkingBlock(String thinking) {
        JsonObject block = new JsonObject();
        block.addProperty(CommonConstants.JSON_KEY_TYPE, CommonConstants.BLOCK_TYPE_THINKING);
        block.addProperty(CommonConstants.JSON_KEY_THINKING, thinking);
        return block;
    }

    // ── JSONL 字段容错提取 ─────────────────────────────────────────────────────

    public static String primitiveString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()
                || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    /** OpenAI function 包装形态:function.name。 */
    public static String functionNameOf(JsonObject call) {
        if (call.has("function") && call.get("function").isJsonObject()) {
            return primitiveString(call.getAsJsonObject("function"), "name");
        }
        return null;
    }

    /** arguments 归一为对象:对象透传;字符串尝试 parse;失败回退空对象。 */
    public static JsonObject argumentsObjectOf(JsonObject call) {
        JsonElement raw = call.has("arguments") ? call.get("arguments")
                : (call.has("function") && call.get("function").isJsonObject()
                        ? call.getAsJsonObject("function").get("arguments") : null);
        if (raw == null || raw.isJsonNull()) {
            return new JsonObject();
        }
        if (raw.isJsonObject()) {
            return raw.getAsJsonObject();
        }
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = com.google.gson.JsonParser.parseString(raw.getAsString());
                if (parsed != null && parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return new JsonObject();
    }

    public static JsonObject parseObject(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return GsonHolder.GSON.fromJson(line.trim(), JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String truncateTitle(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= TITLE_PREVIEW_CHARS ? trimmed
                : trimmed.substring(0, TITLE_PREVIEW_CHARS) + "…";
    }
}
