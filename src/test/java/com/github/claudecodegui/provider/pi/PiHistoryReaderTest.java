package com.github.claudecodegui.provider.pi;

import com.github.claudecodegui.util.GsonHolder;
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
 * PiHistoryReader:header.cwd 项目匹配、session_info 标题、message 三态映射、删除。
 * fixture 按官方 session-format v3 规范构造。
 */
public class PiHistoryReaderTest {

    private Path writeSession(Path base, String uuid, String cwd, String... extraLines)
            throws IOException {
        // 目录名按规范 --<path>--,但 reader 不解析目录名 → fixture 任意合法目录名即可
        Path dir = base.resolve("sessions").resolve("sess-" + System.nanoTime());
        Files.createDirectories(dir);
        JsonObject header = new JsonObject();
        header.addProperty("type", "session");
        header.addProperty("version", 3);
        header.addProperty("id", uuid);
        header.addProperty("timestamp", "2026-08-27T00:00:00.000Z");
        header.addProperty("cwd", cwd);
        StringBuilder sb = new StringBuilder(header.toString());
        for (String line : extraLines) {
            sb.append('\n').append(line);
        }
        Path file = dir.resolve("20260827_" + uuid + ".jsonl");
        Files.writeString(file, sb + "\n");
        return file;
    }

    private static String msg(String role, String contentJson) {
        return "{\"type\":\"message\",\"id\":\"e1\",\"parentId\":null,"
                + "\"timestamp\":\"2026-08-27T00:00:01.000Z\","
                + "\"message\":{\"role\":\"" + role + "\",\"content\":" + contentJson + "}}";
    }

    @Test
    public void listsSessionsByHeaderCwdAndPrefersSessionInfoName() throws IOException {
        Path base = Files.createTempDirectory("pi-hist");
        String infoEntry = "{\"type\":\"session_info\",\"id\":\"s1\",\"parentId\":null,"
                + "\"timestamp\":\"2026-08-27T00:00:02.000Z\",\"name\":\"Refactor auth module\"}";
        writeSession(base, "uuid-aaaa", "/home/u/proj", infoEntry,
                msg("user", "\"hello\""));

        PiHistoryReader reader = new PiHistoryReader(base);
        List<PiHistoryReader.PiSessionInfo> sessions = reader.listSessions("/home/u/proj");

        assertEquals(1, sessions.size());
        assertEquals("uuid-aaaa", sessions.get(0).sessionId);
        assertEquals("Refactor auth module", sessions.get(0).title);
        assertEquals(1, sessions.get(0).messageCount);

        assertTrue("无关项目不命中", reader.listSessions("/other").isEmpty());
    }

    @Test
    public void loadMessagesMapsUserAssistantToolResult() throws IOException {
        Path base = Files.createTempDirectory("pi-hist2");
        Path file = writeSession(base, "uuid-bbbb", "C:/proj/pi",
                msg("user", "\"review this\""),
                // assistant:text + thinking + toolCall 混合块
                "{\"type\":\"message\",\"id\":\"e2\",\"parentId\":\"e1\","
                        + "\"timestamp\":\"2026-08-27T00:00:02.000Z\",\"message\":{"
                        + "\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"plan...\"},"
                        + "{\"type\":\"toolCall\",\"id\":\"tc1\",\"name\":\"bash\","
                        + "\"arguments\":{\"command\":\"ls\"}},"
                        + "{\"type\":\"text\",\"text\":\"done\"}]}}",
                // toolResult
                "{\"type\":\"message\",\"id\":\"e3\",\"parentId\":\"e2\","
                        + "\"timestamp\":\"2026-08-27T00:00:03.000Z\",\"message\":{"
                        + "\"role\":\"toolResult\",\"toolCallId\":\"tc1\",\"toolName\":\"bash\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"out\"}],\"isError\":false}}",
                // compaction 等应被忽略
                "{\"type\":\"compaction\",\"id\":\"e4\",\"parentId\":\"e3\","
                        + "\"timestamp\":\"2026-08-27T00:00:04.000Z\",\"summary\":\"sum\"}");

        List<JsonObject> messages = new PiHistoryReader(base).loadMessages(file);

        assertEquals(3, messages.size());

        assertEquals("user", messages.get(0).get("type").getAsString());
        assertEquals("review this", messages.get(0).get("content").getAsString());

        JsonObject assistant = messages.get(1);
        assertEquals("assistant", assistant.get("type").getAsString());
        assertEquals("done", assistant.get("content").getAsString());
        String blocks = assistant.get("contentBlocks").toString();
        assertTrue(blocks.contains("\"thinking\"") && blocks.contains("plan..."));
        assertTrue(blocks.contains("\"bash\"") && blocks.contains("ls"));

        JsonObject result = messages.get(2);
        assertEquals("user", result.get("type").getAsString());
        assertTrue(result.get("contentBlocks").toString().contains("\"tc1\"")
                && result.get("contentBlocks").toString().contains("out"));
    }

    @Test
    public void deleteRemovesFileById() throws IOException {
        Path base = Files.createTempDirectory("pi-hist3");
        writeSession(base, "uuid-cccc", "C:/proj/x");

        PiHistoryReader reader = new PiHistoryReader(base);
        assertEquals(1, reader.listSessions("c:/proj/x").size());
        assertTrue(reader.deleteSession("uuid-cccc", "c:/proj/x"));
        assertNull(reader.findSessionFile("uuid-cccc", "c:/proj/x"));
    }

    @Test
    public void missingRootDegradesToEmpty() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"),
                "pi-hist-must-not-exist-" + System.nanoTime());
        PiHistoryReader reader = new PiHistoryReader(missing);
        assertTrue(reader.listSessions("/").isEmpty());
        assertNull(reader.findSessionFile("any", "/"));
    }

    @Test
    public void wireShapeRoundTripsThroughGson() {
        // 静态映射纯函数直测:字符串 content 的 user
        JsonObject user = GsonHolder.GSON.fromJson(
                "{\"role\":\"user\",\"content\":\"plain\"}", JsonObject.class);
        assertEquals("plain", PiHistoryReader.toFrontendMessage(user).get("content").getAsString());
        // 未知 role 忽略
        JsonObject bashExec = GsonHolder.GSON.fromJson(
                "{\"role\":\"bashExecution\",\"command\":\"ls\"}", JsonObject.class);
        assertNull(PiHistoryReader.toFrontendMessage(bashExec));
    }
}
