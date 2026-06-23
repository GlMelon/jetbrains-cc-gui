package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.InputHistoryHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#CLEAR_INPUT_HISTORY} (B3 slice: input history). */
public class ClearInputHistoryActionHandler implements FrontendActionHandler<String> {

    private final InputHistoryHandler inputHistoryHandler;

    public ClearInputHistoryActionHandler(InputHistoryHandler inputHistoryHandler) {
        this.inputHistoryHandler = inputHistoryHandler;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CLEAR_INPUT_HISTORY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        inputHistoryHandler.handleClearInputHistory();
    }
}
