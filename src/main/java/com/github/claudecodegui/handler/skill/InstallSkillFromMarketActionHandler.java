package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * 从 Skills 市场安装 skill(最简 handler,委托 {@link SkillMarketActionHandlers})。
 */
public class InstallSkillFromMarketActionHandler implements FrontendActionHandler<String> {

    private final SkillMarketActionHandlers handlers;

    public InstallSkillFromMarketActionHandler(SkillMarketActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.INSTALL_SKILL_FROM_MARKET;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleInstallSkillFromMarket(payload);
    }
}
