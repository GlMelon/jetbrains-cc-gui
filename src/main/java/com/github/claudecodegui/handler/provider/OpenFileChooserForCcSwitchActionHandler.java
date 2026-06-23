package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#OPEN_FILE_CHOOSER_FOR_CC_SWITCH}. */
public class OpenFileChooserForCcSwitchActionHandler implements FrontendActionHandler<String> {
    private final ProviderActionHandlers handlers;
    public OpenFileChooserForCcSwitchActionHandler(ProviderActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.OPEN_FILE_CHOOSER_FOR_CC_SWITCH; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleOpenFileChooserForCcSwitch(); }
}
