package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for SEND_MESSAGE_WITH_ATTACHMENTS action.
 * Delegates to {@link SessionActionHandlers}.
 */
public class SendMessageWithAttachmentsActionHandler implements FrontendActionHandler<String> {

    private final SessionActionHandlers handlers;

    public SendMessageWithAttachmentsActionHandler(SessionActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SEND_MESSAGE_WITH_ATTACHMENTS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleSendMessageWithAttachments(payload);
    }
}
