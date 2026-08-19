package com.github.claudecodegui.handler.session;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.session.SessionCapabilityService;

import java.util.concurrent.CompletableFuture;

/** Returns the current session capability snapshot without starting MCP Gateway. */
public final class GetSessionCapabilitiesActionHandler implements FrontendActionHandler<String> {
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
        CompletableFuture.runAsync(() -> {
            String json = SessionCapabilityService.build(handlerContext.getProject(), handlerContext.getSession());
            handlerContext.dispatchEvent(
                    DownstreamEvent.SESSION_CAPABILITIES.value(),
                    handlerContext.escapeJs(json)
            );
        });
    }
}
