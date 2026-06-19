package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigResult;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

public final class SetAppearanceConfigActionHandler implements FrontendActionHandler<String> {
    private final AppearanceConfigService service;

    public SetAppearanceConfigActionHandler(AppearanceConfigService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_APPEARANCE_CONFIG;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        JsonObject json = GsonHolder.GSON.fromJson(payload, JsonObject.class);
        AppearanceConfigResult result = service.apply(json);
        ctx.dispatchEvent(DownstreamEvent.APPEARANCE_APPLY.value(),
                ctx.escapeJs(result.configJson()));
    }
}
