package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.provider.common.CliResult;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClaudeMessageHandlerEpochGuardTest {

    @Test
    public void ignoresCallbacksFromStaleRuntimeEpoch() {
        SessionState state = new SessionState();
        RecordingCallbackHandler callbackHandler = new RecordingCallbackHandler();
        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson(),
                state.getRuntimeSessionEpoch()
        );

        state.rotateRuntimeSessionEpoch();

        handler.onMessage("content_delta", "stale");
        handler.onQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState.PROCESSING, 0);
        handler.onError("stale error");
        handler.onComplete(new com.github.claudecodegui.provider.common.CliResult());

        assertTrue(callbackHandler.contentDeltas.isEmpty());
        assertEquals(0, callbackHandler.queueUpdates);
        assertTrue(state.getMessages().isEmpty());
        assertEquals(ClaudeSession.SessionCallback.QueueDisplayState.NONE, state.getQueueDisplayState());
    }

    @Test
    public void ignoresCallbacksFromSupersededResponseTurn() {
        SessionState state = new SessionState();
        RecordingCallbackHandler callbackHandler = new RecordingCallbackHandler();
        long firstTurn = state.beginResponseTurn();
        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson(),
                state.getRuntimeSessionEpoch(),
                firstTurn
        );

        handler.onMessage(CliConstants.MSG_STREAM_START, "");
        state.beginResponseTurn();
        state.setBusy(true);
        state.setLoading(true);

        handler.onMessage(CliConstants.MSG_STREAM_END, "");
        handler.onError("late error");
        handler.onComplete(new CliResult());

        assertTrue(state.isBusy());
        assertTrue(state.isLoading());
        assertEquals(0, callbackHandler.streamEndCount);
    }

    @Test
    public void duplicateStreamEndIsDeliveredOnce() {
        SessionState state = new SessionState();
        RecordingCallbackHandler callbackHandler = new RecordingCallbackHandler();
        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson(),
                state.getRuntimeSessionEpoch()
        );

        handler.onMessage(CliConstants.MSG_STREAM_START, "");
        handler.onMessage(CliConstants.MSG_STREAM_END, "");
        handler.onComplete(new CliResult());
        handler.onMessage(CliConstants.MSG_STREAM_END, "");

        assertEquals(1, callbackHandler.streamEndCount);
        assertEquals(1, callbackHandler.streamCompletedCount);
    }
    private static class RecordingCallbackHandler extends CallbackHandler {
        final List<String> contentDeltas = new ArrayList<>();
        int queueUpdates = 0;
        int streamEndCount = 0;
        int streamCompletedCount = 0;

        @Override
        public void notifyContentDelta(String delta) {
            contentDeltas.add(delta);
        }

        @Override
        public void notifyQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState state, int aheadCount) {
            queueUpdates++;
        }

        @Override
        public void notifyStreamEnd() {
            streamEndCount++;
        }

        @Override
        public void notifyStreamCompleted() {
            streamCompletedCount++;
        }
    }
}
