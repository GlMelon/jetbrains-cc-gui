package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#SET_SMITHERY_API_KEY}
 * (MCP 市场 Smithery Registry API Key 写入;空串=清除)。
 */
public class SetSmitheryApiKeyActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public SetSmitheryApiKeyActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.SET_SMITHERY_API_KEY; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleSetSmitheryApiKey(payload); }
}
