package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.MarkerRunOnceCliSession;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * Pi CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_PI},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * 会话实现合并至 {@link MarkerRunOnceCliSession}(marker 协议,grok/kimi/pi 共用)。
 */
public class PiCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;

    public PiCliSessionFactory() {
        this(null);
    }

    public PiCliSessionFactory(McpGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_PI;
    }

    @Override
    public CliSession create(String tabId) {
        return new MarkerRunOnceCliSession(ProviderType.PI, tabId, gatewayService);
    }
}
