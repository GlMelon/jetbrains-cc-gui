package com.github.claudecodegui.cli.opencode;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.github.claudecodegui.util.GsonHolder;

/**
 * OpenCode 事件 → 统一协议输出的纯函数映射(总则四·复用抽取)。
 * <p>
 * 从 {@link OpenCodeCliStreamParser}(one-shot {@code opencode run --format json} NDJSON)
 * 抽取,供 one-shot 解析器与 serve SSE 通道
 * ({@link com.github.claudecodegui.cli.opencode.serve.OpenCodeServeTurn})共同消费,
 * 两条通道对同一 opencode 事件 schema 的映射保持单点,禁止双写漂移。
 * <p>
 * 本类全部方法无状态:累积 / 去重所需的调用方状态(previous 文本等)由调用方持有。
 */
public final class OpenCodeEventMapper {

    private static final Gson GSON = GsonHolder.GSON;

    private OpenCodeEventMapper() {
    }

    /**
     * 累积式文本的增量去重:新文本以旧文本为前缀时取差分为增量;新文本不以旧为前缀
     * (非累积 / 重置)则整体下发。返回 null 表示无新增(空或与旧值相同,不发 delta)。
     * 对称 ai-bridge/services/opencode/event-mapper.js 的 delta()。
     * one-shot 用于 reasoning 累积文本;serve 用于 part.updated 的全量 part.text。
     */
    public static String deltaOf(String previous, String next) {
        String oldText = previous == null ? "" : previous;
        String newText = next == null ? "" : next;
        if (newText.isEmpty() || newText.equals(oldText)) {
            return null;
        }
        if (oldText.isEmpty()) {
            return newText;
        }
        if (newText.startsWith(oldText)) {
            return newText.substring(oldText.length());
        }
        return newText;
    }

    /**
     * tokens 归一为 Anthropic usage schema(CodexMessageHandler 无 MSG_USAGE case,
     * usage 必须经 MSG_RESULT 走 handleResultMessage)。one-shot 取自 step_finish part.tokens;
     * serve 取自 message.updated 的 info.tokens,两者同构 {input, output, reasoning, cache:{read, write}}。
     */
    public static JsonObject buildUsage(JsonObject tokens) {
        JsonObject cache = asObject(tokens, "cache");
        JsonObject usage = new JsonObject();
        usage.addProperty("input_tokens", getInt(tokens, "input"));
        usage.addProperty("output_tokens", getInt(tokens, "output"));
        usage.addProperty("cache_read_input_tokens", getInt(cache, "read"));
        usage.addProperty("cache_creation_input_tokens", getInt(cache, "write"));
        return usage;
    }

    /**
     * tool_use 原始块(Anthropic schema,CodexMessageHandler.handleToolUse 经
     * wrapAsAssistantRaw 包装)。one-shot 与 serve 的 tool part 同构
     * ({tool|name, callID|id, state:{input}})。
     */
    public static JsonObject buildToolUseBlock(JsonObject part) {
        String tool = firstNonBlank(getString(part, "tool"), getString(part, "name"), "unknown");
        String callId = firstNonBlank(getString(part, "callID"), getString(part, "id"),
                "call_" + System.nanoTime());
        JsonObject state = asObject(part, "state");
        JsonObject input = asObject(state, "input");

        JsonObject toolUseBlock = new JsonObject();
        toolUseBlock.addProperty("type", "tool_use");
        toolUseBlock.addProperty("id", callId);
        toolUseBlock.addProperty("name", tool);
        toolUseBlock.add("input", input);
        return toolUseBlock;
    }

    /**
     * tool_result 原始块(CodexMessageHandler.handleToolResult 经 wrapAsUserRaw 包装)。
     * 与 {@link #buildToolUseBlock} 的 callId 推导保持一致(同一 part 两次调用产出配对的 id)。
     */
    public static JsonObject buildToolResultBlock(JsonObject part) {
        String callId = firstNonBlank(getString(part, "callID"), getString(part, "id"),
                "call_" + System.nanoTime());
        JsonObject state = asObject(part, "state");
        String output = getString(state, "output");
        boolean isError = isErrorState(state);

        JsonObject toolResultBlock = new JsonObject();
        toolResultBlock.addProperty("type", "tool_result");
        toolResultBlock.addProperty("tool_use_id", callId);
        toolResultBlock.addProperty("is_error", isError);
        toolResultBlock.addProperty("content", output != null ? output : "(running)");
        return toolResultBlock;
    }

    /**
     * 从错误事件提取错误消息:error.data.message → error.message → event.message → 原始 JSON。
     * one-shot 取自 type=error 事件;serve 取自 session.error 事件(properties.error 同构)。
     */
    public static String extractErrorMessage(JsonObject event) {
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
            message = event != null ? GSON.toJson(event) : null;
        }
        return message;
    }

    /** state 是否处于错误态(status=error/failed、有 error 字段、或 metadata.exit != 0)。 */
    public static boolean isErrorState(JsonObject state) {
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

    // ── JSON 取值辅助(原 parser 私有,提取后两通道共用) ──────────────────────────

    public static JsonObject asObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    public static String firstNonBlank(String... values) {
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

    public static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    public static int getInt(JsonObject obj, String key) {
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
