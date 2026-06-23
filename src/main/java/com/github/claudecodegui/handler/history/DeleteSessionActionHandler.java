package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#DELETE_SESSION}. */
public class DeleteSessionActionHandler implements FrontendActionHandler<String> {
    private final HistoryActionHandlers handlers;
    public DeleteSessionActionHandler(HistoryActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.DELETE_SESSION; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleDeleteSession(payload); }
}
