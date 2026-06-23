package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_PROJECT_INFO}. */
public class GetProjectInfoActionHandler implements FrontendActionHandler<String> {

    private final PromptActionHandlers handlers;

    public GetProjectInfoActionHandler(PromptActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_PROJECT_INFO;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetProjectInfo(payload);
    }
}
