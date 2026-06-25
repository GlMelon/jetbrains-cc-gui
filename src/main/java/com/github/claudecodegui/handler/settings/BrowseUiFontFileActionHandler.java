package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#BROWSE_UI_FONT_FILE} (B3 slice: project-config). */
public class BrowseUiFontFileActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public BrowseUiFontFileActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.BROWSE_UI_FONT_FILE; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleBrowseUiFontFile(); }
}
