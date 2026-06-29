package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_CURRENT_OPENCODE_CONFIG}. */
public class GetCurrentOpenCodeConfigActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public GetCurrentOpenCodeConfigActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_CURRENT_OPENCODE_CONFIG; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleGetCurrentOpenCodeConfig(); }
}
