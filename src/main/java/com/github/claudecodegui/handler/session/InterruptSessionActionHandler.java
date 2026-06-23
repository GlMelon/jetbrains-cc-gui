package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for INTERRUPT_SESSION action.
 * Delegates to {@link SessionActionHandlers}.
 */
public class InterruptSessionActionHandler implements FrontendActionHandler<String> {

    private final SessionActionHandlers handlers;

    public InterruptSessionActionHandler(SessionActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.INTERRUPT_SESSION;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleInterruptSession();
    }
}
