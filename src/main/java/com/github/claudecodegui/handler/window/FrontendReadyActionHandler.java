package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for FRONTEND_READY action.
 * Delegates to {@link WindowActionHandlers}.
 */
public class FrontendReadyActionHandler implements FrontendActionHandler<String> {

    private final WindowActionHandlers handlers;

    public FrontendReadyActionHandler(WindowActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.FRONTEND_READY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleFrontendReady();
    }
}
