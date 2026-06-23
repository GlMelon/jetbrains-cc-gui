package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for GET_MCP_SERVER_STATUS action.
 * Delegates to {@link McpServerActionHandlers}.
 */
public class GetMcpServerStatusActionHandler implements FrontendActionHandler<String> {

    private final McpServerActionHandlers handlers;

    public GetMcpServerStatusActionHandler(McpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_MCP_SERVER_STATUS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetMcpServerStatus();
    }
}
