package com.github.claudecodegui.cli.minimax;

import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;

/**
 * MiniMax Code CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_MINIMAX},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * 会话实现为 {@link MiniMaxRunOnceCliSession}(exec stream-json 方言,直 spawn 原生 CLI)。
 */
public class MiniMaxCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;

    public MiniMaxCliSessionFactory() {
        this(null, null);
    }

    public MiniMaxCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null);
    }

    public MiniMaxCliSessionFactory(McpGatewayService gatewayService,
                                    LifecycleObservabilityService lifecycleService) {
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_MINIMAX;
    }

    @Override
    public CliSession create(String tabId) {
        return new MiniMaxRunOnceCliSession(tabId, gatewayService, lifecycleService);
    }
}
