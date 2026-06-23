package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for CREATE_NEW_SESSION action.
 * Delegates to {@link WindowActionHandlers}.
 */
public class CreateNewSessionActionHandler implements FrontendActionHandler<String> {

    private final WindowActionHandlers handlers;

    public CreateNewSessionActionHandler(WindowActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CREATE_NEW_SESSION;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleCreateNewSession();
    }
}
