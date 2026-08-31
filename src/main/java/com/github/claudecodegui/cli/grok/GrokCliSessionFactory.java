package com.github.claudecodegui.cli.grok;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;

/**
 * Grok CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_GROK},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * 会话实现为 {@link GrokRunOnceCliSession}(headless streaming-json 方言,直 spawn 原生 CLI)。
 */
public class GrokCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;

    public GrokCliSessionFactory() {
        this(null, null);
    }

    public GrokCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null);
    }

    public GrokCliSessionFactory(McpGatewayService gatewayService,
                                 LifecycleObservabilityService lifecycleService) {
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_GROK;
    }

    @Override
    public CliSession create(String tabId) {
        return new GrokRunOnceCliSession(tabId, gatewayService, lifecycleService);
    }
}
