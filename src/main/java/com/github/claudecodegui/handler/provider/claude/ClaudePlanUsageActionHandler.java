package com.github.claudecodegui.handler.provider.claude;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.provider.claude.ClaudePlanUsageService;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

/**
 * 处理 {@code get_claude_plan_usage} 轮询:返回缓存的 Claude 订阅用量快照(rate_limit_event 填充),
 * 经 {@code window.updateClaudePlanUsage} 下行。前端在首个 rate_limit_event 到达前隐藏 bar
 * (upstream 设计),无事件时 resolvePlanUsagePayload 返回空快照,bar 保持隐藏,无错。
 * <p>
 * rate_limit_event 事件源填充(ClaudeCliStreamParser/ClaudeMessageHandler 调
 * {@link ClaudePlanUsageService#cacheRateLimitInfo})为批次 E 剩余子项,需确认本地 Claude CLI 解析链路
 * 是否产出该事件(代理后端不输出,真实 OAuth 订阅才输出)。
 */
public class ClaudePlanUsageActionHandler implements FrontendActionHandler<String> {

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CLAUDE_PLAN_USAGE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        JsonObject snapshot = ClaudePlanUsageService.resolvePlanUsagePayload();
        context.handlerContext().callJavaScript("updateClaudePlanUsage",
                context.handlerContext().escapeJs(GsonHolder.GSON.toJson(snapshot)));
    }
}
