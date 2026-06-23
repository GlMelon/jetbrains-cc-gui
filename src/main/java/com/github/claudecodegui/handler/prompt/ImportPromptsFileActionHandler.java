package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#IMPORT_PROMPTS_FILE}. */
public class ImportPromptsFileActionHandler implements FrontendActionHandler<String> {

    private final PromptActionHandlers handlers;

    public ImportPromptsFileActionHandler(PromptActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.IMPORT_PROMPTS_FILE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleImportPromptsFile(payload);
    }
}
