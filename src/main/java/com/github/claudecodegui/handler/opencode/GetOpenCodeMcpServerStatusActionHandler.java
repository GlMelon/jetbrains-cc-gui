package com.github.claudecodegui.handler.opencode;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for GET_OPENCODE_MCP_SERVER_STATUS action.
 * Delegates to {@link OpenCodeMcpServerActionHandlers}.
 */
public class GetOpenCodeMcpServerStatusActionHandler implements FrontendActionHandler<String> {

    private final OpenCodeMcpServerActionHandlers handlers;

    public GetOpenCodeMcpServerStatusActionHandler(OpenCodeMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_OPENCODE_MCP_SERVER_STATUS;
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
