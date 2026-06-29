package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#REVOKE_OPENCODE_LOCAL_CONFIG_AUTHORIZATION}. */
public class RevokeOpenCodeLocalConfigAuthorizationActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public RevokeOpenCodeLocalConfigAuthorizationActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.REVOKE_OPENCODE_LOCAL_CONFIG_AUTHORIZATION; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleRevokeOpenCodeLocalConfigAuthorization(payload); }
}
