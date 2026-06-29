package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SORT_OPENCODE_PROVIDERS}. */
public class SortOpenCodeProvidersActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public SortOpenCodeProvidersActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.SORT_OPENCODE_PROVIDERS; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleSortOpenCodeProviders(payload); }
}
