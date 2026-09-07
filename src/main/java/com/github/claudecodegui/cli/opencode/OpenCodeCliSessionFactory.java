package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.common.CliPersistentFeatureFlags;
import com.github.claudecodegui.cli.opencode.serve.OpenCodeServeManager;
import com.github.claudecodegui.cli.opencode.serve.OpenCodeServeSession;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
    private static final Logger LOG = Logger.getInstance(OpenCodeCliSessionFactory.class);

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
            return new OpenCodeServeSession(tabId, serveManager, gatewayService, lifecycleService,
                    buildServePermissionGate(serveManager));
        }
        return new OpenCodeCliSession(tabId, gatewayService, lifecycleService);
    }

    /**
     * serve 交互式权限闸口:把 permission.asked 转发到 PermissionService(决策记忆 +
     * 前端对话框),决策映射为 serve 应答(ALLOW→once / ALLOW_ALWAYS→always / DENY→reject)。
     * 闸口自身保守:实例解析失败 / 任何异常 → "reject"。
     */
    private static OpenCodeServeSession.ServePermissionGate buildServePermissionGate(
            OpenCodeServeManager serveManager) {
        return (toolName, inputs, cwd) -> {
            try {
                String bridgeSessionId = com.github.claudecodegui.bridge.NodeService.getInstance().getSessionId();
                if (bridgeSessionId == null || bridgeSessionId.isEmpty()) {
                    return CompletableFuture.completedFuture("reject");
                }
                PermissionService permissionService =
                        PermissionService.getInstance(serveManager.project(), bridgeSessionId);
                CompletionStage<PermissionService.PermissionResponse> decision =
                        permissionService.requestInteractivePermission(toolName, inputs, cwd);
                return decision.thenApply(OpenCodeCliSessionFactory::toServePermissionResponse);
            } catch (Exception | LinkageError e) {
                LOG.warn("[OpenCodeCliSessionFactory] serve permission gate failed: " + e.getMessage());
                return CompletableFuture.completedFuture("reject");
            }
        };
    }

    private static String toServePermissionResponse(PermissionService.PermissionResponse decision) {
        if (decision == PermissionService.PermissionResponse.ALLOW) {
            return "once";
        }
        if (decision == PermissionService.PermissionResponse.ALLOW_ALWAYS) {
            return "always";
        }
        return "reject";
    }
}
