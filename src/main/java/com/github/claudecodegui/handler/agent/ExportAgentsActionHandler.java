package com.github.claudecodegui.handler.agent;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

public class ExportAgentsActionHandler implements FrontendActionHandler<String> {

    private final AgentActionHandlers handlers;

    public ExportAgentsActionHandler(AgentActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.EXPORT_AGENTS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleExportAgents(payload);
    }
}
