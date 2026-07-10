package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#GET_SMITHERY_API_KEY}
 * (MCP 市场 Smithery Registry API Key 配置读取;下行返回掩码,不回传明文)。
 */
public class GetSmitheryApiKeyActionHandler implements FrontendActionHandler<String> {
    private final ProjectConfigHandler delegate;
    public GetSmitheryApiKeyActionHandler(ProjectConfigHandler delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_SMITHERY_API_KEY; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetSmitheryApiKey(); }
}
