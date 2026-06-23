package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for VALIDATE_CODEX_MCP_SERVER action.
 * Delegates to {@link CodexMcpServerActionHandlers}.
 */
public class ValidateCodexMcpServerActionHandler implements FrontendActionHandler<String> {

    private final CodexMcpServerActionHandlers handlers;

    public ValidateCodexMcpServerActionHandler(CodexMcpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.VALIDATE_CODEX_MCP_SERVER;
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
