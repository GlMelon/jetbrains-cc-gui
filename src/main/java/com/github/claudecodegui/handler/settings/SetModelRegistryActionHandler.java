package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

public final class SetModelRegistryActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public SetModelRegistryActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_MODEL_REGISTRY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        JsonObject json = GsonHolder.GSON.fromJson(payload, JsonObject.class);
        ModelRegistryResult result = service.setRegistry(json);
        ModelRegistryEvents.dispatchUpdated(ctx, result);
        if (result.success()) {
            ModelRegistryEvents.dispatchRegistry(ctx, result);
        }
    }
}
