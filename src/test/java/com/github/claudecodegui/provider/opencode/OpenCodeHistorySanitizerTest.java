package com.github.claudecodegui.provider.opencode;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * OpenCodeHistorySanitizer:回放/历史路径后处理 OpenCode 消息,剥除 IDE 拼接到 user 文本的
 * 上下文(## Project Modules、## Opened Files Context 等)。
 * <p>
 * 问题3 根因:OpenCode 消息提取是回放路径与历史面板路径的共同 choke point,此前回放路径未清理
 * → 回放把 "This project contains multiple modules:" 这类提示词当正文渲染。sanitize 抽为
 * provider.opencode 包内纯函数,供回放与历史 adapter 复用。
 */
public class OpenCodeHistorySanitizerTest {

    @Test
    public void stripsAppendedProjectModulesContextFromUserMessage() {
        JsonObject userMsg = newUserMessage("帮我看下这个 bug\n\n## Project Modules\n\nThis project contains multiple modules:\n- a");

        List<JsonObject> messages = new ArrayList<>();
        messages.add(userMsg);

        OpenCodeHistorySanitizer.sanitize(messages);

        // 顶层 content 与 raw.content[0].text 都应截断为真实正文 "帮我看下这个 bug"
        assertEquals("帮我看下这个 bug", userMsg.get("content").getAsString());
        assertEquals("帮我看下这个 bug",
                userMsg.getAsJsonObject("raw").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void leavesAssistantMessagesUntouched() {
        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("type", "assistant");
        assistantMsg.addProperty("content", "好的,我来帮你分析。");

        List<JsonObject> messages = new ArrayList<>();
        messages.add(assistantMsg);

        OpenCodeHistorySanitizer.sanitize(messages);

        assertEquals("好的,我来帮你分析。", assistantMsg.get("content").getAsString());
    }

    @Test
    public void handlesUserMessageWithoutRawContent() {
        // 防御:仅顶层 content、无 raw.content 数组的 user 消息也能清理,不抛异常
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("type", "user");
        userMsg.addProperty("content", "你好\n\n## Opened Files Context\n\n- src/Main.java");

        List<JsonObject> messages = new ArrayList<>();
        messages.add(userMsg);

        OpenCodeHistorySanitizer.sanitize(messages);

        assertEquals("你好", userMsg.get("content").getAsString());
    }

    @Test
    public void detectsUserMessageByRawRoleFallback() {
        // ai-bridge toFrontendMessage 同时写 type 与 raw.role;type 缺失时按 raw.role=user 兜底识别
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("content", "正文\n\n## IDE Context\n\n- a");
        JsonArray blocks = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", "正文\n\n## IDE Context\n\n- a");
        blocks.add(textBlock);
        JsonObject raw = new JsonObject();
        raw.addProperty("role", "user");
        raw.add("content", blocks);
        userMsg.add("raw", raw);

        List<JsonObject> messages = new ArrayList<>();
        messages.add(userMsg);

        OpenCodeHistorySanitizer.sanitize(messages);

        assertEquals("正文", userMsg.get("content").getAsString());
    }

    @Test
    public void isNullSafeAndIgnoresNullEntries() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(null);
        messages.add(newUserMessage("正文\n\n## Workspace Context\n\nx"));

        OpenCodeHistorySanitizer.sanitize(messages); // 不抛 NPE

        assertEquals("正文", messages.get(1).get("content").getAsString());
    }

    private static JsonObject newUserMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "user");
        msg.addProperty("content", content);
        JsonArray blocks = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", content);
        blocks.add(textBlock);
        JsonObject raw = new JsonObject();
        raw.addProperty("role", "user");
        raw.add("content", blocks);
        msg.add("raw", raw);
        return msg;
    }
}
