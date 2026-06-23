package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SWITCH_CODEX_PROVIDER}. */
public class SwitchCodexProviderActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public SwitchCodexProviderActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.SWITCH_CODEX_PROVIDER; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleSwitchCodexProvider(payload); }
}
