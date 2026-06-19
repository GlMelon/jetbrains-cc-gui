package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.settings.ModelRegistryService;

public final class ResetModelRegistryActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public ResetModelRegistryActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.RESET_MODEL_REGISTRY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        ModelRegistryResult result = service.resetRegistry();
        ModelRegistryEvents.dispatchUpdated(ctx, result);
        if (result.success()) {
            ModelRegistryEvents.dispatchRegistry(ctx, result);
        }
    }
}
