package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

public class WindowActionHandlerTest {

    @Test
    public void heartbeatAction_matchesLegacyType() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        HeartbeatActionHandler h = new HeartbeatActionHandler(dummy);
        Assert.assertEquals(UpstreamAction.HEARTBEAT, h.action());
        Assert.assertEquals("heartbeat", h.action().value());
    }

    @Test
    public void tabLoadingChangedAction_matchesLegacyType() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        TabLoadingChangedActionHandler h = new TabLoadingChangedActionHandler(dummy);
        Assert.assertEquals(UpstreamAction.TAB_LOADING_CHANGED, h.action());
        Assert.assertEquals("tab_loading_changed", h.action().value());
    }

    @Test
    public void tabStatusChangedAction_matchesLegacyType() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        TabStatusChangedActionHandler h = new TabStatusChangedActionHandler(dummy);
        Assert.assertEquals(UpstreamAction.TAB_STATUS_CHANGED, h.action());
        Assert.assertEquals("tab_status_changed", h.action().value());
    }

    @Test
    public void createNewSessionAction_matchesLegacyType() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        CreateNewSessionActionHandler h = new CreateNewSessionActionHandler(dummy);
        Assert.assertEquals(UpstreamAction.CREATE_NEW_SESSION, h.action());
        Assert.assertEquals("create_new_session", h.action().value());
    }

    @Test
    public void frontendReadyAction_matchesLegacyType() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        FrontendReadyActionHandler h = new FrontendReadyActionHandler(dummy);
        Assert.assertEquals(UpstreamAction.FRONTEND_READY, h.action());
        Assert.assertEquals("frontend_ready", h.action().value());
    }

    @Test
    public void refreshSlashCommandsAction_matchesLegacyType() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        RefreshSlashCommandsActionHandler h = new RefreshSlashCommandsActionHandler(dummy);
        Assert.assertEquals(UpstreamAction.REFRESH_SLASH_COMMANDS, h.action());
        Assert.assertEquals("refresh_slash_commands", h.action().value());
    }

    @Test
    public void allImplementFrontendActionHandler() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        FrontendActionHandler<?>[] handlers = {
            new HeartbeatActionHandler(dummy),
            new TabLoadingChangedActionHandler(dummy),
            new TabStatusChangedActionHandler(dummy),
            new CreateNewSessionActionHandler(dummy),
            new FrontendReadyActionHandler(dummy),
            new RefreshSlashCommandsActionHandler(dummy),
        };
        for (FrontendActionHandler<?> h : handlers) {
            Assert.assertNotNull("action() must not be null", h.action());
            Assert.assertNotNull("payloadType() must not be null", h.payloadType());
        }
    }

    @Test
    public void payloadTypes_areAllString() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        Assert.assertEquals(String.class, new HeartbeatActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new TabLoadingChangedActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new TabStatusChangedActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new CreateNewSessionActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new FrontendReadyActionHandler(dummy).payloadType());
        Assert.assertEquals(String.class, new RefreshSlashCommandsActionHandler(dummy).payloadType());
    }

    @Test
    public void actions_areDistinct() {
        WindowActionHandlers dummy = new WindowActionHandlers(new NoOpCallback());
        java.util.Set<UpstreamAction> seen = new java.util.HashSet<>();
        for (FrontendActionHandler<?> h : new FrontendActionHandler<?>[]{
            new HeartbeatActionHandler(dummy),
            new TabLoadingChangedActionHandler(dummy),
            new TabStatusChangedActionHandler(dummy),
            new CreateNewSessionActionHandler(dummy),
            new FrontendReadyActionHandler(dummy),
            new RefreshSlashCommandsActionHandler(dummy),
        }) {
            Assert.assertTrue("Duplicate action: " + h.action(), seen.add(h.action()));
        }
        Assert.assertEquals(6, seen.size());
    }

    private static final class NoOpCallback implements WindowActionHandlers.Callback {
        @Override public void onHeartbeat(String content) {}
        @Override public void onTabLoadingChanged(boolean loading) {}
        @Override public void onTabStatusChanged(String status) {}
        @Override public void onCreateNewSession() {}
        @Override public void onFrontendReady() {}
        @Override public void onRefreshSlashCommands() {}
    }
}
