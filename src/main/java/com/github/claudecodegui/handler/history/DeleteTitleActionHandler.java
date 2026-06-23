package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#DELETE_TITLE}. */
public class DeleteTitleActionHandler implements FrontendActionHandler<String> {
    private final HistoryActionHandlers handlers;
    public DeleteTitleActionHandler(HistoryActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.DELETE_TITLE; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleDeleteTitle(payload); }
}
