package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PiCliStreamParser:JSON 事件流模式(session/message_update delta/tool_execution 系列/message_end)。
 * pi thinking 为一等公民(thinking_delta 直通);start/end 天然配对。
 */
public class PiCliStreamParserTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
            // 未覆盖
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
            // 未覆盖
        }

        @Override
        public void onInterrupted(String content, String reason) {
            // 未覆盖
        }
    }

    @Test
    public void sessionHeaderCapturesSessionIdOnce() {
        RecordingCallback cb = new RecordingCallback();
        PiCliStreamParser parser = new PiCliStreamParser(cb);

        parser.parseLine("{\"type\":\"session\",\"version\":3,\"id\":\"uuid-1\",\"cwd\":\"/p\"}");
        parser.parseLine("{\"type\":\"session\",\"id\":\"uuid-1\"}");

        assertEquals("uuid-1", parser.capturedSessionId());
        assertEquals(1L, cb.messages.stream()
                .filter(m -> CliConstants.MSG_SESSION_ID.equals(m[0])).count());
    }

    @Test
    public void textDeltasAccumulate() {
        RecordingCallback cb = new RecordingCallback();
        PiCliStreamParser parser = new PiCliStreamParser(cb);

        parser.parseLine("{\"type\":\"message_update\",\"assistantMessageEvent\":"
                + "{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"Hel\"}}");
        parser.parseLine("{\"type\":\"message_update\",\"assistantMessageEvent\":"
                + "{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"lo\"}}");

        assertEquals("Hello", parser.accumulatedText());
        assertTrue(parser.receivedAnyEvent());
    }

    @Test
    public void thinkingDeltasActivateThinkingSection() {
        RecordingCallback cb = new RecordingCallback();
        PiCliStreamParser parser = new PiCliStreamParser(cb);

        parser.parseLine("{\"type\":\"message_update\",\"assistantMessageEvent\":"
                + "{\"type\":\"thinking_delta\",\"delta\":\"reason step 1. \"}}");
        parser.parseLine("{\"type\":\"message_update\",\"assistantMessageEvent\":"
                + "{\"type\":\"thinking_delta\",\"delta\":\"step 2.\"}}");

        assertEquals("激活一次", 1L, cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_THINKING.equals(m[0])).count());
        assertEquals("两条 delta", 2L, cb.messages.stream()
                .filter(m -> CliConstants.MSG_THINKING_DELTA.equals(m[0])).count());
    }

    @Test
    public void toolExecutionStartEndPairEmitsPairedBlocks() {
        RecordingCallback cb = new RecordingCallback();
        PiCliStreamParser parser = new PiCliStreamParser(cb);

        parser.parseLine("{\"type\":\"tool_execution_start\",\"toolCallId\":\"tc1\","
                + "\"toolName\":\"bash\",\"args\":{\"command\":\"echo hi\"}}");
        parser.parseLine("{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc1\","
                + "\"toolName\":\"bash\",\"isError\":false,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\\n\"}]}}");
        parser.parseLine("{\"type\":\"tool_execution_end\",\"toolCallId\":\"tc2\","
                + "\"toolName\":\"edit\",\"isError\":true,\"result\":\"boom\"}");

        assertTrue(parser.receivedAnyEvent());
        // 仅 tc1 有 start 事件;tc2 只有 end(result 兜底直发)
        long toolUses = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_TOOL_USE.equals(m[0])).count();
        assertEquals(1L, toolUses);
        List<String[]> results = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_TOOL_RESULT.equals(m[0])).toList();
        assertEquals(2L, results.size());
        assertTrue(results.get(0)[1].contains("\"is_error\":false") && results.get(0)[1].contains("hi"));
        assertTrue(results.get(1)[1].contains("\"is_error\":true") && results.get(1)[1].contains("boom"));
    }

    @Test
    public void messageEndUsageForwardedForAssistantOnly() {
        RecordingCallback cb = new RecordingCallback();
        PiCliStreamParser parser = new PiCliStreamParser(cb);

        parser.parseLine("{\"type\":\"message_end\",\"message\":{\"role\":\"user\",\"usage\":{"
                + "\"input_tokens\":1}}}");
        assertFalse("非 assistant 的 usage 不透传", cb.messages.stream()
                .anyMatch(m -> CliConstants.MSG_USAGE.equals(m[0])));

        parser.parseLine("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"endTurn\","
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":5}}}");
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_USAGE.equals(m[0])
                && m[1].contains("\"input_tokens\":100")));
    }

    @Test
    public void unknownEventsAreIgnoredWithoutError() {
        RecordingCallback cb = new RecordingCallback();
        PiCliStreamParser parser = new PiCliStreamParser(cb);

        parser.parseLine("{\"type\":\"turn_start\"}");
        parser.parseLine("{\"type\":\"agent_start\"}");
        parser.parseLine("not json at all");

        assertFalse(parser.hasError());
        assertEquals("", parser.errorDiagnostic());
    }
}
