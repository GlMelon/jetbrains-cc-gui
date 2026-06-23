package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_DEPENDENCY_VERSIONS}. */
public class GetDependencyVersionsActionHandler implements FrontendActionHandler<String> {

    private final DependencyActionHandlers handlers;

    public GetDependencyVersionsActionHandler(DependencyActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_DEPENDENCY_VERSIONS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetDependencyVersions(payload);
    }
}
