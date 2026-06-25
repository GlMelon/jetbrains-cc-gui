package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_STATUS_BAR_WIDGET_ENABLED} (B3 slice: project-config). */
public class GetStatusBarWidgetEnabledActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public GetStatusBarWidgetEnabledActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_STATUS_BAR_WIDGET_ENABLED; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetStatusBarWidgetEnabled(); }
}
