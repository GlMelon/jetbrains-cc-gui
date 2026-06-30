package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.mcp.McpGatewayService;

/**
 * Claude CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_CLAUDE},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class ClaudeCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;

    public ClaudeCliSessionFactory() {
        this(null);
    }

    public ClaudeCliSessionFactory(McpGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_CLAUDE;
    }

    @Override
    public CliSession create(String tabId) {
        return new ClaudeCliSession(tabId, gatewayService);
    }
}
