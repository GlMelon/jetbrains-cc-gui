package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#LOAD_HISTORY_DATA}. */
public class LoadHistoryDataActionHandler implements FrontendActionHandler<String> {
    private final HistoryActionHandlers handlers;
    public LoadHistoryDataActionHandler(HistoryActionHandlers handlers) { this.handlers = handlers; }
    @Override public UpstreamAction action() { return UpstreamAction.LOAD_HISTORY_DATA; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { handlers.handleLoadHistoryData(payload); }
}
