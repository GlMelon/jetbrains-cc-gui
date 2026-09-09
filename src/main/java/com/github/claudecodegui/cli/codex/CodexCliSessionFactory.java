package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionFactory;
import com.github.claudecodegui.cli.codex.appserver.CodexAppServerManager;
import com.github.claudecodegui.cli.codex.appserver.CodexAppServerSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliPersistentFeatureFlags;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Codex CLI 会话工厂(E1·开闭路由化)。
 * <p>
 * 声明 provider 路由键 {@link CommonConstants#PROVIDER_CODEX},
 * 由 {@link com.github.claudecodegui.cli.CliSessionManager} 注册表查表调用。
 * <p>
 * 通道分流:app-server 子开关({@link CliPersistentFeatureFlags#isCodexAppServerEnabled()})
 * 开且 manager 可用 → {@link CodexAppServerSession}(app-server + stdio JSON-RPC,token 级增量,
 * 轮级失败自动降级 one-shot);否则 → {@link CodexCliSession}(one-shot exec --json 纯路径)。
 */
public class CodexCliSessionFactory implements CliSessionFactory {
    private static final Logger LOG = Logger.getInstance(CodexCliSessionFactory.class);

    private final McpGatewayService gatewayService;
    private final LifecycleObservabilityService lifecycleService;
    private final CodexAppServerManager appServerManager;

    public CodexCliSessionFactory() {
        this(null, null, null);
    }

    public CodexCliSessionFactory(McpGatewayService gatewayService) {
        this(gatewayService, null, null);
    }

    public CodexCliSessionFactory(McpGatewayService gatewayService,
                                  LifecycleObservabilityService lifecycleService) {
        this(gatewayService, lifecycleService, null);
    }

    /**
     * Project-aware 构造:注入 app-server 管理器。
     * appServerManager 为 null(测试/无 Project 路径)时永远走 one-shot,自然降级。
     */
    public CodexCliSessionFactory(McpGatewayService gatewayService,
                                  LifecycleObservabilityService lifecycleService,
                                  CodexAppServerManager appServerManager) {
        this.gatewayService = gatewayService;
        this.lifecycleService = lifecycleService;
        this.appServerManager = appServerManager;
    }

    @Override
    public String provider() {
        return CommonConstants.PROVIDER_CODEX;
    }

    @Override
    public CliSession create(String tabId) {
        if (appServerManager != null && CliPersistentFeatureFlags.isCodexAppServerEnabled()) {
            return new CodexAppServerSession(tabId, appServerManager, gatewayService, lifecycleService,
                    buildAppServerPermissionGate(appServerManager));
        }
        return new CodexCliSession(tabId, gatewayService, lifecycleService);
    }

    /**
     * app-server 交互式权限闸口:把审批请求转发到 PermissionService(决策记忆 +
     * 前端对话框),决策映射为 app-server 应答(ALLOW→accept / ALLOW_ALWAYS→acceptForSession /
     * DENY→decline)。闸口自身保守:实例解析失败 / 任何异常 → "decline"。
     */
    private static CodexAppServerSession.AppServerPermissionGate buildAppServerPermissionGate(
            CodexAppServerManager appServerManager) {
        return (toolName, inputs, cwd) -> {
            try {
                String bridgeSessionId = com.github.claudecodegui.bridge.NodeService.getInstance().getSessionId();
                if (bridgeSessionId == null || bridgeSessionId.isEmpty()) {
                    return CompletableFuture.completedFuture(CliConstants.CODEX_APPSERVER_DECISION_DECLINE);
                }
                PermissionService permissionService =
                        PermissionService.getInstance(appServerManager.project(), bridgeSessionId);
                CompletionStage<PermissionService.PermissionResponse> decision =
                        permissionService.requestInteractivePermission(toolName, inputs, cwd);
                return decision.thenApply(CodexCliSessionFactory::toAppServerDecision);
            } catch (Exception | LinkageError e) {
                LOG.warn("[CodexCliSessionFactory] app-server permission gate failed: " + e.getMessage());
                return CompletableFuture.completedFuture(CliConstants.CODEX_APPSERVER_DECISION_DECLINE);
            }
        };
    }

    private static String toAppServerDecision(PermissionService.PermissionResponse decision) {
        if (decision == PermissionService.PermissionResponse.ALLOW) {
            return CliConstants.CODEX_APPSERVER_DECISION_ACCEPT;
        }
        if (decision == PermissionService.PermissionResponse.ALLOW_ALWAYS) {
            return CliConstants.CODEX_APPSERVER_DECISION_ACCEPT_FOR_SESSION;
        }
        return CliConstants.CODEX_APPSERVER_DECISION_DECLINE;
    }
}
