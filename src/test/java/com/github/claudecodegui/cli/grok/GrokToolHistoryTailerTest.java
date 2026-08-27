package com.github.claudecodegui.cli.grok;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * GrokToolHistoryTailer:chat_history.jsonl 增量尾随(对称 JS history-tools.js 行为)。
 * 覆盖:cwd 编码、增量 drain、resume 基线跳过、去重、残行拼接。
 */
public class GrokToolHistoryTailerTest {

    @Test
    public void jsEncodeURIComponentMatchesJsEngineOutput() {
        assertEquals("C%3A%2FUsers%2Fdev", GrokToolHistoryTailer.jsEncodeURIComponent(
                GrokToolHistoryTailer.normalizeCwd("C:\\Users\\dev")));
        assertEquals("D%3A%2Fa%20b", GrokToolHistoryTailer.jsEncodeURIComponent("D:/a b"));
        // JS 不转义 !'()* — URLEncoder 会转义,须还原
        assertEquals("!()*'~", GrokToolHistoryTailer.jsEncodeURIComponent("!()*'~"));
        // 尾部斜杠去除
        assertEquals("C%3A%2Fproj", GrokToolHistoryTailer.jsEncodeURIComponent(
                GrokToolHistoryTailer.normalizeCwd("C:\\proj\\")));
    }

    @Test
    public void rejectsPathTraversalSessionIds() {
        assertNull(GrokToolHistoryTailer.resolveChatHistoryPath(Path.of("/home"), "/proj", "../etc"));
        assertNull(GrokToolHistoryTailer.resolveChatHistoryPath(Path.of("/home"), "/proj", "a/b"));
        assertNull(GrokToolHistoryTailer.resolveChatHistoryPath(Path.of("/home"), "", "abc"));
        assertNull(GrokToolHistoryTailer.resolveChatHistoryPath(null, "/proj", "abc"));
    }

    @Test
    public void newSessionReadsFromStartAndDedupesAcrossPolls() throws IOException {
        Path home = Files.createTempDirectory("grok-home");
        String encoded = GrokToolHistoryTailer.jsEncodeURIComponent(
                GrokToolHistoryTailer.normalizeCwd("C:\\proj"));
        Path sessionDir = home.resolve("sessions").resolve(encoded).resolve("sid-1");
        Files.createDirectories(sessionDir);
        Path history = sessionDir.resolve("chat_history.jsonl");

        GrokToolHistoryTailer tailer = new GrokToolHistoryTailer(home, "C:\\proj", "sid-1", false);

        // 文件尚未出现 → 空
        assertTrue(tailer.pollOnce().isEmpty());

        List<String> first = """
                {"type":"assistant","tool_calls":[{"id":"t1","name":"bash","arguments":"{\\"command\\":\\"ls\\"}"}]}
                {"type":"tool_result","tool_call_id":"t1","content":"file1"}
                """.lines().toList();
        Files.writeString(history, String.join("\n", first) + "\n");
        List<String> signals = tailer.pollOnce();
        assertEquals(2, signals.size());
        assertTrue(signals.get(0).startsWith("[MESSAGE] {\"type\":\"assistant\""));
        assertTrue(signals.get(0).contains("\"t1\"") && signals.get(0).contains("\"bash\""));
        assertTrue(signals.get(1).contains("\"tool_result\"") && signals.get(1).contains("t1"));

        // 同内容重复读不重发(seen 集合)
        assertTrue(tailer.pollOnce().isEmpty());

        // 追加新信号只出新条目
        Files.writeString(history, String.join("\n", first) + "\n",
                java.nio.file.StandardOpenOption.APPEND);
        List<String> second = tailer.pollOnce();
        assertEquals(0, second.size()); // seen 去重:same ids 不会再发

        Files.writeString(history,
                "{\"type\":\"tool_result\",\"tool_call_id\":\"t1\",\"content\":\"dup\"}\n"
                        + "{\"type\":\"assistant\",\"tool_calls\":[{\"id\":\"t2\",\"name\":\"read\"}]}\n",
                java.nio.file.StandardOpenOption.APPEND);
        List<String> third = tailer.pollOnce();
        assertEquals("同 id 结果去重,新 id 工具下发", 1, third.size());
        assertTrue(third.get(0).contains("\"t2\""));

        Files.deleteIfExists(history);
    }

    @Test
    public void resumeSkipsPreexistingHistoryOnFirstSighting() throws IOException {
        Path home = Files.createTempDirectory("grok-home2");
        String encoded = GrokToolHistoryTailer.jsEncodeURIComponent(
                GrokToolHistoryTailer.normalizeCwd("C:\\proj"));
        Path sessionDir = home.resolve("sessions").resolve(encoded).resolve("sid-9");
        Files.createDirectories(sessionDir);
        Path history = sessionDir.resolve("chat_history.jsonl");
        Files.writeString(history,
                "{\"type\":\"assistant\",\"tool_calls\":[{\"id\":\"old-1\",\"name\":\"bash\"}]}\n");

        GrokToolHistoryTailer tailer = new GrokToolHistoryTailer(home, "C:\\proj", "sid-9", true);
        assertTrue("resume 首见全跳过", tailer.pollOnce().isEmpty());

        Files.writeString(history,
                "{\"type\":\"assistant\",\"tool_calls\":[{\"id\":\"new-1\",\"name\":\"edit\"}]}\n",
                java.nio.file.StandardOpenOption.APPEND);
        List<String> fresh = tailer.pollOnce();
        assertEquals(1, fresh.size());
        assertTrue(fresh.get(0).contains("\"new-1\""));

        Files.deleteIfExists(history);
    }

    @Test
    public void partialTrailingLineIsCarriedUntilComplete() throws IOException {
        Path home = Files.createTempDirectory("grok-home3");
        String encoded = GrokToolHistoryTailer.jsEncodeURIComponent(
                GrokToolHistoryTailer.normalizeCwd("C:\\proj"));
        Path sessionDir = home.resolve("sessions").resolve(encoded).resolve("sid-c");
        Files.createDirectories(sessionDir);
        Path history = sessionDir.resolve("chat_history.jsonl");

        GrokToolHistoryTailer tailer = new GrokToolHistoryTailer(home, "C:\\proj", "sid-c", false);

        // 无换行的残行 → 不产出信号
        Files.writeString(history, "{\"type\":\"tool_result\",\"tool_call_id\":\"p1\"");
        assertTrue(tailer.pollOnce().isEmpty());

        // 补全换行 → 完整行被解析
        Files.writeString(history, ",\"content\":\"ok\"}\n",
                java.nio.file.StandardOpenOption.APPEND);
        List<String> out = tailer.pollOnce();
        assertEquals(1, out.size());
        assertTrue(out.get(0).contains("\"p1\"") && out.get(0).contains("ok"));

        Files.deleteIfExists(history);
    }

    @Test
    public void missingCallIdGetsSyntheticUniqueIds() {
        GrokToolHistoryTailer tailer = new GrokToolHistoryTailer(null, null, null, false);
        List<String> out = tailer.drainChunk(
                "{\"type\":\"tool_result\",\"content\":\"no-id-a\"}\n"
                        + "{\"type\":\"tool_result\",\"content\":\"no-id-b\"}\n");
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("grok-tool-1"));
        assertTrue(out.get(1).contains("grok-tool-2"));
    }
}
