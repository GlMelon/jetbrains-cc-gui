package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for REFRESH_SLASH_COMMANDS action.
 * Delegates to {@link WindowActionHandlers}.
 */
public class RefreshSlashCommandsActionHandler implements FrontendActionHandler<String> {

    private final WindowActionHandlers handlers;

    public RefreshSlashCommandsActionHandler(WindowActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.REFRESH_SLASH_COMMANDS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleRefreshSlashCommands();
    }
}
