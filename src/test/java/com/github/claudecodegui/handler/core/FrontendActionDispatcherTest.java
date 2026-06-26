package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void isRegisteredReflectsRoutedActions() {
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(
                List.of(new NoopStringActionHandler(UpstreamAction.SET_MODE)), null);

        assertTrue(dispatcher.isRegistered("set_mode"));
        assertFalse(dispatcher.isRegistered("not_a_real_action"));
    }

    @Test
    public void verifyAllRegisteredPassesWhenEveryHandlerIsRouted() {
        FrontendActionHandler<?> a = new NoopStringActionHandler(UpstreamAction.SET_MODE);
        FrontendActionHandler<?> b = new NoopStringActionHandler(UpstreamAction.LOAD_HISTORY_DATA);
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(List.of(a, b), null);

        // 不抛 = 所有 handler 的 action 都在路由表
        FrontendActionDispatcher.verifyAllRegistered(dispatcher, List.of(a, b));
    }

    @Test
    public void verifyAllRegisteredFailsWhenHandlerMissingFromDispatcher() {
        // 防回归:模拟 B2/B4 装配时机 bug —— dispatcher 用部分 list 构造后,list 又追加了
        // handler。自检必须发现"list 里的 handler 未进入 dispatcher 路由表"并 fail-fast。
        FrontendActionHandler<?> present = new NoopStringActionHandler(UpstreamAction.SET_MODE);
        FrontendActionHandler<?> missing = new NoopStringActionHandler(UpstreamAction.LOAD_HISTORY_DATA);
        FrontendActionDispatcher partial = new FrontendActionDispatcher(List.of(present), null);

        try {
            FrontendActionDispatcher.verifyAllRegistered(partial, List.of(present, missing));
            fail("Expected IllegalStateException because 'load_history_data' is not routed");
        } catch (IllegalStateException expected) {
            assertTrue("message should name the missing action, got: " + expected.getMessage(),
                    expected.getMessage().contains("load_history_data"));
        }
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
