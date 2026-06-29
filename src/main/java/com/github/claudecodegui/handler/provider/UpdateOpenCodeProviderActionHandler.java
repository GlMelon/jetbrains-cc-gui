package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#UPDATE_OPENCODE_PROVIDER}. */
public class UpdateOpenCodeProviderActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public UpdateOpenCodeProviderActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.UPDATE_OPENCODE_PROVIDER; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleUpdateOpenCodeProvider(payload); }
}
