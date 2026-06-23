package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#INSTALL_DEPENDENCY}. */
public class InstallDependencyActionHandler implements FrontendActionHandler<String> {

    private final DependencyActionHandlers handlers;

    public InstallDependencyActionHandler(DependencyActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.INSTALL_DEPENDENCY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleInstall(payload);
    }
}
