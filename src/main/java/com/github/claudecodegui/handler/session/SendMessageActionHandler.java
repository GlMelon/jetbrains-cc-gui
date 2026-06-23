package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for SEND_MESSAGE action.
 * Delegates to {@link SessionActionHandlers}.
 */
public class SendMessageActionHandler implements FrontendActionHandler<String> {

    private final SessionActionHandlers handlers;

    public SendMessageActionHandler(SessionActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SEND_MESSAGE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleSendMessage(payload);
    }
}
