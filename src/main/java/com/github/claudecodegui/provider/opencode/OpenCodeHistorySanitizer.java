package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.util.UserMessageSanitizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 剥除 OpenCode 历史/回放消息中 IDE 拼接到 user 文本的上下文(## Project Modules、
 * ## Opened Files Context、## IDE Context 等)。
 * <p>
 * OpenCode 把 IDE 拼接了上下文的用户文本原样存 SQLite,回放时若不清理会把这些元数据
 * 当作聊天内容渲染(问题3)。仅清理 user 消息;assistant(含 thinking/tool_use)不动。
 * <p>
 * 抽为 provider.opencode 包内 public 纯函数,供两条消费路径复用:
 * <ul>
 *   <li>回放路径:SessionProviderRouter → OpenCodeProviderAdapter → OpenCodeSDKBridge.getSessionMessages</li>
 *   <li>历史面板:OpenCodeHistoryProviderAdapter.loadMessages → OpenCodeSDKBridge.getSessionMessages</li>
 * </ul>
 * bridge.getSessionMessages 是共同 choke point,在那里 sanitize 即同时覆盖两路径(历史面板
 * 侧的再次 sanitize 幂等无害)。消息形状(type/raw.role/raw.content[])对应 ai-bridge
 * toFrontendMessage 同时写入的 type 与 raw.role。
 */
public final class OpenCodeHistorySanitizer {

    private OpenCodeHistorySanitizer() {
    }

    /**
     * 就地清理消息列表中所有 user 消息的拼接上下文。null/空安全,跳过 null 条目。
     */
    public static void sanitize(List<JsonObject> messages) {
        if (messages == null) {
            return;
        }
        for (JsonObject message : messages) {
            if (message == null) {
                continue;
            }
            if (isOpenCodeUserMessage(message)) {
                sanitizeUserMessageContent(message);
            }
        }
    }

    private static boolean isOpenCodeUserMessage(JsonObject message) {
        String type = message.has("type") && message.get("type").isJsonPrimitive()
                ? message.get("type").getAsString() : null;
        if ("user".equals(type)) {
            return true;
        }
        // 兜底:看 raw.role(ai-bridge toFrontendMessage 同时写 type 与 raw.role)
        if (message.has("raw") && message.get("raw").isJsonObject()) {
            JsonObject raw = message.getAsJsonObject("raw");
            return raw.has("role") && "user".equals(raw.get("role").getAsString());
        }
        return false;
    }

    private static void sanitizeUserMessageContent(JsonObject message) {
        // 顶层 content(字符串)
        if (message.has("content") && message.get("content").isJsonPrimitive()) {
            String sanitized = UserMessageSanitizer.sanitizeUserFacingText(message.get("content").getAsString());
            message.addProperty("content", sanitized);
        }
        // raw.content(JsonArray of blocks)中 type==text 块的 text 同步清理
        // (前端读 raw.content 渲染时也拿到干净文本)
        if (message.has("raw") && message.get("raw").isJsonObject()) {
            JsonObject raw = message.getAsJsonObject("raw");
            if (raw.has("content") && raw.get("content").isJsonArray()) {
                JsonArray blocks = raw.getAsJsonArray("content");
                for (JsonElement block : blocks) {
                    if (block == null || !block.isJsonObject()) {
                        continue;
                    }
                    JsonObject blockObj = block.getAsJsonObject();
                    if (blockObj.has("type") && "text".equals(blockObj.get("type").getAsString())
                            && blockObj.has("text") && blockObj.get("text").isJsonPrimitive()) {
                        blockObj.addProperty("text",
                                UserMessageSanitizer.sanitizeUserFacingText(blockObj.get("text").getAsString()));
                    }
                }
            }
        }
    }
}
