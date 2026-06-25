package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SET_WORKING_DIRECTORY} (B3 slice: project-config). */
public class SetWorkingDirectoryActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public SetWorkingDirectoryActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.SET_WORKING_DIRECTORY; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleSetWorkingDirectory(payload); }
}
