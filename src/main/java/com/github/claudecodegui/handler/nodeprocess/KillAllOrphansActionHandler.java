package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#KILL_ALL_ORPHANS}. */
public class KillAllOrphansActionHandler implements FrontendActionHandler<String> {

    private final NodeProcessActionHandlers handlers;

    public KillAllOrphansActionHandler(NodeProcessActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.KILL_ALL_ORPHANS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleKillAllOrphans();
    }
}
