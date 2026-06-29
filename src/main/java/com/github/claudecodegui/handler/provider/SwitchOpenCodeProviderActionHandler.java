package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SWITCH_OPENCODE_PROVIDER}. */
public class SwitchOpenCodeProviderActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public SwitchOpenCodeProviderActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.SWITCH_OPENCODE_PROVIDER; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleSwitchOpenCodeProvider(payload); }
}
