package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.RuntimePolicyHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/** Typed handler for {@link UpstreamAction#GET_RUNTIME_POLICY} (B3 slice: runtime-policy). */
public class GetRuntimePolicyActionHandler implements FrontendActionHandler<String> {
    private final RuntimePolicyHandler delegate;
    public GetRuntimePolicyActionHandler(RuntimePolicyHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_RUNTIME_POLICY; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetRuntimePolicy(); }
}
