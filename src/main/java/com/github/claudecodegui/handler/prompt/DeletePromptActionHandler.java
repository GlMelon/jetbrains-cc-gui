package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#DELETE_PROMPT}. */
public class DeletePromptActionHandler implements FrontendActionHandler<String> {

    private final PromptActionHandlers handlers;

    public DeletePromptActionHandler(PromptActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.DELETE_PROMPT;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleDeletePrompt(payload);
    }
}
