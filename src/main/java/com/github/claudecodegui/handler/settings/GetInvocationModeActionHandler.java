package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_INVOCATION_MODE} (B3 slice: project-config). */
public class GetInvocationModeActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public GetInvocationModeActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_INVOCATION_MODE; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetInvocationMode(); }
}
