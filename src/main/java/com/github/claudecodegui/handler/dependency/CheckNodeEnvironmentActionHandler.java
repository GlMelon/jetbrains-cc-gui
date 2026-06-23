package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#CHECK_NODE_ENVIRONMENT}. */
public class CheckNodeEnvironmentActionHandler implements FrontendActionHandler<String> {

    private final DependencyActionHandlers handlers;

    public CheckNodeEnvironmentActionHandler(DependencyActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CHECK_NODE_ENVIRONMENT;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleCheckNodeEnvironment();
    }
}
