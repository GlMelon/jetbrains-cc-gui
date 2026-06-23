package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_LINKIFY_CAPABILITIES}. */
public class GetLinkifyCapabilitiesActionHandler implements FrontendActionHandler<String> {

    private final FileActionHandlers handlers;

    public GetLinkifyCapabilitiesActionHandler(FileActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_LINKIFY_CAPABILITIES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetLinkifyCapabilities();
    }
}
