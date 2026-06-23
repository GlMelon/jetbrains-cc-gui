package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#REVOKE_CODEX_LOCAL_CONFIG_AUTHORIZATION}. */
public class RevokeCodexLocalConfigAuthorizationActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public RevokeCodexLocalConfigAuthorizationActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.REVOKE_CODEX_LOCAL_CONFIG_AUTHORIZATION; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleRevokeCodexLocalConfigAuthorization(payload); }
}
