package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#ADD_OPENCODE_PROVIDER}. */
public class AddOpenCodeProviderActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public AddOpenCodeProviderActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.ADD_OPENCODE_PROVIDER; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleAddOpenCodeProvider(payload); }
}
