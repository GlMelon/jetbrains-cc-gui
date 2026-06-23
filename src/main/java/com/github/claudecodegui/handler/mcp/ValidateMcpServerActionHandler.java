package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for VALIDATE_MCP_SERVER action.
 * Delegates to {@link McpServerActionHandlers}.
 */
public class ValidateMcpServerActionHandler implements FrontendActionHandler<String> {

    private final McpServerActionHandlers handlers;

    public ValidateMcpServerActionHandler(McpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.VALIDATE_MCP_SERVER;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleValidateMcpServer(payload);
    }
}
