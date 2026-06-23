package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#PREVIEW_CC_SWITCH_IMPORT}. */
public class PreviewCcSwitchImportActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public PreviewCcSwitchImportActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.PREVIEW_CC_SWITCH_IMPORT; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handlePreviewCcSwitchImport(); }
}
