package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#DEEP_SEARCH_HISTORY}. */
public class DeepSearchHistoryActionHandler implements FrontendActionHandler<String> {
    private final HistoryActionHandlers handlers;
    public DeepSearchHistoryActionHandler(HistoryActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.DEEP_SEARCH_HISTORY; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleDeepSearchHistory(payload); }
}
