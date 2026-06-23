package com.github.claudecodegui.handler.diff;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SHOW_DIFF}. */
public class ShowDiffActionHandler implements FrontendActionHandler<String> {

    private final DiffActionHandlers handlers;

    public ShowDiffActionHandler(DiffActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SHOW_DIFF;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.dispatch("show_diff", payload);
    }
}
