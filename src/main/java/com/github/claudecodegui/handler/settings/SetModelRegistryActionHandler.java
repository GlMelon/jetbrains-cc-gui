package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.ModelRegistryResult;
import com.github.claudecodegui.settings.ModelRegistryService;

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
        // payload 解析与异常处理下沉到 service:畸形 JSON 在 service 的 try 内被捕获并产出
        // failure("保存失败: ..."),与原 SettingsHandler.handleSetModelRegistry 逐字等价。
        ModelRegistryResult result = service.setRegistry(payload);
        ModelRegistryEvents.dispatchUpdated(ctx, result);
        if (result.success()) {
            ModelRegistryEvents.dispatchRegistry(ctx, result);
        }
    }
}
