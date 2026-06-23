package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for TAB_LOADING_CHANGED action.
 * Delegates to {@link WindowActionHandlers}.
 */
public class TabLoadingChangedActionHandler implements FrontendActionHandler<String> {

    private final WindowActionHandlers handlers;

    public TabLoadingChangedActionHandler(WindowActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.TAB_LOADING_CHANGED;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleTabLoadingChanged(payload);
    }
}
