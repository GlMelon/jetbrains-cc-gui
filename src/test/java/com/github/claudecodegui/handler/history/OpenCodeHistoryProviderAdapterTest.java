package com.github.claudecodegui.handler.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * OpenCodeHistoryProviderAdapter.loadSessionsJson 的归一化行为。
 * <p>
 * getSessionList 失败时返回 ""(bridge 未就绪/db 缺失/超时/子进程异常)。adapter 必须把空串归一化为
 * 合法空会话 JSON,避免 HistoryLoadService.enhanceHistoryWithFavorites → fromJson("")=null → 前端
 * JSON.parse("") 抛"解析历史数据失败"(对称 Codex/Claude reader 始终返回合法 JSON)。
 * <p>
 * 归一化抽为 package-private static 纯函数,无需构造 HandlerContext(具体类,Platform 依赖)。
 */
public class OpenCodeHistoryProviderAdapterTest {

    @Test
    public void normalizeSessionsJsonPassthroughValidJson() {
        String valid = "{\"success\":true,\"sessions\":[{\"sessionId\":\"s1\"}]}";
        assertEquals(valid, OpenCodeHistoryProviderAdapter.normalizeSessionsJson(valid));
    }

    @Test
    public void normalizeSessionsJsonBlankReturnsEmptySessionsContract() {
        assertEquals("{\"success\":true,\"sessions\":[]}",
                OpenCodeHistoryProviderAdapter.normalizeSessionsJson(""));
    }

    @Test
    public void normalizeSessionsJsonNullReturnsEmptySessionsContract() {
        assertEquals("{\"success\":true,\"sessions\":[]}",
                OpenCodeHistoryProviderAdapter.normalizeSessionsJson(null));
    }

    // deleteSession 把 ai-bridge archiveSession 返回的受影响行数映射为 HistoryDeleteResult。
    // OpenCode 走软删除(归档),无独立 agent 文件,故 agentFilesDeleted 恒 0;
    // mainDeleted 仅取决于是否至少归档了 1 行(对称 Codex 把"文件是否删掉"映射为 mainDeleted)。

    @Test
    public void buildDeleteResultMapsArchivedRowsToMainDeleted() {
        assertEquals(new HistoryDeleteResult(true, 0), OpenCodeHistoryProviderAdapter.buildDeleteResult(1));
        assertEquals(new HistoryDeleteResult(true, 0), OpenCodeHistoryProviderAdapter.buildDeleteResult(3));
    }

    @Test
    public void buildDeleteResultZeroOrNegativeArchivedIsNotDeleted() {
        assertEquals(new HistoryDeleteResult(false, 0), OpenCodeHistoryProviderAdapter.buildDeleteResult(0));
        assertEquals(new HistoryDeleteResult(false, 0), OpenCodeHistoryProviderAdapter.buildDeleteResult(-1));
    }

    // loadMessages 后处理:OpenCode 把 IDE 拼接了 ## Project Modules 等上下文的用户文本原样存 SQLite,
    // 回放时 sanitizeHistoryMessages 剥除拼接上下文(对称 Claude 历史路径调 UserMessageSanitizer)。
    // 仅清理 user 消息;assistant(含 thinking/tool_use)不动。

    @Test
    public void sanitizeHistoryMessagesStripsAppendedContextFromUserMessages() {
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("type", "user");
        userMsg.addProperty("content", "你好\n\n## Project Modules\n\nThis project contains multiple modules:\n- a");

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", "你好\n\n## Project Modules\n\nThis project contains multiple modules:\n- a");
        JsonArray blocks = new JsonArray();
        blocks.add(textBlock);
        JsonObject raw = new JsonObject();
        raw.addProperty("role", "user");
        raw.add("content", blocks);
        userMsg.add("raw", raw);

        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("type", "assistant");
        assistantMsg.addProperty("content", "你好！有什么我可以帮你的吗？");

        List<JsonObject> messages = new ArrayList<>();
        messages.add(userMsg);
        messages.add(assistantMsg);

        OpenCodeHistoryProviderAdapter.sanitizeHistoryMessages(messages);

        // user:顶层 content 截断为原始输入"你好"
        assertEquals("你好", messages.get(0).get("content").getAsString());
        // user:raw.content[0].text 同步清理(前端读 raw.content 渲染时也拿到干净文本)
        JsonObject cleanedBlock = messages.get(0).getAsJsonObject("raw")
                .getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("你好", cleanedBlock.get("text").getAsString());
        // assistant:不动
        assertEquals("你好！有什么我可以帮你的吗？", messages.get(1).get("content").getAsString());
    }

    @Test
    public void sanitizeHistoryMessagesHandlesUserMessageWithoutRawContent() {
        // 防御:仅顶层 content、无 raw.content 数组的 user 消息也能清理,不抛异常
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("type", "user");
        userMsg.addProperty("content", "你好\n\n## Opened Files Context\n\n- src/Main.java");

        List<JsonObject> messages = new ArrayList<>();
        messages.add(userMsg);

        OpenCodeHistoryProviderAdapter.sanitizeHistoryMessages(messages);

        assertEquals("你好", messages.get(0).get("content").getAsString());
    }
}
