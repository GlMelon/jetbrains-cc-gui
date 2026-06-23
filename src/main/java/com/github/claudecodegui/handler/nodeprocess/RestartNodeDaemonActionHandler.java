package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#RESTART_NODE_DAEMON}. */
public class RestartNodeDaemonActionHandler implements FrontendActionHandler<String> {

    private final NodeProcessActionHandlers handlers;

    public RestartNodeDaemonActionHandler(NodeProcessActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.RESTART_NODE_DAEMON;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleRestartDaemon(payload);
    }
}
