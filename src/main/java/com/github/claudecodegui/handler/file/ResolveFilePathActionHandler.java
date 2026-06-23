package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#RESOLVE_FILE_PATH}. */
public class ResolveFilePathActionHandler implements FrontendActionHandler<String> {

    private final FileActionHandlers handlers;

    public ResolveFilePathActionHandler(FileActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.RESOLVE_FILE_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleResolveFilePath(payload);
    }
}
