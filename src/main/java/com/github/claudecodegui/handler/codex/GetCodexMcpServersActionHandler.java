package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for GET_CODEX_MCP_SERVERS action.
 * Delegates to {@link CodexMcpServerActionHandlers}.
 */
public class GetCodexMcpServersActionHandler implements FrontendActionHandler<String> {

    private final CodexMcpServerActionHandlers handlers;

    public GetCodexMcpServersActionHandler(CodexMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CODEX_MCP_SERVERS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetMcpServers();
    }
}
