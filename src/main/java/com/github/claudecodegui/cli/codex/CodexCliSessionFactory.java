package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;

/**
 * Codex CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_CODEX},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 */
public class CodexCliSessionFactory implements CliSessionFactory {
    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;

    public CodexCliSessionFactory() {
        this(null, null);
    }

    public CodexCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null);
    }

    public CodexCliSessionFactory(McpGatewayService gatewayService,
                                  LifecycleObservabilityService lifecycleService) {
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_CODEX;
    }

    @Override
    public CliSession create(String tabId) {
        return new CodexCliSession(tabId, gatewayService, lifecycleService);
    }
}
