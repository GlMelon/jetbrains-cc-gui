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
}
