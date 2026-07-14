package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for updating the AskUserQuestion reminder notification setting. */
public class SetAskUserQuestionNotificationEnabledActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;

    public SetAskUserQuestionNotificationEnabledActionHandler(ProjectConfigHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_ASK_USER_QUESTION_NOTIFICATION_ENABLED;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        delegate.handleSetAskUserQuestionNotificationEnabled(payload);
    }
}
