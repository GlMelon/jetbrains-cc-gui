package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#CHECK_DEPENDENCY_UPDATES}. */
public class CheckDependencyUpdatesActionHandler implements FrontendActionHandler<String> {

    private final DependencyActionHandlers handlers;

    public CheckDependencyUpdatesActionHandler(DependencyActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CHECK_DEPENDENCY_UPDATES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleCheckUpdates(payload);
    }
}
