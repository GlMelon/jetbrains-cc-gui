package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.InputHistoryHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#RECORD_INPUT_HISTORY} (B3 slice: input history). */
public class RecordInputHistoryActionHandler implements FrontendActionHandler<String> {

    private final InputHistoryHandler inputHistoryHandler;

    public RecordInputHistoryActionHandler(InputHistoryHandler inputHistoryHandler) {
        this.inputHistoryHandler = inputHistoryHandler;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.RECORD_INPUT_HISTORY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        inputHistoryHandler.handleRecordInputHistory(payload);
    }
}
