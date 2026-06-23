package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SAVE_IMPORTED_PROVIDERS}. */
public class SaveImportedProvidersActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public SaveImportedProvidersActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.SAVE_IMPORTED_PROVIDERS; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleSaveImportedProviders(payload); }
}
