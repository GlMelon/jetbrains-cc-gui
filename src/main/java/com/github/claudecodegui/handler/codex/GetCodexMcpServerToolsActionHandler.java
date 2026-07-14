package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.mcp.McpServerToolsRequest;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for GET_CODEX_MCP_SERVER_TOOLS action.
 * Delegates to {@link CodexMcpServerActionHandlers}.
 */
public class GetCodexMcpServerToolsActionHandler implements FrontendActionHandler<McpServerToolsRequest> {

    private final CodexMcpServerActionHandlers handlers;

    public GetCodexMcpServerToolsActionHandler(CodexMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CODEX_MCP_SERVER_TOOLS;
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
