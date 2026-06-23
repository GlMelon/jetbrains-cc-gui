package com.github.claudecodegui.handler.agent;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

public class AddAgentActionHandler implements FrontendActionHandler<String> {

    private final AgentActionHandlers handlers;

    public AddAgentActionHandler(AgentActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.ADD_AGENT;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleAddAgent(payload);
    }
}
