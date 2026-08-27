package com.github.claudecodegui.cli.grok;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * GrokCliStreamParser 双格式解析:streaming-json 事件(text/thought/end/error)
 * 与 tailer 合成 marker 行([MESSAGE] 工具信号/[SESSION_ID]/[USAGE])。
 */
public class GrokCliStreamParserTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>();
        String error;
        boolean completed;
        boolean completeOk;

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
            this.error = error;
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
            completed = true;
            completeOk = success;
        }

        @Override
        public void onInterrupted(String content, String reason) {
            // 未覆盖
        }
    }

    @Test
    public void textEventEmitsContentDeltaAndAccumulates() {
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);

        parser.parseLine("{\"type\":\"text\",\"data\":\"你好\"}");
        parser.parseLine("{\"type\":\"text\",\"data\":\"世界\"}");

        assertTrue(parser.receivedAnyEvent());
        assertEquals("你好世界", parser.accumulatedText());
        // 首个内容事件先惰性触发 stream_start(前端先建流),随后两条 delta
        List<String[]> deltas = cb.messages.stream()
                .filter(m -> CliConstants.MSG_CONTENT_DELTA.equals(m[0])).toList();
        assertEquals(2, deltas.size());
        assertEquals("你好", deltas.get(0)[1]);
        assertEquals("世界", deltas.get(1)[1]);
    }

    @Test
    public void thoughtEventsActivateThinkingOnceAndPassIncrementalDeltas() {
        // grok thought 为增量式(对称旧 bridge):每条直接下发 delta,激活态仅一次。
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);

        parser.parseLine("{\"type\":\"thought\",\"data\":\"先查目录\"}");
        parser.parseLine("{\"type\":\"thought\",\"data\":\",再看测试\"}");
        parser.parseLine("{\"type\":\"text\",\"data\":\"done\"}");

        long activations = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_THINKING.equals(m[0])).count();
        assertEquals("思考激活只发一次", 1L, activations);
        long deltas = cb.messages.stream()
                .filter(m -> CliConstants.MSG_THINKING_DELTA.equals(m[0])).count();
        assertEquals("每条 thought 都是一条 delta", 2L, deltas);
    }

    @Test
    public void endEventCapturesSessionIdAndUsageButDefersStreamEnd() {
        // end 携带 sessionId/usage,但流结束延迟到 finishStream()(等最终 chat_history drain)。
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);

        parser.parseLine("{\"type\":\"text\",\"data\":\"hi\"}");
        parser.parseLine("{\"type\":\"end\",\"sessionId\":\"11111111-2222-3333-4444-555555555555\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");

        assertEquals("11111111-2222-3333-4444-555555555555", parser.capturedSessionId());
        assertFalse("end 不应立即结束流(tailer 尾部工具结果未 drain)", parser.streamEnded());
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_SESSION_ID.equals(m[0])));
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_USAGE.equals(m[0])
                && m[1].contains("\"input_tokens\":10")));

        assertFalse(cb.messages.stream().anyMatch(m -> CliConstants.MSG_STREAM_END.equals(m[0])));

        parser.finishStream();
        assertTrue(parser.streamEnded());
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_STREAM_END.equals(m[0])));
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_MESSAGE_END.equals(m[0])));
    }

    @Test
    public void syntheticMessageMarkersRouteToToolUseAndResult() {
        // tailer 注入的 [MESSAGE] 行:assistant/tool_use 解包为原始块,user/tool_result 原样透传。
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);

        parser.parseLine(GrokToolHistoryTailer.formatToolUseLine(
                "tool-1", "bash", "{\"command\":\"echo hi\"}"));
        parser.parseLine(GrokToolHistoryTailer.formatToolResultLine("tool-1", "hi"));

        assertTrue(cb.messages.stream().anyMatch(m -> CommonConstants.MSG_TYPE_TOOL_USE.equals(m[0])
                && m[1].contains("\"bash\"") && m[1].contains("echo hi")));
        assertTrue(cb.messages.stream().anyMatch(m -> CommonConstants.MSG_TYPE_TOOL_RESULT.equals(m[0])
                && m[1].contains("\"tool-1\"") && m[1].contains("\"hi\"")));
    }

    @Test
    public void errorEventMarksHasErrorWhileMcpFailureIsDowngraded() {
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);
        parser.parseLine("{\"type\":\"error\",\"message\":\"boom\"}");
        assertTrue(parser.hasError());
        assertTrue(parser.errorDiagnostic().contains("boom"));

        // MCP 连接失败:镜像 opencode 的非阻塞降级,不标 hasError、不发错误
        RecordingCallback cb2 = new RecordingCallback();
        GrokCliStreamParser parser2 = new GrokCliStreamParser(cb2);
        parser2.parseLine("{\"type\":\"error\",\"message\":\"mcp server 'weather' failed to connect\"}");
        assertFalse("MCP 失败不标记 hasError", parser2.hasError());
        assertEquals(1, cb2.messages.stream()
                .filter(m -> CliConstants.CODEX_MSG_STATUS.equals(m[0])).count());
    }

    @Test
    public void sessionIdMarkerCapturesBeforeEndFallback() {
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);

        parser.parseLine("[SESSION_ID] my-session-id");
        assertEquals("my-session-id", parser.capturedSessionId());

        // 后续 end 的 sessionId 不覆盖首捕获(仅首次下发语义由 emitter/session 层保证)
        parser.parseLine("{\"type\":\"end\",\"sessionId\":\"another-id\"}");
        assertEquals("my-session-id", parser.capturedSessionId());
    }

    @Test
    public void noiseLinesAreIgnoredWithoutEvents() {
        RecordingCallback cb = new RecordingCallback();
        GrokCliStreamParser parser = new GrokCliStreamParser(cb);
        parser.parseLine("Warning: something odd");
        parser.parseLine("");
        parser.parseLine(null);
        assertFalse(parser.receivedAnyEvent());
        assertNull(parser.capturedSessionId());
    }
}
