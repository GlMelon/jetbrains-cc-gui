package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#GET_MCP_MARKET_DETAIL}
 * (Smithery Registry 获取单个 MCP server 详情+连接配置;委托 {@link McpMarketActionHandlers})。
 */
public class GetMcpMarketDetailActionHandler implements FrontendActionHandler<String> {
    private final McpMarketActionHandlers delegate;
    public GetMcpMarketDetailActionHandler(McpMarketActionHandlers delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.GET_MCP_MARKET_DETAIL; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleGetMcpMarketDetail(payload); }
}
