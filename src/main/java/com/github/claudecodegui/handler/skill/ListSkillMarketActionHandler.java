package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * 列出 Skills 市场某源的 skills(最简 handler,委托 {@link SkillMarketActionHandlers})。
 */
public class ListSkillMarketActionHandler implements FrontendActionHandler<String> {

    private final SkillMarketActionHandlers handlers;

    public ListSkillMarketActionHandler(SkillMarketActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.LIST_SKILL_MARKET;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleListSkillMarket(payload);
    }
}
