package com.github.claudecodegui.handler.diff;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SHOW_EDIT_FULL_DIFF}. */
public class ShowEditFullDiffActionHandler implements FrontendActionHandler<String> {

    private final DiffActionHandlers handlers;

    public ShowEditFullDiffActionHandler(DiffActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SHOW_EDIT_FULL_DIFF;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.dispatch("show_edit_full_diff", payload);
    }
}
