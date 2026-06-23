package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Handler for HEARTBEAT action.
 * Delegates to {@link WindowActionHandlers}.
 */
public class HeartbeatActionHandler implements FrontendActionHandler<String> {

    private final WindowActionHandlers handlers;

    public HeartbeatActionHandler(WindowActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.HEARTBEAT;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleHeartbeat(payload);
    }
}
