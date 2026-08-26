package com.github.claudecodegui.cli.kimi;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.MarkerRunOnceCliSession;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;

/**
 * Kimi CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_KIMI},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * 会话实现合并至 {@link MarkerRunOnceCliSession}(marker 协议,grok/kimi/pi 共用)。
 */
public class KimiCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;

    public KimiCliSessionFactory() {
        this(null);
    }

    public KimiCliSessionFactory(McpGatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_KIMI;
    }

    @Override
    public CliSession create(String tabId) {
        return new MarkerRunOnceCliSession(ProviderType.KIMI, tabId, gatewayService);
    }
}
