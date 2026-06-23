package com.github.claudecodegui.handler.prompt;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#EXPORT_PROMPTS}. */
public class ExportPromptsActionHandler implements FrontendActionHandler<String> {

    private final PromptActionHandlers handlers;

    public ExportPromptsActionHandler(PromptActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.EXPORT_PROMPTS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleExportPrompts(payload);
    }
}
