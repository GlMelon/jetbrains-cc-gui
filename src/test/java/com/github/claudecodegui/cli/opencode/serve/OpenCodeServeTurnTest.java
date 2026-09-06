package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OpenCodeServeTurn 事件映射单测:serve SSE 事件(实测契约 opencode v1.18.26)
 * → 统一 MSG_* 协议的逐类覆盖,与 one-shot OpenCodeCliStreamParserTest 对齐。
 */
public class OpenCodeServeTurnTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>(); // {type, content}

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
        }
    }

    private static OpenCodeServeTurn.TurnResult await(OpenCodeServeTurn turn) throws Exception {
        return turn.completion().get(5, TimeUnit.SECONDS);
    }

    private static long countType(RecordingCallback cb, String type) {
        return cb.messages.stream().filter(m -> m[0].equals(type)).count();
    }

    @Test
    public void textDeltaEmitsStreamStartOnceAndTokenDeltas() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);

        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":"
                        + "{\"id\":\"p1\",\"messageID\":\"m1\",\"sessionID\":\"ses_1\",\"type\":\"text\",\"text\":\"\"}}}")
                .getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_1\",\"messageID\":\"m1\","
                        + "\"partID\":\"p1\",\"field\":\"text\",\"delta\":\"Hello\"}}").getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_1\",\"messageID\":\"m1\","
                        + "\"partID\":\"p1\",\"field\":\"text\",\"delta\":\" world\"}}").getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_1\"}}").getAsJsonObject());

        OpenCodeServeTurn.TurnResult result = await(turn);
        assertEquals(OpenCodeServeTurn.OutcomeKind.COMPLETED, result.kind());
        assertEquals("Hello world", turn.accumulatedText());
        assertEquals(1, countType(cb, CliConstants.MSG_STREAM_START));
        assertEquals(2, countType(cb, CliConstants.MSG_CONTENT_DELTA));
        assertEquals("Hello", cb.messages.get(1)[1]);
        assertEquals(" world", cb.messages.get(2)[1]);
        assertEquals(1, countType(cb, CliConstants.MSG_STREAM_END));
        assertEquals(1, countType(cb, CliConstants.MSG_MESSAGE_END));
    }

    @Test
    public void reasoningPartDeduplicatesCumulativeText() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);

        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":"
                        + "{\"id\":\"pr\",\"sessionID\":\"ses_1\",\"type\":\"reasoning\",\"text\":\"think\"}}}")
                .getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":"
                        + "{\"id\":\"pr\",\"sessionID\":\"ses_1\",\"type\":\"reasoning\",\"text\":\"thinking\"}}}")
                .getAsJsonObject());
        // reasoning part 的 part.delta 不重复下发(内容走 part.updated 全量去重)
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_1\",\"partID\":\"pr\","
                        + "\"field\":\"text\",\"delta\":\"thinking\"}}").getAsJsonObject());

        assertEquals(1, countType(cb, CommonConstants.MSG_TYPE_THINKING));
        assertEquals(2, countType(cb, CliConstants.MSG_THINKING_DELTA));
        List<String> thinkingDeltas = cb.messages.stream()
                .filter(m -> m[0].equals(CliConstants.MSG_THINKING_DELTA)).map(m -> m[1]).toList();
        assertEquals("think", thinkingDeltas.get(0));
        assertEquals("ing", thinkingDeltas.get(1));
        assertEquals("", turn.accumulatedText());
    }

    @Test
    public void toolPartEmitsToolUseOnceAndToolResultOnTerminalState() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);

        String partPrefix = "{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"part\":"
                + "{\"id\":\"pt\",\"sessionID\":\"ses_1\",\"type\":\"tool\",\"tool\":\"bash\",\"state\":";
        turn.onEvent(JsonParser.parseString(
                partPrefix + "{\"status\":\"running\",\"input\":{\"cmd\":\"ls\"}}}}}").getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                partPrefix + "{\"status\":\"running\",\"input\":{\"cmd\":\"ls\"}}}}}").getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                partPrefix + "{\"status\":\"completed\",\"input\":{\"cmd\":\"ls\"},\"output\":\"ok\"}}}}")
                .getAsJsonObject());

        assertEquals(1, countType(cb, CommonConstants.MSG_TYPE_TOOL_USE));
        assertEquals(1, countType(cb, CommonConstants.MSG_TYPE_TOOL_RESULT));
        String toolResult = cb.messages.get(cb.messages.size() - 1)[1];
        assertTrue(toolResult.contains("\"tool_use_id\":\"pt\""));
        assertTrue(toolResult.contains("\"is_error\":false"));
        assertTrue(toolResult.contains("ok"));
    }

    @Test
    public void messageUpdatedEmitsUsageResultOncePerMessage() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);

        String msgUpdated = "{\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"info\":"
                + "{\"id\":\"m1\",\"role\":\"assistant\",\"time\":{\"created\":1,\"completed\":2},"
                + "\"tokens\":{\"input\":10,\"output\":5,\"reasoning\":0,"
                + "\"cache\":{\"read\":1,\"write\":2}},\"finish\":\"stop\"}}}";
        turn.onEvent(JsonParser.parseString(msgUpdated).getAsJsonObject());
        turn.onEvent(JsonParser.parseString(msgUpdated).getAsJsonObject());
        // 未 completed 的 message.updated 不发 usage
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"ses_1\",\"info\":"
                        + "{\"id\":\"m1\",\"time\":{\"created\":1},\"tokens\":{\"input\":1,\"output\":1}}}}")
                .getAsJsonObject());
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_1\"}}").getAsJsonObject());

        assertEquals(OpenCodeServeTurn.OutcomeKind.COMPLETED, await(turn).kind());
        assertEquals(1, countType(cb, CliConstants.MSG_RESULT));
        String usage = cb.messages.stream().filter(m -> m[0].equals(CliConstants.MSG_RESULT))
                .findFirst().orElseThrow()[1];
        assertTrue(usage.contains("\"input_tokens\":10"));
        assertTrue(usage.contains("\"output_tokens\":5"));
    }

    @Test
    public void sessionErrorAbortedMapsToInterrupted() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\",\"error\":"
                        + "{\"name\":\"" + OpenCodeServeTurn.ERROR_MESSAGE_ABORTED + "\","
                        + "\"data\":{\"message\":\"aborted\"}}}}").getAsJsonObject());
        assertEquals(OpenCodeServeTurn.OutcomeKind.INTERRUPTED, await(turn).kind());
        assertTrue(turn.wasInterrupted());
    }

    @Test
    public void sessionErrorOtherMapsToFailed() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);
        turn.onEvent(JsonParser.parseString(
                "{\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\",\"error\":"
                        + "{\"name\":\"ProviderError\",\"data\":{\"message\":\"quota exceeded\"}}}}")
                .getAsJsonObject());
        OpenCodeServeTurn.TurnResult result = await(turn);
        assertEquals(OpenCodeServeTurn.OutcomeKind.FAILED, result.kind());
        assertEquals("quota exceeded", result.error());
    }

    @Test
    public void streamClosedFailsTurnUnlessInterrupted() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn interruptedTurn = new OpenCodeServeTurn(cb);
        interruptedTurn.markInterrupted();
        interruptedTurn.onStreamClosed("test close");
        assertEquals(OpenCodeServeTurn.OutcomeKind.INTERRUPTED, await(interruptedTurn).kind());

        OpenCodeServeTurn failedTurn = new OpenCodeServeTurn(new RecordingCallback());
        failedTurn.onStreamClosed("test close");
        OpenCodeServeTurn.TurnResult result = await(failedTurn);
        assertEquals(OpenCodeServeTurn.OutcomeKind.FAILED, result.kind());
        assertTrue(result.error().contains("test close"));
    }

    @Test
    public void noiseEventsAreIgnored() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeTurn turn = new OpenCodeServeTurn(cb);
        for (String noise : new String[]{"server.connected", "server.heartbeat", "session.status",
                "file.watcher.updated", "catalog.updated", "permission.asked"}) {
            turn.onEvent(JsonParser.parseString(
                    "{\"type\":\"" + noise + "\",\"properties\":{\"sessionID\":\"ses_1\"}}").getAsJsonObject());
        }
        assertTrue(cb.messages.isEmpty());
        assertFalse(turn.completion().isDone());
    }
}
