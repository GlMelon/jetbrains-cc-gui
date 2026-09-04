package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;

/**
 * gateway 注入型 provider(claude/codex/opencode)的 MCP 面板数据源:
 * 读 {@link McpGatewayService#statusJson()} 的 servers 数组并按 sourceProvider 过滤
 * (2026-09-04 用户确认:每会话只显示本 provider 来源加载的 server 集,
 * claude 会话只列 claude 来源,以此类推);sourceProvider=global(全局统一列表)
 * 的条目对任意 provider 会话一律放行(所有会话均可经 gateway 调用,
 * 见 {@link SessionMcpItemCodec#appendServer})。
 */
public final class GatewaySessionMcpSource implements SessionMcpSource {

    private static final String MCP_STATUS_ERROR = "Unable to read MCP Gateway status";

    /**
     * 能力查询路由(懒加载静态共享,构造轻量)。MCP 面板可用性必须以 adapter 层
     * {@link ProviderCapability#MCP} 声明为门禁:
     * 否则 gateway status 里有 servers 数组的部署上,无 MCP 能力的 provider
     * 会得到「available=true + 过滤后空列表」的自相矛盾 payload(2026-08-29 审计缺口)。
     */
    private static volatile SessionProviderRouter capabilityRouter;

    private final ProviderType provider;

    public GatewaySessionMcpSource(ProviderType provider) {
        this.provider = provider;
    }

    @Override
    public ProviderType provider() {
        return provider;
    }

    @Override
    public McpPanelData collect(Project project, ClaudeSession session) {
        if (project == null || session == null || !providerSupportsMcp()) {
            return McpPanelData.unavailable();
        }
        List<JsonObject> items = new ArrayList<>();
        String mcpError = null;
        boolean available = false;
        try {
            String statusJson = McpGatewayService.getInstance(project).statusJson();
            JsonElement root = JsonParser.parseString(
                    statusJson == null ? McpGatewayConstants.EMPTY_JSON_OBJECT : statusJson
            );
            if (root.isJsonObject()) {
                JsonElement serversElement = root.getAsJsonObject().get(McpGatewayConstants.KEY_SERVERS);
                if (serversElement != null && serversElement.isJsonArray()) {
                    available = true;
                    for (JsonElement serverElement : serversElement.getAsJsonArray()) {
                        SessionMcpItemCodec.appendServer(items, serverElement, session.getProvider());
                    }
                }
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            mcpError = MCP_STATUS_ERROR;
        } catch (RuntimeException e) {
            mcpError = MCP_STATUS_ERROR;
        }
        return new McpPanelData(available, mcpError, items);
    }

    private boolean providerSupportsMcp() {
        SessionProviderRouter router = capabilityRouter;
        if (router == null) {
            router = new SessionProviderRouter();
            capabilityRouter = router;
        }
        return router.supports(provider.value(), ProviderCapability.MCP);
    }
}
