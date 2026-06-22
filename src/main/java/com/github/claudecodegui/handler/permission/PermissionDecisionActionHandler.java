package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@code permission_decision} messages from the frontend.
 * Delegates to {@link PermissionActionHandlers} for shared state management.
 */
public class PermissionDecisionActionHandler implements FrontendActionHandler<String> {

    private final PermissionActionHandlers handlers;

    public PermissionDecisionActionHandler(PermissionActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.PERMISSION_DECISION;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext ctx) {
        handlers.handlePermissionDecision(payload);
    }
}
