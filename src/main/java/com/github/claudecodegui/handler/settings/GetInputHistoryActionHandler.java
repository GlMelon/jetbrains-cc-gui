package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.InputHistoryHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_INPUT_HISTORY} (B3 slice: input history). */
public class GetInputHistoryActionHandler implements FrontendActionHandler<String> {

    private final InputHistoryHandler inputHistoryHandler;

    public GetInputHistoryActionHandler(InputHistoryHandler inputHistoryHandler) {
        this.inputHistoryHandler = inputHistoryHandler;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_INPUT_HISTORY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        inputHistoryHandler.handleGetInputHistory();
    }
}
