package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigResult;
import com.github.claudecodegui.settings.AppearanceConfigService;

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
        // payload 解析下沉到 service:畸形 JSON 在 service 的 try 内被捕获后仍回读权威配置,
        // 与原 SettingsHandler.handleSetAppearanceConfig + pushAppearanceConfig 逐字等价。
        AppearanceConfigResult result = service.apply(payload);
        ctx.dispatchEvent(DownstreamEvent.APPEARANCE_APPLY.value(),
                ctx.escapeJs(result.configJson()));
    }
}
