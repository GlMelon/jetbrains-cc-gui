package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.github.claudecodegui.provider.kimi.KimiMcpDiscoveryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * kimi 会话的 MCP 面板数据源:kimi 的 MCP 不经 melon gateway(插件 ACP session/new 注入
 * + CLI 自读 ~/.kimi-code/mcp.json 两条注入路径),实际加载证据统一落盘在会话 wire 的
 * mcp.tools_discovered 事件(见 {@link KimiMcpDiscoveryReader}),故按 wire 观测。
 */
public final class KimiWireSessionMcpSource implements SessionMcpSource {

    private static final String ID_SEPARATOR = ":";

    @Override
    public ProviderType provider() {
        return ProviderType.KIMI;
    }

    @Override
    public McpPanelData collect(Project project, ClaudeSession session) {
        String sessionId = session == null ? null : session.getSessionId();
        String cwd = session == null ? null : session.getCwd();
        // 防御:null/空边界显式处理(总则六),无会话坐标即面板不可用。
        if (sessionId == null || sessionId.isBlank() || cwd == null || cwd.isBlank()) {
            return McpPanelData.unavailable();
        }
        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> servers =
                new KimiMcpDiscoveryReader().readDiscoveredServers(sessionId, cwd);
        // null = 会话目录不存在 / wire 不可读 → 不可用;空列表 = 会话在但未加载 MCP(可用,空面板)。
        if (servers == null) {
            return McpPanelData.unavailable();
        }
        List<JsonObject> items = new ArrayList<>(servers.size());
        for (KimiMcpDiscoveryReader.DiscoveredMcpServer server : servers) {
            items.add(toItem(server));
        }
        return new McpPanelData(true, null, items);
    }

    /**
     * wire 观测条目:能写出 tools_discovered 即 server 已就绪 → state=ready;
     * gateway 健康字段(lastError/lastSuccessAt/failureCount)wire 无对应证据,
     * 按 payload 契约显式 JsonNull / 0(observed 标记数据为后端实际观测而非占位)。
     */
    private static JsonObject toItem(KimiMcpDiscoveryReader.DiscoveredMcpServer server) {
        String providerValue = ProviderType.KIMI.value();
        JsonObject item = new JsonObject();
        item.addProperty(SessionMcpCapabilityPayloadField.ID.wireKey(), providerValue + ID_SEPARATOR + server.name());
        item.addProperty(SessionMcpCapabilityPayloadField.NAME.wireKey(), server.name());
        item.addProperty(SessionMcpCapabilityPayloadField.PROVIDER.wireKey(), providerValue);
        item.addProperty(
                SessionMcpCapabilityPayloadField.STATE.wireKey(),
                McpGatewayConstants.STATE_READY.toLowerCase(Locale.ROOT)
        );
        item.add(SessionMcpCapabilityPayloadField.LAST_ERROR.wireKey(), JsonNull.INSTANCE);
        item.add(SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey(), JsonNull.INSTANCE);
        item.addProperty(SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey(), 0);
        item.addProperty(SessionMcpCapabilityPayloadField.OBSERVED.wireKey(), true);
        return item;
    }
}
