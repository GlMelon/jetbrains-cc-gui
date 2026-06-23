package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SAVE_IMPORTED_PROMPTS}. */
public class SaveImportedPromptsActionHandler implements FrontendActionHandler<String> {

    private final PromptActionHandlers handlers;

    public SaveImportedPromptsActionHandler(PromptActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SAVE_IMPORTED_PROMPTS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleSaveImportedPrompts(payload);
    }
}
