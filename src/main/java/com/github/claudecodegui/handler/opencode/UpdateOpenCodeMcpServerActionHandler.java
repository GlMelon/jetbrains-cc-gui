package com.github.claudecodegui.handler.opencode;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for UPDATE_OPENCODE_MCP_SERVER action.
 * Delegates to {@link OpenCodeMcpServerActionHandlers}.
 */
public class UpdateOpenCodeMcpServerActionHandler implements FrontendActionHandler<String> {

    private final OpenCodeMcpServerActionHandlers handlers;

    public UpdateOpenCodeMcpServerActionHandler(OpenCodeMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.UPDATE_OPENCODE_MCP_SERVER;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleUpdateMcpServer(payload);
    }
}
