package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for GET_MCP_SERVER_TOOLS action.
 * Delegates to {@link McpServerActionHandlers}.
 */
public class GetMcpServerToolsActionHandler implements FrontendActionHandler<McpServerToolsRequest> {

    private final McpServerActionHandlers handlers;

    public GetMcpServerToolsActionHandler(McpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_MCP_SERVER_TOOLS;
    }

    @Override
    public Class<McpServerToolsRequest> payloadType() {
        return McpServerToolsRequest.class;
    }

    @Override
    public void handle(McpServerToolsRequest payload, FrontendActionContext context) {
        handlers.handleGetMcpServerTools(payload);
    }
}
