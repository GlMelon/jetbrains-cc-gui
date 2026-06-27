package com.github.claudecodegui.handler.opencode;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * §15.8 §11:{@link UpstreamAction#REFRESH_OPENCODE_MODELS} 的 typed handler,
 * 委托 {@link OpenCodeModelsActionHandlers#handleRefreshOpenCodeModels()}。
 * <p>
 * payload 为空字符串(无参刷新动作);前端 UI defer,本 handler 仅让 action 可达。
 */
public class RefreshOpenCodeModelsActionHandler implements FrontendActionHandler<String> {

    private final OpenCodeModelsActionHandlers handlers;

    public RefreshOpenCodeModelsActionHandler(OpenCodeModelsActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.REFRESH_OPENCODE_MODELS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleRefreshOpenCodeModels();
    }
}
