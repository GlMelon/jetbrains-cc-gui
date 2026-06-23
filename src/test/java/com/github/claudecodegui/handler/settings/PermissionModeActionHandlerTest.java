package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/**
 * Contract tests for permission-mode action handlers (B3 slice: permission mode).
 */
public class PermissionModeActionHandlerTest {

    @Test
    public void testGetModeActionContract() {
        GetModeActionHandler h = new GetModeActionHandler(null);
        Assert.assertEquals(UpstreamAction.GET_MODE, h.action());
        Assert.assertEquals("get_mode", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
        Assert.assertTrue(h instanceof FrontendActionHandler<?>);
    }

    @Test
    public void testSetModeActionContract() {
        SetModeActionHandler h = new SetModeActionHandler(null);
        Assert.assertEquals(UpstreamAction.SET_MODE, h.action());
        Assert.assertEquals("set_mode", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test
    public void testSetSessionModeActionContract() {
        SetSessionModeActionHandler h = new SetSessionModeActionHandler(null);
        Assert.assertEquals(UpstreamAction.SET_SESSION_MODE, h.action());
        Assert.assertEquals("set_session_mode", h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }
}
