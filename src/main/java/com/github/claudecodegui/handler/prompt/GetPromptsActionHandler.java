package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_PROMPTS}. */
public class GetPromptsActionHandler implements FrontendActionHandler<String> {

    private final PromptActionHandlers handlers;

    public GetPromptsActionHandler(PromptActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_PROMPTS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleGetPrompts(payload);
    }
}
