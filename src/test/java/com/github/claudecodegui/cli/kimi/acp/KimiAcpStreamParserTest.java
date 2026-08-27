package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KimiAcpStreamParser:ACP session/update 通知 → MSG 映射 + 重放门控。
 * 协议事实(0.38.0 实测):agent_thought_chunk 纯增量 / tool_call 懒创建 /
 * tool_call_update.content REPLACE 语义 / session_info_update.title。
 */
public class KimiAcpStreamParserTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(boolean success, String finalResult, String error) {
        }

        @Override
        public void onInterrupted(String finalResult, String message) {
        }
    }

    /** 构造一条 session/update 通知行(NDJSON),update 为给定 JSON 字符串。 */
    private static String updateLine(String updateJson) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\","
                + "\"params\":{\"sessionId\":\"session_test\",\"update\":" + updateJson + "}}";
    }

    private static List<String> types(RecordingCallback cb) {
        return cb.messages.stream().map(m -> m[0]).toList();
    }

    // ── thought chunk:首条 thinkingStart + 增量 thinkingDelta ──

    @Test
    public void thoughtChunkEmitsThinkingStartThenDelta() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        parser.beginLiveTurn();
        parser.parseLine(updateLine(
                "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"think-1\"}}"));
        parser.parseLine(updateLine(
                "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"think-2\"}}"));

        // 首条触发 thinkingStart(MSG_TYPE_THINKING),后续只 thinkingDelta
        long thinkingStarts = types(cb).stream().filter(t -> CommonConstants.MSG_TYPE_THINKING.equals(t)).count();
        assertEquals("thinkingStart 应只触发一次", 1, thinkingStarts);
        long deltas = types(cb).stream().filter(t -> CliConstants.MSG_THINKING_DELTA.equals(t)).count();
        assertEquals("两个 thought chunk 各一条 delta", 2, deltas);
        assertEquals("think-1", cb.messages.get(1)[1]);
        assertEquals("think-2", cb.messages.get(2)[1]);
    }

    // ── message chunk:正文增量 ──

    @Test
    public void messageChunkEmitsContentDelta() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        parser.beginLiveTurn();
        parser.parseLine(updateLine(
                "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hello\"}}"));

        assertTrue(types(cb).stream().anyMatch(CliConstants.MSG_CONTENT_DELTA::equals));
        assertEquals("hello", cb.messages.get(0)[1]);
    }

    // ── tool_call:懒创建去重(同 toolCallId 重放只发一次 tool_use) ──

    @Test
    public void toolCallLazilyCreatedAndDeduplicated() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        parser.beginLiveTurn();
        String tc = "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"0:tool_1\","
                + "\"title\":\"Bash\",\"kind\":\"execute\",\"status\":\"pending\"}";
        parser.parseLine(updateLine(tc));
        parser.parseLine(updateLine(tc));  // 同 id 重放

        long toolUses = types(cb).stream().filter(t -> CommonConstants.MSG_TYPE_TOOL_USE.equals(t)).count();
        assertEquals("同 toolCallId 重放只发一次 tool_use", 1, toolUses);
    }

    // ── tool_call_update:仅 completed/failed 发 tool_result(REPLACE 全量) ──

    @Test
    public void toolCallUpdateEmitsResultOnlyOnTerminalStatus() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        parser.beginLiveTurn();
        // 先 tool_call(创建)
        parser.parseLine(updateLine("{\"sessionUpdate\":\"tool_call\","
                + "\"toolCallId\":\"0:tool_2\",\"title\":\"Bash\",\"kind\":\"execute\",\"status\":\"pending\"}"));
        // in_progress 不发 tool_result
        parser.parseLine(updateLine("{\"sessionUpdate\":\"tool_call_update\","
                + "\"toolCallId\":\"0:tool_2\",\"status\":\"in_progress\","
                + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"running\"}}]}"));
        // completed 发 tool_result(REPLACE 全量文本)
        parser.parseLine(updateLine("{\"sessionUpdate\":\"tool_call_update\","
                + "\"toolCallId\":\"0:tool_2\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"done-output\"}}]}"));

        long results = types(cb).stream().filter(t -> CommonConstants.MSG_TYPE_TOOL_RESULT.equals(t)).count();
        assertEquals("只有 completed 发 tool_result", 1, results);
        // tool_result content 应是 REPLACE 全量("done-output",非"running")
        String resultContent = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_TOOL_RESULT.equals(m[0]))
                .findFirst().orElse(new String[]{"", ""})[1];
        assertTrue("tool_result 应含 completed 的全量文本", resultContent.contains("done-output"));
        assertFalse("tool_result 不应含 in_progress 的中间态", resultContent.contains("running"));
    }

    @Test
    public void toolCallUpdateFailedEmitsErrorResult() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        parser.beginLiveTurn();
        parser.parseLine(updateLine("{\"sessionUpdate\":\"tool_call\","
                + "\"toolCallId\":\"0:tool_3\",\"title\":\"Bash\",\"kind\":\"execute\",\"status\":\"pending\"}"));
        parser.parseLine(updateLine("{\"sessionUpdate\":\"tool_call_update\","
                + "\"toolCallId\":\"0:tool_3\",\"status\":\"failed\","
                + "\"content\":[{\"type\":\"content\",\"content\":{\"type\":\"text\",\"text\":\"boom\"}}]}"));

        String resultContent = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_TOOL_RESULT.equals(m[0]))
                .findFirst().orElse(new String[]{"", ""})[1];
        assertTrue("failed 应 is_error=true", resultContent.contains("\"is_error\":true"));
    }

    // ── 重放门控:live=false 时全部 update 丢弃,beginLiveTurn 后放行 ──

    @Test
    public void updatesAreDroppedUntilLiveTurnBegins() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        // live=false(默认,load 重放期间):thought/message 全部丢弃
        parser.parseLine(updateLine(
                "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"replay-thought\"}}"));
        parser.parseLine(updateLine(
                "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"replay-msg\"}}"));
        assertTrue("live=false 时不应产出任何事件", cb.messages.isEmpty());
        assertFalse("live=false 时 receivedAnyEvent 应为 false", parser.receivedAnyEvent());

        // beginLiveTurn 后放行
        parser.beginLiveTurn();
        parser.parseLine(updateLine(
                "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"live-msg\"}}"));
        assertTrue("beginLiveTurn 后应放行", parser.receivedAnyEvent());
        assertEquals("live-msg", cb.messages.get(0)[1]);
    }

    // ── session_info_update:捕获 title(受门控) ──

    @Test
    public void capturesTitleOnlyWhenLive() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        // live=false:title 不捕获
        parser.parseLine(updateLine("{\"sessionUpdate\":\"session_info_update\",\"title\":\"replay-title\"}"));
        assertNull("live=false 时 title 不捕获", parser.capturedTitle());
        // beginLiveTurn 后捕获
        parser.beginLiveTurn();
        parser.parseLine(updateLine("{\"sessionUpdate\":\"session_info_update\",\"title\":\"real-title\"}"));
        assertEquals("real-title", parser.capturedTitle());
    }

    // ── 忽略:available_commands_update / user_message_chunk ──

    @Test
    public void ignoresNonContentUpdates() {
        RecordingCallback cb = new RecordingCallback();
        KimiAcpStreamParser parser = new KimiAcpStreamParser(cb);
        parser.beginLiveTurn();
        parser.parseLine(updateLine("{\"sessionUpdate\":\"available_commands_update\","
                + "\"availableCommands\":[{\"name\":\"compact\",\"description\":\"d\"}]}"));
        parser.parseLine(updateLine("{\"sessionUpdate\":\"user_message_chunk\","
                + "\"content\":{\"type\":\"text\",\"text\":\"replayed-user\"}}"));
        // 这两种 update 不产出 wire 事件(但 available_commands_update 仍是"收到事件"标记 receivedAnyEvent)
        // user_message_chunk 被忽略(不进 UI)
        assertFalse("user_message_chunk 不应产出 content/thinking",
                types(cb).stream().anyMatch(t -> t.contains("content") || t.contains("thinking")));
    }
}
