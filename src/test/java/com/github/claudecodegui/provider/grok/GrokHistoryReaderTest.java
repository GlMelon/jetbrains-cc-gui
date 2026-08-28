package com.github.claudecodegui.provider.grok;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * GrokHistoryReader:sessions 目录解码匹配、chat_history.jsonl → 前端消息、删除。
 */
public class GrokHistoryReaderTest {

    private static final String CWD = "C:\\proj\\demo";

    /** 与 GrokToolHistoryTailer.jsEncodeURIComponent(normalizeCwd(cwd)) 等价的手工编码(cli.grok 包私有)。 */
    private static String encodeCwd(String cwd) {
        String normalized = cwd.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return java.net.URLEncoder.encode(normalized, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~");
    }

    private Path newSession(Path root, String sessionId) throws IOException {
        Path dir = root.resolve("sessions").resolve(encodeCwd(CWD)).resolve(sessionId);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    public void listsSessionsByDecodedProjectPath() throws IOException {
        Path root = Files.createTempDirectory("grok-hist");
        Path session = newSession(root, "aaaaaaaa-1111-2222-3333-444444444444");
        Files.writeString(session.resolve("chat_history.jsonl"),
                """
                        {"type":"user","data":"please fix the bug"}
                        {"type":"assistant","data":"working on it"}
                        {"type":"tool_result","tool_call_id":"t1","content":"ok"}
                        """);

        GrokHistoryReader reader = new GrokHistoryReader(root);
        List<GrokHistoryReader.GrokSessionInfo> sessions =
                reader.listSessions("c:/proj/demo");

        assertEquals(1, sessions.size());
        assertEquals("aaaaaaaa-1111-2222-3333-444444444444", sessions.get(0).sessionId);
        assertTrue(sessions.get(0).messageCount >= 2);
        assertFalse(sessions.get(0).cwd.isEmpty());

        // 无关项目不命中
        assertTrue(reader.listSessions("C:/other").isEmpty());
    }

    @Test
    public void loadMessagesMapsToolLinesToClaudeBlocks() throws IOException {
        Path root = Files.createTempDirectory("grok-hist2");
        Path session = newSession(root, "bbbbbbbb-1111-2222-3333-444444444444");
        Files.writeString(session.resolve("chat_history.jsonl"),
                """
                        {"type":"assistant","tool_calls":[{"id":"t1","name":"bash","arguments":"{\\"command\\":\\"ls\\"}"}]}
                        {"type":"tool_result","tool_call_id":"t1","content":"file-a"}
                        {"type":"assistant","data":"done!"}
                        {"type":"unknown-junk"}
                        not json line
                        """);

        GrokHistoryReader reader = new GrokHistoryReader(root);
        List<JsonObject> messages = reader.loadMessages(session);

        assertEquals("actual=" + messages.stream().map(m -> m == null ? "null" : m.toString()).toList(),
                3, messages.size());
        assertEquals("assistant", messages.get(0).get("type").getAsString());
        assertTrue(messages.get(0).getAsJsonObject("raw").get("content").toString().contains("\"bash\""));
        assertTrue(messages.get(0).getAsJsonObject("raw").get("content").toString().contains("command"));

        JsonObject resultMsg = messages.get(1);
        assertEquals("user", resultMsg.get("type").getAsString());
        assertTrue(resultMsg.getAsJsonObject("raw").get("content").toString().contains("\"t1\"")
                && resultMsg.getAsJsonObject("raw").get("content").toString().contains("file-a"));

        assertEquals("assistant", messages.get(2).get("type").getAsString());
        assertEquals("done!", messages.get(2).get("content").getAsString());
    }

    @Test
    public void findAndDeleteSession() throws IOException {
        Path root = Files.createTempDirectory("grok-hist3");
        newSession(root, "cccccccc-1111-2222-3333-444444444444");

        GrokHistoryReader reader = new GrokHistoryReader(root);
        assertNotNull(reader.findSessionDir("cccccccc-1111-2222-3333-444444444444", "C:/proj/demo"));
        assertNull(reader.findSessionDir("not-exist", "C:/proj/demo"));

        assertTrue(reader.deleteSession("cccccccc-1111-2222-3333-444444444444", "C:/proj/demo"));
        assertNull(reader.findSessionDir("cccccccc-1111-2222-3333-444444444444", "C:/proj/demo"));
    }

    @Test
    public void defaultRootWithMissingDirectoryDegradesToEmpty() {
        // 不存在的自定义根:空列表、找不到目录,均不抛异常(优雅降级契约)
        Path missingRoot = Path.of(System.getProperty("java.io.tmpdir"),
                "grok-hist-must-not-exist-" + System.nanoTime());
        GrokHistoryReader reader = new GrokHistoryReader(missingRoot.resolve(".grok"));
        assertTrue(reader.listSessions("/").isEmpty());
        assertNull(reader.findSessionDir("any", "/"));
    }
}
