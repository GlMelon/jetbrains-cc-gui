package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.avatar.AvatarConfigResult;
import com.github.claudecodegui.settings.avatar.AvatarConfigService;

public final class SetAvatarConfigActionHandler implements FrontendActionHandler<String> {
    private final AvatarConfigService service;

    public SetAvatarConfigActionHandler(AvatarConfigService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.AVATAR_SET_CONFIG;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        AvatarConfigResult result = service.applyConfig(payload);
        ctx.dispatchEvent(DownstreamEvent.AVATAR_CONFIG_APPLY.value(), result.configJson());
    }
}
