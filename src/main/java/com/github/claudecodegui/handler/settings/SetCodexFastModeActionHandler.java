package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.provider.ModelProviderHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SET_CODEX_FAST_MODE} (B3 slice: model-provider). */
public class SetCodexFastModeActionHandler implements FrontendActionHandler<String> {
    private final ModelProviderHandler modelProviderHandler;
    public SetCodexFastModeActionHandler(ModelProviderHandler modelProviderHandler) { this.modelProviderHandler = modelProviderHandler; }
    @Override public UpstreamAction action() { return UpstreamAction.SET_CODEX_FAST_MODE; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { modelProviderHandler.handleSetCodexFastMode(payload); }
}
