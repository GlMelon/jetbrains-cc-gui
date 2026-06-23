package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_DEPENDENCY_STATUS}. */
public class GetDependencyStatusActionHandler implements FrontendActionHandler<String> {

    private final DependencyActionHandlers handlers;

    public GetDependencyStatusActionHandler(DependencyActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_DEPENDENCY_STATUS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetStatus();
    }
}
