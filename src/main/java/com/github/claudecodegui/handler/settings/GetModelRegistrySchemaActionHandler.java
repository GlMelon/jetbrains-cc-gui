package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryService;

public final class GetModelRegistrySchemaActionHandler implements FrontendActionHandler<String> {
    private final ModelRegistryService service;

    public GetModelRegistrySchemaActionHandler(ModelRegistryService service) {
        this.service = service;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_MODEL_REGISTRY_SCHEMA;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        String schemaJson = service.getSchema().schema().toString();
        ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY_SCHEMA.value(), ctx.escapeJs(schemaJson));
    }
}
