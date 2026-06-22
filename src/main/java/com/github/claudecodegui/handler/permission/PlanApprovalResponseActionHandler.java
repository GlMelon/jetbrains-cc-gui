package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@code plan_approval_response} messages from the frontend.
 * Delegates to {@link PermissionActionHandlers} for shared state management.
 */
public class PlanApprovalResponseActionHandler implements FrontendActionHandler<String> {

    private final PermissionActionHandlers handlers;

    public PlanApprovalResponseActionHandler(PermissionActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.PLAN_APPROVAL_RESPONSE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext ctx) {
        handlers.handlePlanApprovalResponse(payload);
    }
}
