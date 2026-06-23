package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.InputHistoryHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#DELETE_INPUT_HISTORY_ITEM} (B3 slice: input history). */
public class DeleteInputHistoryItemActionHandler implements FrontendActionHandler<String> {

    private final InputHistoryHandler inputHistoryHandler;

    public DeleteInputHistoryItemActionHandler(InputHistoryHandler inputHistoryHandler) {
        this.inputHistoryHandler = inputHistoryHandler;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.DELETE_INPUT_HISTORY_ITEM;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        inputHistoryHandler.handleDeleteInputHistoryItem(payload);
    }
}
