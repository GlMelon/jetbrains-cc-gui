package com.github.claudecodegui.handler.diff;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#REFRESH_FILE}. */
public class RefreshFileActionHandler implements FrontendActionHandler<String> {

    private final DiffActionHandlers handlers;

    public RefreshFileActionHandler(DiffActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.REFRESH_FILE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.dispatch("refresh_file", payload);
    }
}
