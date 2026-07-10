package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#GET_SKILL_MARKET_DETAIL}
 * (GitHub raw 下载单个 SKILL.md 解析 frontmatter 详情;委托 {@link SkillMarketActionHandlers})。
 */
public class GetSkillMarketDetailActionHandler implements FrontendActionHandler<String> {
    private final SkillMarketActionHandlers delegate;
    public GetSkillMarketDetailActionHandler(SkillMarketActionHandlers delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_SKILL_MARKET_DETAIL; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetSkillMarketDetail(payload); }
}
