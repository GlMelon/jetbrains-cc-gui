package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#ARCHIVE_SESSIONS}. */
public class ArchiveSessionsActionHandler implements FrontendActionHandler<String> {
    private final HistoryActionHandlers handlers;

    public ArchiveSessionsActionHandler(HistoryActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.ARCHIVE_SESSIONS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleArchiveSessions(payload);
    }
}
