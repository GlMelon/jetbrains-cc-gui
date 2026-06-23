package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Assert;
import org.junit.Test;

/** Contract tests for model-provider action handlers (B3 slice: model-provider). */
public class ModelProviderActionHandlerTest {

    private void assertContract(FrontendActionHandler<String> h, UpstreamAction expected, String value) {
        Assert.assertEquals(expected, h.action());
        Assert.assertEquals(value, h.action().value());
        Assert.assertEquals(String.class, h.payloadType());
    }

    @Test public void testSetModel() {
        assertContract(new SetModelActionHandler(null), UpstreamAction.SET_MODEL, "set_model");
    }

    @Test public void testSetSessionModel() {
        assertContract(new SetSessionModelActionHandler(null), UpstreamAction.SET_SESSION_MODEL, "set_session_model");
    }

    @Test public void testSetProvider() {
        assertContract(new SetProviderActionHandler(null), UpstreamAction.SET_PROVIDER, "set_provider");
    }

    @Test public void testSetSessionProvider() {
        assertContract(new SetSessionProviderActionHandler(null), UpstreamAction.SET_SESSION_PROVIDER, "set_session_provider");
    }

    @Test public void testSetReasoningEffort() {
        assertContract(new SetReasoningEffortActionHandler(null), UpstreamAction.SET_REASONING_EFFORT, "set_reasoning_effort");
    }

    @Test public void testSetCodexFastMode() {
        assertContract(new SetCodexFastModeActionHandler(null), UpstreamAction.SET_CODEX_FAST_MODE, "set_codex_fast_mode");
    }

    @Test public void testImplementsFrontendActionHandler() {
        Assert.assertTrue(new SetModelActionHandler(null) instanceof FrontendActionHandler<?>);
    }
}
