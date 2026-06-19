package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrontendActionDispatcherTest {

    @Test
    public void dispatchesRegisteredStringAction() {
        AtomicReference<String> seen = new AtomicReference<>();
        FrontendActionHandler<String> handler = new StringActionHandler(UpstreamAction.SET_MODE, seen);
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(List.of(handler), null);

        assertTrue(dispatcher.dispatch("set_mode", "plan"));

        assertEquals("plan", seen.get());
    }

    @Test
    public void dispatchesRegisteredJsonPayloadAction() {
        AtomicReference<ModePayload> seen = new AtomicReference<>();
        FrontendActionHandler<ModePayload> handler = new ModePayloadActionHandler(seen);
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(List.of(handler), null);

        assertTrue(dispatcher.dispatch("set_session_mode", "{\"mode\":\"default\"}"));

        assertEquals("default", seen.get().mode);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateActionHandlers() {
        new FrontendActionDispatcher(List.of(
                new NoopStringActionHandler(UpstreamAction.SET_MODE),
                new NoopStringActionHandler(UpstreamAction.SET_MODE)
        ), null);
    }

    @Test
    public void returnsFalseForUnknownAction() {
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(List.of(), null);

        assertFalse(dispatcher.dispatch("missing", "{}"));
    }

    @Test
    public void legacyHandlerAdapterForwardsRawContent() {
        AtomicBoolean called = new AtomicBoolean(false);
        AtomicReference<String> seenType = new AtomicReference<>();
        AtomicReference<String> seenContent = new AtomicReference<>();
        MessageHandler legacyHandler = new MessageHandler() {
            @Override
            public boolean handle(String type, String content) {
                called.set(true);
                seenType.set(type);
                seenContent.set(content);
                return true;
            }

            @Override
            public String[] getSupportedTypes() {
                return new String[]{"set_mode"};
            }
        };

        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(
                LegacyMessageHandlerAdapter.from(legacyHandler),
                null
        );

        assertTrue(dispatcher.dispatch("set_mode", "plan"));
        assertTrue(called.get());
        assertEquals("set_mode", seenType.get());
        assertEquals("plan", seenContent.get());
    }

    private static class StringActionHandler implements FrontendActionHandler<String> {
        private final UpstreamAction action;
        private final AtomicReference<String> seen;

        private StringActionHandler(UpstreamAction action, AtomicReference<String> seen) {
            this.action = action;
            this.seen = seen;
        }

        @Override
        public UpstreamAction action() {
            return action;
        }

        @Override
        public Class<String> payloadType() {
            return String.class;
        }

        @Override
        public void handle(String payload, FrontendActionContext context) {
            seen.set(payload);
        }
    }

    private static final class NoopStringActionHandler extends StringActionHandler {
        private NoopStringActionHandler(UpstreamAction action) {
            super(action, new AtomicReference<>());
        }
    }

    private static final class ModePayloadActionHandler implements FrontendActionHandler<ModePayload> {
        private final AtomicReference<ModePayload> seen;

        private ModePayloadActionHandler(AtomicReference<ModePayload> seen) {
            this.seen = seen;
        }

        @Override
        public UpstreamAction action() {
            return UpstreamAction.SET_SESSION_MODE;
        }

        @Override
        public Class<ModePayload> payloadType() {
            return ModePayload.class;
        }

        @Override
        public void handle(ModePayload payload, FrontendActionContext context) {
            seen.set(payload);
        }
    }

    private static final class ModePayload {
        String mode;
    }
}
