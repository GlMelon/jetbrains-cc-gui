package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.UserLanguageHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SET_USER_LANGUAGE} (B3 slice: user-language). */
public class SetUserLanguageActionHandler implements FrontendActionHandler<String> {
    private final UserLanguageHandler delegate;
    public SetUserLanguageActionHandler(UserLanguageHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.SET_USER_LANGUAGE; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleSetUserLanguage(payload); }
}
