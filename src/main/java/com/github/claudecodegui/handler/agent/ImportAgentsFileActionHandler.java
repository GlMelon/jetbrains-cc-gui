package com.github.claudecodegui.handler.agent;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

public class ImportAgentsFileActionHandler implements FrontendActionHandler<String> {

    private final AgentActionHandlers handlers;

    public ImportAgentsFileActionHandler(AgentActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.IMPORT_AGENTS_FILE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleImportAgentsFile();
    }
}
