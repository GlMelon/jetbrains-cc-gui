package com.github.claudecodegui.cli.grok;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.mcp.McpGatewayService;

/**
 * Grok CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_GROK},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class GrokCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;

    public GrokCliSessionFactory() {
        this(null);
    }

    public GrokCliSessionFactory(McpGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_GROK;
    }

    @Override
    public CliSession create(String tabId) {
        return new GrokCliSession(tabId, gatewayService);
    }
}
