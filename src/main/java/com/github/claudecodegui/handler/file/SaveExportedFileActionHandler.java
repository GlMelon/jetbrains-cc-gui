package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Persists a backend-rendered export after validating its format, name, and size. */
public final class SaveExportedFileActionHandler implements FrontendActionHandler<String> {
    @Override
    public UpstreamAction action() {
        return UpstreamAction.SAVE_EXPORTED_FILE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        FileExportUtils.handleSaveExportedFile(context.handlerContext(), payload);
    }
}
