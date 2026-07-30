package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#PREVIEW_CODEX_CC_SWITCH_IMPORT}. */
public class PreviewCodexCcSwitchImportActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public PreviewCodexCcSwitchImportActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.PREVIEW_CODEX_CC_SWITCH_IMPORT; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handlePreviewCodexCcSwitchImport(); }
}
