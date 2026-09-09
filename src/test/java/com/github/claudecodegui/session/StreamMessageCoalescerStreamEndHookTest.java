package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for the {@code onStreamEnded()} host hook on the REAL
 * {@link StreamMessageCoalescer} — the signal ClaudeChatWindow uses to drain a
 * deferred background-turn reload at the safe point (stream inactive).
 *
 * <p>These drive the production coalescer (not a re-implementation), so they
 * catch regressions in the actual onStreamStart/onStreamEnd lifecycle: the hook
 * firing, the {@code streamActive} transition, and per-turn repetition.
 */
public class StreamMessageCoalescerStreamEndHookTest {

    /** Minimal JsCallbackTarget that counts onStreamEnded() firings. */
    private static final class CountingTarget implements StreamMessageCoalescer.JsCallbackTarget {
        final AtomicInteger streamEndedCount = new AtomicInteger();

        @Override public void callJavaScript(String functionName, String... args) {}
        @Override public JBCefBrowser getBrowser() { return null; }
        @Override public boolean isDisposed() { return false; }
        @Override public HandlerContext getHandlerContext() { return null; }
        @Override public void onStreamEnded() { streamEndedCount.incrementAndGet(); }
    }

    @Test
    public void onStreamEndFiresHookAndClearsActive() {
        CountingTarget target = new CountingTarget();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(target);
        try {
            coalescer.onStreamStart();
            assertTrue("stream active after start", coalescer.isStreamActive());
            assertEquals("hook not fired yet", 0, target.streamEndedCount.get());

            coalescer.onStreamEnd();
            assertFalse("stream inactive after end", coalescer.isStreamActive());
            assertEquals("onStreamEnd fires the host hook exactly once", 1, target.streamEndedCount.get());
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void hookFiresOncePerTurnAcrossMultipleTurns() {
        // A long session fans out many turns; the deferred-reload drain must get
        // a signal at EACH turn boundary, not just the first.
        CountingTarget target = new CountingTarget();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(target);
        try {
            for (int i = 0; i < 5; i++) {
                coalescer.onStreamStart();
                coalescer.onStreamEnd();
            }
            assertEquals("hook fires once per turn", 5, target.streamEndedCount.get());
            assertFalse(coalescer.isStreamActive());
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void resetStreamStateClearsActiveWithoutFiringHook() {
        // resetStreamState() (new-session / restart) also drops streamActive, but
        // it is NOT a turn boundary — it must not fire the drain hook, or a reload
        // could run against a session the user just navigated away from.
        CountingTarget target = new CountingTarget();
        StreamMessageCoalescer coalescer = new StreamMessageCoalescer(target);
        try {
            coalescer.onStreamStart();
            assertTrue(coalescer.isStreamActive());

            coalescer.resetStreamState();
            assertFalse("reset clears active", coalescer.isStreamActive());
            assertEquals("reset must NOT fire the drain hook", 0, target.streamEndedCount.get());
        } finally {
            coalescer.dispose();
        }
    }

    @Test
    public void firstLongConversationSnapshotKeepsTheFullPrefix() {
        List<ClaudeSession.Message> messages = messages(400);

        StreamMessageCoalescer.MessageTransport transport =
                StreamMessageCoalescer.selectMessageTransport(messages, null);

        assertFalse(transport.tailUpdate());
        assertEquals(0, transport.baseIndex());
        assertEquals(messages, transport.messages());
    }

    @Test
    public void thresholdConversationKeepsTheFullSnapshot() {
        List<ClaudeSession.Message> messages = messages(300);

        StreamMessageCoalescer.MessageTransport transport =
                StreamMessageCoalescer.selectMessageTransport(messages, null);

        assertFalse(transport.tailUpdate());
        assertEquals(0, transport.baseIndex());
        assertEquals(messages, transport.messages());
    }

    @Test
    public void growingConversationWithStablePrefixUsesTail() {
        List<ClaudeSession.Message> previous = messages(300);
        List<ClaudeSession.Message> growing = new ArrayList<>(previous);
        for (int i = 300; i < 400; i++) {
            growing.add(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "message-" + i));
        }

        StreamMessageCoalescer.MessageTransport transport =
                StreamMessageCoalescer.selectMessageTransport(growing, previous);

        assertTrue(transport.tailUpdate());
        assertEquals(220, transport.baseIndex());
        assertEquals(180, transport.messages().size());
    }

    @Test
    public void shrinkingConversationForcesAFullRebase() {
        List<ClaudeSession.Message> previous = messages(400);
        List<ClaudeSession.Message> compacted = new ArrayList<>(previous.subList(0, 350));

        StreamMessageCoalescer.MessageTransport transport =
                StreamMessageCoalescer.selectMessageTransport(compacted, previous);

        assertFalse(transport.tailUpdate());
        assertEquals(0, transport.baseIndex());
        assertEquals(compacted, transport.messages());
    }

    @Test
    public void replacedPrefixForcesAFullRebase() {
        List<ClaudeSession.Message> previous = messages(400);
        List<ClaudeSession.Message> rebuilt = new ArrayList<>(previous);
        rebuilt.set(10, new ClaudeSession.Message(ClaudeSession.Message.Type.SYSTEM, "summary"));

        StreamMessageCoalescer.MessageTransport transport =
                StreamMessageCoalescer.selectMessageTransport(rebuilt, previous);

        assertFalse(transport.tailUpdate());
        assertEquals(0, transport.baseIndex());
        assertEquals(rebuilt, transport.messages());
    }

    private static List<ClaudeSession.Message> messages(int count) {
        List<ClaudeSession.Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "message-" + i));
        }
        return messages;
    }

    @Test
    public void textOnlyChangesSkipSnapshotPushWhileStreaming() {
        // Every provider streams text via the delta channel (onContentDelta);
        // a text-only change must not re-serialize the full snapshot mid-stream.
        assertFalse(StreamMessageCoalescer.shouldScheduleSnapshotPush(true, false));
    }

    @Test
    public void structuralChangesStillPushSnapshotWhileStreaming() {
        assertTrue(StreamMessageCoalescer.shouldScheduleSnapshotPush(true, true));
    }

    @Test
    public void idleStreamsAlwaysPushSnapshot() {
        assertTrue(StreamMessageCoalescer.shouldScheduleSnapshotPush(false, false));
        assertTrue(StreamMessageCoalescer.shouldScheduleSnapshotPush(false, true));
    }

    @Test
    public void snapshotSkipDecisionIsProviderAgnostic() throws IOException {
        // Platform-coupled push path (Alarm/JCEF) — guarded via source check, per AGENTS.md §6.
        // The snapshot-skip decision must not branch on provider identity: all CLI
        // providers emit MSG_CONTENT_DELTA, so the delta channel is a property of
        // the unified pipeline, not of a provider whitelist.
        String source = readSource("src/main/java/com/github/claudecodegui/session/StreamMessageCoalescer.java");
        assertFalse(source.contains("hasDeltaChannel"));
        assertFalse(source.contains("\"claude\".equals"));
        assertFalse(source.contains("\"codex\".equals"));
        assertFalse(source.contains("\"grok\".equals"));
        assertTrue(source.contains("shouldScheduleSnapshotPush(streamActive, structuralChanged)"));
    }

    private String readSource(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
