package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.session.SessionCapabilityService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/** Returns the current session capability snapshot without starting MCP Gateway. */
public final class GetSessionCapabilitiesActionHandler implements FrontendActionHandler<String> {
    private static final Logger LOG = Logger.getInstance(GetSessionCapabilitiesActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_SESSION_CAPABILITIES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext handlerContext = context.handlerContext();
        com.github.claudecodegui.session.ClaudeSession session = handlerContext.getSession();
        String backendSessionId = session != null ? session.getSessionId() : null;
        String backendProvider = session != null ? session.getProvider() : null;
        CompletableFuture.runAsync(() -> {
            String json = SessionCapabilityService.build(handlerContext.getProject(), session);
            // 常规可观测:摘要即可(全量 payload 含会话/健康数据,排查期临时日志已移除)。
            LOG.info("[SessionCapabilities] dispatched, length=" + json.length()
                    + ", backendSessionId=" + backendSessionId
                    + ", backendProvider=" + backendProvider);
            handlerContext.dispatchEvent(
                    DownstreamEvent.SESSION_CAPABILITIES.value(),
                    handlerContext.escapeJs(json)
            );
        }, AppExecutorUtil.getAppExecutorService());
    }
}
