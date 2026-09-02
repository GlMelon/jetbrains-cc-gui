package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.service.CodexSubscriptionQuotaService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 get_codex_subscription_quota 的字符串派发
 * + CodexSubscriptionQuotaHandler 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>复用 app 级 {@link CodexSubscriptionQuotaService} 单例,异步取快照后经单一
 * {@code codex.subscription_quota} 事件回传前端(成功/失败共用同一出口,与旧
 * CodexSubscriptionQuotaHandler 逐字等价)。
 */
public final class GetCodexSubscriptionQuotaActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetCodexSubscriptionQuotaActionHandler.class);
    private static final Gson GSON = new Gson();

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CODEX_SUBSCRIPTION_QUOTA;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        ApplicationManager.getApplication()
                .getService(CodexSubscriptionQuotaService.class)
                .getQuotaSnapshot()
                .thenAccept(snapshot -> sendPayload(ctx, snapshot))
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    LOG.warn("[GetCodexSubscriptionQuotaActionHandler] Failed to load quota: " + cause.getMessage());
                    sendPayload(ctx, CodexSubscriptionQuotaService.buildUnavailablePayload(
                            cause.getMessage(), System.currentTimeMillis()));
                    return null;
                });
    }

    private void sendPayload(HandlerContext ctx, JsonObject payload) {
        ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent(DownstreamEvent.CODEX_SUBSCRIPTION_QUOTA.value(), GSON.toJson(payload)));
    }
}
