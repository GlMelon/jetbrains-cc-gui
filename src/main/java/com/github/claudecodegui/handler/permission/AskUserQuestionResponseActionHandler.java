package com.github.claudecodegui.handler.permission;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@code ask_user_question_response} messages from the frontend.
 * Delegates to {@link PermissionActionHandlers} for shared state management.
 */
public class AskUserQuestionResponseActionHandler implements FrontendActionHandler<String> {

    private final PermissionActionHandlers handlers;

    public AskUserQuestionResponseActionHandler(PermissionActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.ASK_USER_QUESTION_RESPONSE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext ctx) {
        handlers.handleAskUserQuestionResponse(payload);
    }
}
