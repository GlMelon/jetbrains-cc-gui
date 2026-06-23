package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

public class SessionActionHandlerTest {

    private final SessionActionHandlers handlers = null;

    @Test
    public void sendMessageAction_matchesLegacyType() {
        SendMessageActionHandler h = new SendMessageActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.SEND_MESSAGE, h.action());
        Assert.assertEquals("send_message", h.action().value());
    }

    @Test
    public void sendMessageWithAttachmentsAction_matchesLegacyType() {
        SendMessageWithAttachmentsActionHandler h = new SendMessageWithAttachmentsActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.SEND_MESSAGE_WITH_ATTACHMENTS, h.action());
        Assert.assertEquals("send_message_with_attachments", h.action().value());
    }

    @Test
    public void interruptSessionAction_matchesLegacyType() {
        InterruptSessionActionHandler h = new InterruptSessionActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.INTERRUPT_SESSION, h.action());
        Assert.assertEquals("interrupt_session", h.action().value());
    }

    @Test
    public void restartSessionAction_matchesLegacyType() {
        RestartSessionActionHandler h = new RestartSessionActionHandler(handlers);
        Assert.assertEquals(UpstreamAction.RESTART_SESSION, h.action());
        Assert.assertEquals("restart_session", h.action().value());
    }

    @Test
    public void allImplementFrontendActionHandler() {
        SessionActionHandlers dummy = null;
        FrontendActionHandler<?>[] handlers = {
            new SendMessageActionHandler(dummy),
            new SendMessageWithAttachmentsActionHandler(dummy),
            new InterruptSessionActionHandler(dummy),
            new RestartSessionActionHandler(dummy),
        };
        for (FrontendActionHandler<?> h : handlers) {
            Assert.assertNotNull("action() must not be null", h.action());
            Assert.assertNotNull("payloadType() must not be null", h.payloadType());
        }
    }

    @Test
    public void payloadTypes_areAllString() {
        SessionActionHandlers dummy = null;
        Assert.assertEquals(String.class, new SendMessageActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new SendMessageWithAttachmentsActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new InterruptSessionActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new RestartSessionActionHandler(dummy).payloadType());
    }

    @Test
    public void actions_areDistinct() {
        SessionActionHandlers dummy = null;
        java.util.Set<UpstreamAction> seen = new java.util.HashSet<>();
        for (FrontendActionHandler<?> h : new FrontendActionHandler<?>[]{
            new SendMessageActionHandler(dummy),
            new SendMessageWithAttachmentsActionHandler(dummy),
            new InterruptSessionActionHandler(dummy),
            new RestartSessionActionHandler(dummy),
        }) {
            Assert.assertTrue("Duplicate action: " + h.action(), seen.add(h.action()));
        }
        Assert.assertEquals(4, seen.size());
    }
}
