package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * 从 MCP 市场一键安装 server(最简 handler,委托 {@link McpMarketActionHandlers})。
 *
 * <p>后端做 riskLevel 安全校验({@code unverified-command} 拒绝),再 {@code upsertMcpServer} 落盘。
 * payload 为 {@code {server, __requestId}} 的 JSON 字符串;下行 {@code MCP_MARKET_INSTALL_RESULT}。
 */
public class InstallMcpFromMarketActionHandler implements FrontendActionHandler<String> {

    private final McpMarketActionHandlers handlers;

    public InstallMcpFromMarketActionHandler(McpMarketActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.INSTALL_MCP_FROM_MARKET;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleInstallMcpFromMarket(payload);
    }
}
