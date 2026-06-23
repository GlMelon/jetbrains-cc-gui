package com.github.claudecodegui.handler.diff;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SHOW_EDITABLE_DIFF}. */
public class ShowEditableDiffActionHandler implements FrontendActionHandler<String> {

    private final DiffActionHandlers handlers;

    public ShowEditableDiffActionHandler(DiffActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SHOW_EDITABLE_DIFF;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.dispatch("show_editable_diff", payload);
    }
}
