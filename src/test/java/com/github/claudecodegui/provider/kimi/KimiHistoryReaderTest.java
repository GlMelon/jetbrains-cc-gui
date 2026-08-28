package com.github.claudecodegui.provider.kimi;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KimiHistoryReader:state.json 候选键探测、wire.jsonl → 前端消息、删除。
 */
public class KimiHistoryReaderTest {

    private static final String WORKDIR = "C:\\proj\\kdemo";

    private Path newSession(Path base, String sessionId) throws IOException {
        Path dir = base.resolve("sessions").resolve("some-work-dir-key").resolve(sessionId);
        Files.createDirectories(dir.resolve("agents").resolve("main"));
        JsonObject state = new JsonObject();
        state.addProperty("title", "fix kdemo bug");
        state.addProperty("workDir", WORKDIR);
        Files.writeString(dir.resolve("state.json"), state.toString());
        return dir;
    }

    @Test
    public void listsSessionsWithTolerantStateParsing() throws IOException {
        Path base = Files.createTempDirectory("kimi-hist");
        newSession(base, "session_01HZABC");

        KimiHistoryReader reader = new KimiHistoryReader(base);
        List<KimiHistoryReader.KimiSessionInfo> sessions = reader.listSessions("c:/proj/kdemo");

        assertEquals(1, sessions.size());
        assertEquals("session_01HZABC", sessions.get(0).sessionId);
        assertEquals("fix kdemo bug", sessions.get(0).title);

        // 无关项目不命中;cwd 缺失的会话也无法归位 → 跳过
        assertTrue(reader.listSessions("C:/other").isEmpty());
    }

    @Test
    public void loadMessagesMapsWireRoles() throws IOException {
        Path base = Files.createTempDirectory("kimi-hist2");
        Path session = newSession(base, "session_01HZDEF");

        // 程序化构造行,规避手写转义(与生产 wire.jsonl 形态一致)
        com.google.gson.JsonObject user = new com.google.gson.JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "hello kimi");

        com.google.gson.JsonObject call = new com.google.gson.JsonObject();
        call.addProperty("id", "c1");
        com.google.gson.JsonObject fn = new com.google.gson.JsonObject();
        fn.addProperty("name", "bash");
        com.google.gson.JsonObject args = new com.google.gson.JsonObject();
        args.addProperty("command", "ls");
        fn.add("arguments", args);
        call.add("function", fn);
        com.google.gson.JsonArray calls = new com.google.gson.JsonArray();
        calls.add(call);
        com.google.gson.JsonObject assistantWithTool = new com.google.gson.JsonObject();
        assistantWithTool.addProperty("role", "assistant");
        assistantWithTool.addProperty("content", "thinking…");
        assistantWithTool.add("tool_calls", calls);

        com.google.gson.JsonObject toolResult = new com.google.gson.JsonObject();
        toolResult.addProperty("role", "tool");
        toolResult.addProperty("tool_call_id", "c1");
        toolResult.addProperty("content", "out");

        com.google.gson.JsonObject finished = new com.google.gson.JsonObject();
        finished.addProperty("role", "assistant");
        finished.addProperty("content", "finished");

        Files.writeString(session.resolve("agents").resolve("main").resolve("wire.jsonl"),
                user + "\n" + assistantWithTool + "\n" + toolResult + "\n" + finished
                        + "\njunk line\n");

        List<JsonObject> messages = new KimiHistoryReader(base).loadMessages(session);

        assertEquals("actual=" + messages.stream().map(m -> m == null ? "null" : m.toString()).toList(),
                4, messages.size());
        assertEquals("user", messages.get(0).get("type").getAsString());
        assertEquals("hello kimi", messages.get(0).get("content").getAsString());

        JsonObject assistantToolMsg = messages.get(1);
        assertTrue(assistantToolMsg.toString().contains("\"bash\""));
        assertTrue(assistantToolMsg.toString().contains("thinking…"));

        assertEquals("user", messages.get(2).get("type").getAsString());
        assertTrue(messages.get(2).toString().contains("\"c1\"") && messages.get(2).toString().contains("out"));
        assertEquals("finished", messages.get(3).get("content").getAsString());
    }

    @Test
    public void deleteRemovesSessionDir() throws IOException {
        Path base = Files.createTempDirectory("kimi-hist3");
        newSession(base, "session_01HZXYZ");

        KimiHistoryReader reader = new KimiHistoryReader(base);
        assertTrue(reader.findSessionDir("session_01HZXYZ", null) != null);
        assertTrue(reader.deleteSession("session_01HZXYZ", "c:/proj/kdemo"));
        assertNull(reader.findSessionDir("session_01HZXYZ", null));
    }

    /** ACP wire 事件流(v2 引擎,2026-08 实测形态):turn.prompt → user,content.part 聚合 → assistant。 */
    @Test
    public void loadMessagesParsesAcpWireEvents() throws IOException {
        Path base = Files.createTempDirectory("kimi-hist4");
        Path session = newSession(base, "session_01HZACP");

        StringBuilder wire = new StringBuilder();
        // 控制事件:消费不产出
        wire.append("{\"type\":\"metadata\",\"protocol_version\":\"1.5\"}\n");
        wire.append("{\"type\":\"runtime.set_binding\",\"agentId\":\"main\"}\n");
        wire.append("{\"type\":\"profile.bind\",\"thinkingEffort\":\"high\"}\n");
        // turn 0:user + think + text + turn.ended
        wire.append("{\"type\":\"turn.prompt\",\"agentId\":\"main\",\"input\":[{\"type\":\"text\",")
            .append("\"text\":\"你好\\n\\n## Opened Files Context\\n{\\\"modules\\\":[]}\"}],")
            .append("\"origin\":{\"kind\":\"user\"},\"time\":1787899890890}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"content.part\",\"turnId\":\"0\",")
            .append("\"part\":{\"type\":\"think\",\"think\":\"Simple greeting.\"}}}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"content.part\",\"turnId\":\"0\",")
            .append("\"part\":{\"type\":\"text\",\"text\":\"你好！有什么可以帮你的吗？\"}}}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"step.end\",\"finishReason\":\"end_turn\"}}\n");
        wire.append("{\"type\":\"turn.ended\",\"turnId\":\"0\"}\n");
        // turn 1:user + text(无 turn.ended,靠 EOF flush)
        wire.append("{\"type\":\"turn.prompt\",\"agentId\":\"main\",\"input\":[{\"type\":\"text\",")
            .append("\"text\":\"你是什么模型\"}],\"origin\":{\"kind\":\"user\"}}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"content.part\",\"turnId\":\"1\",")
            .append("\"part\":{\"type\":\"text\",\"text\":\"我是 Kimi 助手。\"}}}\n");
        Files.writeString(session.resolve("agents").resolve("main").resolve("wire.jsonl"), wire.toString());

        List<JsonObject> messages = new KimiHistoryReader(base).loadMessages(session);

        assertEquals("actual=" + messages.stream().map(JsonObject::toString).toList(),
                4, messages.size());
        // turn 0 user:注入标记被剥离
        assertEquals("user", messages.get(0).get("type").getAsString());
        assertEquals("你好", messages.get(0).get("content").getAsString());
        // turn 0 assistant:think→thinking block + text 块挂 raw.content(前端 normalizeBlocks 读位)
        JsonObject assistant0 = messages.get(1);
        assertEquals("assistant", assistant0.get("type").getAsString());
        assertEquals("你好！有什么可以帮你的吗？", assistant0.get("content").getAsString());
        String blocks0 = assistant0.getAsJsonObject("raw").get("content").toString();
        assertTrue(blocks0.contains("\"thinking\"") && blocks0.contains("Simple greeting."));
        assertTrue(blocks0.contains("\"text\"") && blocks0.contains("有什么可以帮你的吗"));
        // turn 1 user + assistant(EOF flush)
        assertEquals("user", messages.get(2).get("type").getAsString());
        assertEquals("你是什么模型", messages.get(2).get("content").getAsString());
        assertEquals("我是 Kimi 助手。", messages.get(3).get("content").getAsString());
    }

    /** ACP wire 工具事件:tool.call → tool_use 块,tool.result → 独立 user(tool_result)消息,随 assistant 补发。 */
    @Test
    public void loadMessagesParsesAcpToolEvents() throws IOException {
        Path base = Files.createTempDirectory("kimi-hist6");
        Path session = newSession(base, "session_01HZTOOL");

        StringBuilder wire = new StringBuilder();
        wire.append("{\"type\":\"turn.prompt\",\"input\":[{\"type\":\"text\",\"text\":\"跑下 ls\"}]}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"content.part\",")
            .append("\"part\":{\"type\":\"think\",\"think\":\"need a listing.\"}}}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"tool.call\",")
            .append("\"toolCallId\":\"tool_abc\",\"name\":\"bash\",\"args\":{\"command\":\"ls\"}}}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"tool.result\",")
            .append("\"toolCallId\":\"tool_abc\",\"result\":{\"output\":\"file-a\\nfile-b\"}}}\n");
        wire.append("{\"type\":\"context.append_loop_event\",\"event\":{\"type\":\"content.part\",")
            .append("\"part\":{\"type\":\"text\",\"text\":\"列出来了。\"}}}\n");
        wire.append("{\"type\":\"turn.ended\"}\n");
        Files.writeString(session.resolve("agents").resolve("main").resolve("wire.jsonl"), wire.toString());

        List<JsonObject> messages = new KimiHistoryReader(base).loadMessages(session);

        assertEquals("actual=" + messages.stream().map(JsonObject::toString).toList(),
                3, messages.size());
        assertEquals("user", messages.get(0).get("type").getAsString());
        // assistant:thinking + text + tool_use 块
        JsonObject assistant = messages.get(1);
        assertEquals("assistant", assistant.get("type").getAsString());
        assertEquals("列出来了。", assistant.get("content").getAsString());
        String blocks = assistant.getAsJsonObject("raw").get("content").toString();
        assertTrue(blocks.contains("\"thinking\"") && blocks.contains("need a listing."));
        assertTrue(blocks.contains("\"tool_use\"") && blocks.contains("\"bash\"")
                && blocks.contains("tool_abc"));
        // tool_result 消息在 assistant 之后
        JsonObject resultMsg = messages.get(2);
        assertEquals("user", resultMsg.get("type").getAsString());
        String resultBlocks = resultMsg.getAsJsonObject("raw").get("content").toString();
        assertTrue(resultBlocks.contains("tool_abc") && resultBlocks.contains("file-a"));
        assertTrue(resultBlocks.contains("\"tool_result\""));
    }

    /** kimi 把 lastPrompt 首行当 title,注入段污染列表展示:剥到最早出现的注入标记。 */
    @Test
    public void listSessionsStripsInjectedContextFromTitle() throws IOException {
        Path base = Files.createTempDirectory("kimi-hist5");
        Path dir = base.resolve("sessions").resolve("wd").resolve("session_01HZTIT");
        Files.createDirectories(dir);
        JsonObject state = new JsonObject();
        state.addProperty("title", "你好 ## Opened Files Context {\"modules\":[]}");
        state.addProperty("cwd", WORKDIR);
        Files.writeString(dir.resolve("state.json"), state.toString());

        List<KimiHistoryReader.KimiSessionInfo> sessions =
                new KimiHistoryReader(base).listSessions("c:/proj/kdemo");
        assertEquals(1, sessions.size());
        assertEquals("你好", sessions.get(0).title);
    }
}
