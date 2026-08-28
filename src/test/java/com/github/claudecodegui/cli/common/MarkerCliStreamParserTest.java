package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * MarkerCliStreamParser 的 [THINKING_DELTA] 思考区链路:omp thinking_delta /
 * dsh reasoning-delta 经此接通(首条补 thinkingStart,后续增量)。
 */
public class MarkerCliStreamParserTest {

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

    private static List<String> typesOf(RecordingCallback cb) {
        List<String> types = new ArrayList<>();
        for (String[] m : cb.messages) {
            types.add(m[0]);
        }
        return types;
    }

    @Test
    public void firstThinkingDeltaEmitsThinkingStartThenDelta() {
        RecordingCallback cb = new RecordingCallback();
        MarkerCliStreamParser parser = new MarkerCliStreamParser(cb);

        parser.parseLine("[MESSAGE_START]");
        parser.parseLine("[STREAM_START]");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"想一想\"}");

        assertEquals(CommonConstants.MSG_TYPE_THINKING, cb.messages.get(2)[0]);
        assertEquals(CliConstants.MSG_THINKING_DELTA, cb.messages.get(3)[0]);
        assertEquals("想一想", cb.messages.get(3)[1]);
    }

    @Test
    public void subsequentThinkingDeltasEmitDeltaOnly() {
        RecordingCallback cb = new RecordingCallback();
        MarkerCliStreamParser parser = new MarkerCliStreamParser(cb);

        parser.parseLine("[STREAM_START]");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"a\"}");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"b\"}");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"c\"}");

        List<String> types = typesOf(cb);
        // thinkingStart 仅一次;其余为增量
        assertEquals(1, types.stream().filter(CommonConstants.MSG_TYPE_THINKING::equals).count());
        assertEquals(3, types.stream().filter(CliConstants.MSG_THINKING_DELTA::equals).count());
    }

    @Test
    public void thinkingAndContentInterleaveInOrder() {
        RecordingCallback cb = new RecordingCallback();
        MarkerCliStreamParser parser = new MarkerCliStreamParser(cb);

        parser.parseLine("[STREAM_START]");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"think\"}");
        parser.parseLine("[CONTENT_DELTA] {\"text\":\"answer\"}");

        assertEquals(CommonConstants.MSG_TYPE_THINKING, cb.messages.get(1)[0]);
        assertEquals(CliConstants.MSG_THINKING_DELTA, cb.messages.get(2)[0]);
        assertEquals(CliConstants.MSG_CONTENT_DELTA, cb.messages.get(3)[0]);
        assertEquals("answer", cb.messages.get(3)[1]);
    }

    @Test
    public void malformedOrEmptyThinkingLinesAreIgnored() {
        RecordingCallback cb = new RecordingCallback();
        MarkerCliStreamParser parser = new MarkerCliStreamParser(cb);

        parser.parseLine("[THINKING_DELTA]");
        parser.parseLine("[THINKING_DELTA] {\"other\":\"x\"}");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"\"}");
        parser.parseLine("plain stdout noise");
        parser.parseLine("[THINKING_DELTA] {\"text\":\"ok\"}");

        // 仅最后一条有效 delta 产生 thinkingStart + thinkingDelta
        assertEquals(2, cb.messages.size());
        assertEquals(CommonConstants.MSG_TYPE_THINKING, cb.messages.get(0)[0]);
        assertEquals("ok", cb.messages.get(1)[1]);
        assertFalse(parser.hasError());
        assertTrue(parser.receivedAnyEvent());
    }
}
