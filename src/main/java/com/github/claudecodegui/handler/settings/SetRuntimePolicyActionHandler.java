package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.RuntimePolicyHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#SET_RUNTIME_POLICY} (B3 slice: runtime-policy). */
public class SetRuntimePolicyActionHandler implements FrontendActionHandler<String> {
    private final RuntimePolicyHandler delegate;
    public SetRuntimePolicyActionHandler(RuntimePolicyHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.SET_RUNTIME_POLICY; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleSetRuntimePolicy(payload); }
}
