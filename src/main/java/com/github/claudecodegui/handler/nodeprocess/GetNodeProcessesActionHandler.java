package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_NODE_PROCESSES}. */
public class GetNodeProcessesActionHandler implements FrontendActionHandler<String> {

    private final NodeProcessActionHandlers handlers;

    public GetNodeProcessesActionHandler(NodeProcessActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_NODE_PROCESSES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetNodeProcesses();
    }
}
