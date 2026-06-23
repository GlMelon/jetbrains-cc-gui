package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.PermissionModeHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_MODE} (B3 slice: permission mode). */
public class GetModeActionHandler implements FrontendActionHandler<String> {

    private final PermissionModeHandler permissionModeHandler;

    public GetModeActionHandler(PermissionModeHandler permissionModeHandler) {
        this.permissionModeHandler = permissionModeHandler;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_MODE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        permissionModeHandler.handleGetMode();
    }
}
