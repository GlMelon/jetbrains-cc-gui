package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.UpstreamAction;

/**
 * Typed handler for {@link UpstreamAction#RELOAD_MCP_GATEWAY}
 * (MCP 面板"重载 Gateway"按钮:硬重置 + 重建 gateway 进程,自动加载失败时手动恢复,免重启 IDE)。
 */
public class ReloadMcpGatewayActionHandler implements FrontendActionHandler<String> {

    private final McpServerActionHandlers handlers;

    public ReloadMcpGatewayActionHandler(McpServerActionHandlers handlers) {
        this.handlers = handlers;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.RELOAD_MCP_GATEWAY;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        handlers.handleReloadMcpGateway();
    }
}
