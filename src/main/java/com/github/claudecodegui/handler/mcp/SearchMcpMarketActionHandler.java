package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#SEARCH_MCP_MARKET}
 * (Smithery Registry 搜索 MCP 服务器;委托 {@link McpMarketActionHandlers})。
 */
public class SearchMcpMarketActionHandler implements FrontendActionHandler<String> {
    private final McpMarketActionHandlers delegate;
    public SearchMcpMarketActionHandler(McpMarketActionHandlers delegate) { this.delegate = delegate; }
    @Override public UpstreamAction action() { return UpstreamAction.SEARCH_MCP_MARKET; }
    @Override public Class<String> payloadType() { return String.class; }
    @Override public void handle(String payload, FrontendActionContext context) { delegate.handleSearchMcpMarket(payload); }
}
