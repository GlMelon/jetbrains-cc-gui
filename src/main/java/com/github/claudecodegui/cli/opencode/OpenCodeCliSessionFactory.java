package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.CliPersistentFeatureFlags;
import com.github.claudecodegui.cli.opencode.serve.OpenCodeServeManager;
import com.github.claudecodegui.cli.opencode.serve.OpenCodeServeSession;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;

/**
 * OpenCode CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_OPENCODE},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * <p>
 * 通道分流:serve 子开关({@link CliPersistentFeatureFlags#isOpenCodeServeEnabled()})
 * 开且 manager 可用 → {@link OpenCodeServeSession}(serve + HTTP/SSE,token 级增量,
 * 轮级失败自动降级 one-shot);否则 → {@link OpenCodeCliSession}(one-shot 纯路径)。
 */
public class OpenCodeCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;
    private final OpenCodeServeManager serveManager;

    public OpenCodeCliSessionFactory() {
        this(null, null, null);
    }

    public OpenCodeCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null, null);
    }

    public OpenCodeCliSessionFactory(McpGatewayService gatewayService,
                                     LifecycleObservabilityService lifecycleService) {
        this(gatewayService, lifecycleService, null);
    }

    /**
     * Project-aware 构造:注入 serve 管理器。
     * serveManager 为 null(测试/无 Project 路径)时永远走 one-shot,自然降级。
     */
    public OpenCodeCliSessionFactory(McpGatewayService gatewayService,
                                     LifecycleObservabilityService lifecycleService,
                                     OpenCodeServeManager serveManager) {
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
        this.serveManager = serveManager;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_OPENCODE;
    }

    @Override
    public CliSession create(String tabId) {
        if (serveManager != null && CliPersistentFeatureFlags.isOpenCodeServeEnabled()) {
            return new OpenCodeServeSession(tabId, serveManager, gatewayService, lifecycleService);
        }
        return new OpenCodeCliSession(tabId, gatewayService, lifecycleService);
    }
}
