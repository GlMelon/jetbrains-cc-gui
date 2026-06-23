package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for ADD_CODEX_MCP_SERVER action.
 * Delegates to {@link CodexMcpServerActionHandlers}.
 */
public class AddCodexMcpServerActionHandler implements FrontendActionHandler<String> {

    private final CodexMcpServerActionHandlers handlers;

    public AddCodexMcpServerActionHandler(CodexMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.ADD_CODEX_MCP_SERVER;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleAddMcpServer(payload);
    }
}
