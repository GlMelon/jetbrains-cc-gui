package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SET_PROMPT_ENHANCER_CONFIG} (B3 slice: project-config). */
public class SetPromptEnhancerConfigActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public SetPromptEnhancerConfigActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.SET_PROMPT_ENHANCER_CONFIG; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleSetPromptEnhancerConfig(payload); }
}
