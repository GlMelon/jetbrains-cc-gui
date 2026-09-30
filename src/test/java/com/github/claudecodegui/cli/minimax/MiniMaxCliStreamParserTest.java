package com.github.claudecodegui.cli.minimax;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * MiniMaxCliStreamParser:0.2.x 扁平事件与 0.4.x schemaVersion 1 信封(item 化)双协议。
 * 用例对称上游 ai-bridge/services/minimax/message-service.test.mjs。
 */
public class MiniMaxCliStreamParserTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
            errors.add(error);
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

    private static List<String[]> ofType(RecordingCallback cb, String type) {
        return cb.messages.stream().filter(m -> type.equals(m[0])).toList();
    }

    // ── 0.2.x 扁平事件(回归) ────────────────────────────────────────────────

    @Test
    public void legacyDeltaContentAndThinkingMapToDeltas() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"type\":\"delta\",\"content\":\"hello\"}");
        parser.parseLine("{\"type\":\"delta\",\"thinking\":\"pondering\"}");

        assertEquals("hello", ofType(cb, CliConstants.MSG_CONTENT_DELTA).get(0)[1]);
        assertTrue(ofType(cb, CommonConstants.MSG_TYPE_THINKING).size() >= 1);
        assertEquals("pondering", ofType(cb, CliConstants.MSG_THINKING_DELTA).get(0)[1]);
    }

    @Test
    public void legacyMessageUsageEmitted() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"type\":\"message\",\"message\":{\"role\":\"assistant\","
                + "\"id\":\"m1\",\"usage\":{\"totalTokens\":10}}}");

        assertEquals(1, ofType(cb, CliConstants.MSG_USAGE).size());
    }

    @Test
    public void legacyExecResultFailedWithoutOutputRaisesError() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"type\":\"exec.result\",\"sessionId\":\"s1\",\"status\":\"failed\"}");

        assertEquals("s1", parser.capturedSessionId());
        assertEquals(1, cb.errors.size());
        assertTrue(parser.hasError());
    }

    @Test
    public void legacyToolStatusAsStringIsNormalized() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"type\":\"delta\",\"toolCalls\":[{\"id\":\"t1\",\"name\":\"bash\","
                + "\"status\":\"2\",\"output\":{\"content\":\"done\"}}]}");

        assertEquals(1, ofType(cb, CommonConstants.MSG_TYPE_TOOL_USE).size());
        assertEquals(1, ofType(cb, CommonConstants.MSG_TYPE_TOOL_RESULT).size());
    }

    // ── 0.4.x schemaVersion 1 信封 ──────────────────────────────────────────

    @Test
    public void itemUpdatedAgentMessageDeltaMapsToContent() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\","
                + "\"item\":{\"id\":\"uuid:message\",\"type\":\"agent_message\","
                + "\"contentDelta\":\"I'll run that\"}}");

        assertEquals("I'll run that", ofType(cb, CliConstants.MSG_CONTENT_DELTA).get(0)[1]);
    }

    @Test
    public void itemStartedReasoningDeltaMapsToThinking() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.started\","
                + "\"item\":{\"id\":\"uuid:reasoning\",\"type\":\"reasoning\","
                + "\"contentDelta\":\"The user is asking\"}}");

        assertEquals(1, ofType(cb, CommonConstants.MSG_TYPE_THINKING).size());
        assertEquals("The user is asking", ofType(cb, CliConstants.MSG_THINKING_DELTA).get(0)[1]);
    }

    @Test
    public void itemToolCallStatusesMapToToolEvents() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\",\"item\":{\"id\":\"c1\","
                + "\"type\":\"tool_call\",\"toolCall\":{\"id\":\"c1\",\"name\":\"bash\","
                + "\"status\":1,\"input\":{\"command\":\"echo hi\"}}}}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\",\"item\":{\"id\":\"c1\","
                + "\"type\":\"tool_call\",\"toolCall\":{\"id\":\"c1\",\"name\":\"bash\","
                + "\"status\":2,\"input\":{\"command\":\"echo hi\"},"
                + "\"output\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\\r\\n\"}]}}}}");

        // done 补发 tool_use(id 去重后 start/done 各一次)
        assertEquals(1, ofType(cb, CommonConstants.MSG_TYPE_TOOL_USE).size());
        assertEquals(1, ofType(cb, CommonConstants.MSG_TYPE_TOOL_RESULT).size());
        assertTrue(ofType(cb, CommonConstants.MSG_TYPE_TOOL_RESULT).get(0)[1].contains("hi"));
    }

    @Test
    public void itemToolCallQueuedRunningStatusesIgnored() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        for (int status : new int[]{4, 5}) {
            parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\",\"item\":{\"id\":\"c1\","
                    + "\"type\":\"tool_call\",\"toolCall\":{\"id\":\"c1\",\"name\":\"bash\","
                    + "\"status\":" + status + "}}}");
        }

        assertEquals(0, ofType(cb, CommonConstants.MSG_TYPE_TOOL_USE).size());
        assertEquals(0, ofType(cb, CommonConstants.MSG_TYPE_TOOL_RESULT).size());
    }

    @Test
    public void itemCompletedFullTextEmitsOnceAfterDeltaSameId() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\","
                + "\"item\":{\"id\":\"m1\",\"type\":\"agent_message\",\"contentDelta\":\"ok\"}}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.completed\","
                + "\"item\":{\"id\":\"m1\",\"type\":\"agent_message\",\"content\":\"ok\"}}");

        assertEquals(1, ofType(cb, CliConstants.MSG_CONTENT_DELTA).size());
    }

    @Test
    public void itemCompletedWithoutIdUsesAnonKeyAndEmitsOnce() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"agent_message\",\"content\":\"final answer\"}}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.completed\","
                + "\"item\":{\"type\":\"agent_message\",\"content\":\"final answer\"}}");

        assertEquals(1, ofType(cb, CliConstants.MSG_CONTENT_DELTA).size());
        assertEquals("final answer", ofType(cb, CliConstants.MSG_CONTENT_DELTA).get(0)[1]);
    }

    @Test
    public void itemCompletedUnknownTypeIgnored() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\","
                + "\"item\":{\"id\":\"x\",\"type\":\"file_change\",\"contentDelta\":\"patch\"}}");

        assertEquals(0, ofType(cb, CliConstants.MSG_CONTENT_DELTA).size());
    }

    @Test
    public void turnCompletedUsageEmitted() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"turn.completed\","
                + "\"usage\":{\"inputTokens\":30660,\"outputTokens\":100,\"totalTokens\":30760}}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"turn.completed\"}");

        assertEquals(1, ofType(cb, CliConstants.MSG_USAGE).size());
    }

    @Test
    public void execCompletedSucceededCapturesNestedSessionId() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"exec.completed\","
                + "\"sessionId\":\"mvs_b6ec\",\"result\":{\"schemaVersion\":1,"
                + "\"type\":\"exec.result\",\"sessionId\":\"mvs_b6ec\",\"status\":\"succeeded\","
                + "\"output\":\"ok\"}}");

        assertEquals("mvs_b6ec", parser.capturedSessionId());
        assertEquals(0, cb.errors.size());
    }

    @Test
    public void execCompletedFailedMapsNestedErrorObject() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"exec.completed\","
                + "\"result\":{\"schemaVersion\":1,\"type\":\"exec.result\",\"status\":\"failed\","
                + "\"error\":{\"category\":\"runtime\","
                + "\"message\":\"Model \\\"bogus/x\\\" is not available\",\"retryable\":true}}}");

        assertEquals(1, cb.errors.size());
        assertEquals("Model \"bogus/x\" is not available", cb.errors.get(0));
    }

    @Test
    public void turnFailedSurfacesErrorOnceAndExecCompletedDoesNotDuplicate() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"turn.failed\",\"status\":\"failed\","
                + "\"error\":{\"category\":\"runtime\",\"message\":\"model overloaded\"}}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"exec.completed\","
                + "\"result\":{\"schemaVersion\":1,\"type\":\"exec.result\",\"status\":\"failed\","
                + "\"error\":{\"category\":\"runtime\",\"message\":\"model overloaded\"}}}");

        assertEquals(1, cb.errors.size());
        assertEquals("model overloaded", cb.errors.get(0));
    }

    @Test
    public void turnFailedAcceptsPlainStringErrorAndIgnoresEmpty() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"turn.failed\",\"error\":\"rate limited\"}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"turn.failed\",\"status\":\"failed\"}");

        assertEquals(1, cb.errors.size());
        assertEquals("rate limited", cb.errors.get(0));
    }

    @Test
    public void execCompletedAnswerFallbackEmittedWhenNothingStreamed() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"exec.completed\","
                + "\"result\":{\"schemaVersion\":1,\"type\":\"exec.result\","
                + "\"sessionId\":\"s9\",\"status\":\"succeeded\",\"answer\":\"cached answer\"}}");

        assertEquals(1, ofType(cb, CliConstants.MSG_CONTENT_DELTA).size());
        assertEquals("cached answer", ofType(cb, CliConstants.MSG_CONTENT_DELTA).get(0)[1]);
        assertEquals("s9", parser.capturedSessionId());
    }

    @Test
    public void noAnswerFallbackWhenContentAlreadyStreamed() {
        RecordingCallback cb = new RecordingCallback();
        MiniMaxCliStreamParser parser = new MiniMaxCliStreamParser(cb);
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"item.updated\","
                + "\"item\":{\"id\":\"m1\",\"type\":\"agent_message\",\"contentDelta\":\"streamed\"}}");
        parser.parseLine("{\"schemaVersion\":1,\"type\":\"exec.completed\","
                + "\"result\":{\"schemaVersion\":1,\"type\":\"exec.result\","
                + "\"status\":\"succeeded\",\"answer\":\"cached answer\"}}");

        assertEquals(1, ofType(cb, CliConstants.MSG_CONTENT_DELTA).size());
        assertEquals("streamed", ofType(cb, CliConstants.MSG_CONTENT_DELTA).get(0)[1]);
    }
}
