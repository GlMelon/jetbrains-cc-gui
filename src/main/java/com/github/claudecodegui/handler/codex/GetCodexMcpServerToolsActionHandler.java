package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for GET_CODEX_MCP_SERVER_TOOLS action.
 * Delegates to {@link CodexMcpServerActionHandlers}.
 */
public class GetCodexMcpServerToolsActionHandler implements FrontendActionHandler<String> {

    private final CodexMcpServerActionHandlers handlers;

    public GetCodexMcpServerToolsActionHandler(CodexMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CODEX_MCP_SERVER_TOOLS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetMcpServerTools(payload);
    }
}
