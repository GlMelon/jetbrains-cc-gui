package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#PRINT_SESSION_PDF}. */
public class PrintSessionPdfActionHandler implements FrontendActionHandler<String> {
    private final HistoryActionHandlers handlers;

    public PrintSessionPdfActionHandler(HistoryActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.PRINT_SESSION_PDF;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handlePrintSessionPdf(payload);
    }
}
