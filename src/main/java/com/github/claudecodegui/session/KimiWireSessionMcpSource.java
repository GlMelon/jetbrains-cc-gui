package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.github.claudecodegui.provider.kimi.KimiMcpDiscoveryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * kimi 会话的 MCP 面板数据源,「gateway 目录优先 + wire 并集」:
 * <p>
 * ① catalog 优先:kimi 会话经 ACP session/new 注入 melon_gateway(聚合 gateway),
 * gateway statusJson 的 servers 数组本身即配置的全部 server 及其真实状态
 * (READY/DEGRADED/BACKOFF/STOPPED,与设置页同源),面板全量收录;
 * ② wire 并集:kimi CLI 自读 ~/.kimi-code/mcp.json 直连的 server(不经 gateway,
 * catalog 里没有)按会话 wire 的 mcp.tools_discovered 事件
 * (见 {@link KimiMcpDiscoveryReader})观测追加,state=ready;
 * ③ 展开兜底:仅当 catalog 不可用 / 为空时,wire 里的 melon_gateway 条目才按工具名
 * ({@code mcp__<sourceProvider>__<serverId>__<toolName>},见 McpGatewayConstants)
 * 展开,避免 gateway 不可达时面板空无一物。
 * <p>
 * available = catalog 可读(servers 数组存在,哪怕空)|| wire 可观测(reader 返回非 null),
 * 双不可用才降级 unavailable。
 */
public final class KimiWireSessionMcpSource implements SessionMcpSource {

    private static final Logger LOG = Logger.getInstance(KimiWireSessionMcpSource.class);

    private static final String ID_SEPARATOR = ":";

    @Override
    public ProviderType provider() {
        return ProviderType.KIMI;
    }

    @Override
    public McpPanelData collect(Project project, ClaudeSession session) {
        // 1. catalog 优先:gateway statusJson 的 servers 全量收录。
        List<JsonObject> catalogItems = new ArrayList<>();
        boolean catalogAvailable = false;
        if (project != null) {
            try {
                String statusJson = McpGatewayService.getInstance(project).statusJson();
                JsonElement root = JsonParser.parseString(
                        statusJson == null ? McpGatewayConstants.EMPTY_JSON_OBJECT : statusJson
                );
                if (root.isJsonObject()) {
                    JsonElement serversElement = root.getAsJsonObject().get(McpGatewayConstants.KEY_SERVERS);
                    if (serversElement != null && serversElement.isJsonArray()) {
                        catalogAvailable = true;
                        for (JsonElement serverElement : serversElement.getAsJsonArray()) {
                            SessionMcpItemCodec.appendServer(catalogItems, serverElement);
                        }
                    }
                }
            } catch (RuntimeException e) {
                // catalog 视为不可用,不致死(不设 mcpError:还有 wire 兜底)。
                LOG.warn("[KimiWireSessionMcpSource] read gateway catalog failed: " + e.getMessage());
            }
        }
        // 2. wire 观测(无会话坐标即跳过,仅靠 catalog)。
        String sessionId = session == null ? null : session.getSessionId();
        String cwd = session == null ? null : session.getCwd();
        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> wireServers = null;
        if (sessionId != null && !sessionId.isBlank() && cwd != null && !cwd.isBlank()) {
            wireServers = new KimiMcpDiscoveryReader().readDiscoveredServers(sessionId, cwd);
        }
        // 3. 双不可用 → 面板不可用;否则合并 catalog + wire 并集。
        if (!catalogAvailable && wireServers == null) {
            return McpPanelData.unavailable();
        }
        return new McpPanelData(true, null, mergeItems(catalogItems, wireServers));
    }

    /**
     * 合并 catalog 条目与 wire 观测 server(包私有静态纯函数,脱离 Project 单测):
     * - catalog 非空:全部配置 server 已在列(含真实状态);wire 里仅「直连
     *   (serverName != melon_gateway)且不在 catalog(按 serverId 比对)」的 server
     *   追加为 ready 观测条目;
     * - catalog 为空:wire 里的 melon_gateway 条目走 {@link #expandGatewayTools} 展开兜底
     *   (无 status 可富化,state 全 ready;工具名全畸形时保留 melon_gateway 单条),
     *   直连 server 照常追加。
     */
    static List<JsonObject> mergeItems(
            List<JsonObject> catalogItems,
            List<KimiMcpDiscoveryReader.DiscoveredMcpServer> wireServers) {
        List<JsonObject> items = new ArrayList<>(catalogItems);
        if (wireServers == null || wireServers.isEmpty()) {
            return items;
        }
        boolean catalogEmpty = catalogItems.isEmpty();
        // catalog 条目的 name 即 serverId(见 SessionMcpItemCodec.appendServer)。
        Set<String> catalogServerIds = new HashSet<>();
        for (JsonObject item : catalogItems) {
            String name = SessionMcpItemCodec.stringValue(item, SessionMcpCapabilityPayloadField.NAME.wireKey());
            if (name != null) {
                catalogServerIds.add(name);
            }
        }
        for (KimiMcpDiscoveryReader.DiscoveredMcpServer server : wireServers) {
            if (McpGatewayConstants.GATEWAY_SERVER_ID.equals(server.name())) {
                if (catalogEmpty) {
                    List<JsonObject> expanded = expandGatewayTools(server.toolNames(), Map.of());
                    if (expanded.isEmpty()) {
                        // 兜底:工具名全部解析失败 → 保留 melon_gateway 单条现状输出。
                        items.add(toItem(server));
                    } else {
                        items.addAll(expanded);
                    }
                }
                // catalog 非空时 melon_gateway 不再展开(catalog 已列出真实 server)。
            } else if (!catalogServerIds.contains(server.name())) {
                items.add(toItem(server));
            }
        }
        return items;
    }

    /**
     * 展开 gateway 聚合条目(包私有静态,脱离 Project 纯单测):
     * 解析工具名(镜像 ai-bridge/mcp-gateway/tool-router.js parseGatewayToolName 语义:
     * 按 {@code __} 切分,段数 < 4 无效,sourceProvider=段1、serverId=段2),
     * 按 (sourceProvider, serverId) 分组保序去重,每个真实 server 产出一个面板条目;
     * 命中 gateway status 的透传健康字段,未命中按 ready 兜底。
     */
    static List<JsonObject> expandGatewayTools(List<String> toolNames, Map<String, JsonObject> statusByServer) {
        // LinkedHashMap:按首次出现保序;同 server 的多个工具只展开为一个条目。
        Map<GatewayServerRef, GatewayServerRef> discovered = new LinkedHashMap<>();
        for (String toolName : toolNames) {
            GatewayServerRef ref = parseGatewayToolName(toolName);
            if (ref != null) {
                discovered.putIfAbsent(ref, ref);
            }
        }
        List<JsonObject> out = new ArrayList<>(discovered.size());
        for (GatewayServerRef ref : discovered.keySet()) {
            JsonObject status = statusByServer == null ? null : statusByServer.get(ref.serverId());
            out.add(toExpandedItem(ref, status));
        }
        return out;
    }

    /** gateway 工具名解析:非 gateway 前缀 / 段数不足 → null(跳过)。 */
    private static GatewayServerRef parseGatewayToolName(String toolName) {
        if (toolName == null || !toolName.startsWith(McpGatewayConstants.GATEWAY_TOOL_PREFIX)) {
            return null;
        }
        // limit=-1 保留尾段空串,与 JS split 行为一致。
        String[] parts = toolName.split(McpGatewayConstants.GATEWAY_TOOL_SEPARATOR, -1);
        if (parts.length < 4 || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        return new GatewayServerRef(parts[1], parts[2]);
    }

    /** gateway 展开条目:命中 status 则经 codec 透传健康字段,未命中按 ready 兜底。 */
    private static JsonObject toExpandedItem(GatewayServerRef ref, JsonObject status) {
        JsonObject item = new JsonObject();
        item.addProperty(
                SessionMcpCapabilityPayloadField.ID.wireKey(),
                ProviderType.KIMI.value() + ID_SEPARATOR + ref.sourceProvider() + ID_SEPARATOR + ref.serverId()
        );
        item.addProperty(SessionMcpCapabilityPayloadField.NAME.wireKey(), ref.serverId());
        item.addProperty(SessionMcpCapabilityPayloadField.PROVIDER.wireKey(), ref.sourceProvider());
        if (status != null) {
            item.addProperty(
                    SessionMcpCapabilityPayloadField.STATE.wireKey(),
                    SessionMcpItemCodec.mapState(
                            SessionMcpItemCodec.stringValue(status, McpGatewayConstants.KEY_STATE))
            );
            SessionMcpItemCodec.copyStringOrNull(
                    item,
                    SessionMcpCapabilityPayloadField.LAST_ERROR.wireKey(),
                    status.get(McpGatewayConstants.KEY_LAST_ERROR)
            );
            SessionMcpItemCodec.copyNumberOrNull(
                    item,
                    SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey(),
                    status.get(McpGatewayConstants.KEY_LAST_SUCCESS_AT)
            );
            SessionMcpItemCodec.copyNumber(
                    item,
                    SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey(),
                    status.get(McpGatewayConstants.KEY_FAILURE_COUNT)
            );
        } else {
            item.addProperty(
                    SessionMcpCapabilityPayloadField.STATE.wireKey(),
                    McpGatewayConstants.STATE_READY.toLowerCase(Locale.ROOT)
            );
            item.add(SessionMcpCapabilityPayloadField.LAST_ERROR.wireKey(), JsonNull.INSTANCE);
            item.add(SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey(), JsonNull.INSTANCE);
            item.addProperty(SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey(), 0);
        }
        item.addProperty(SessionMcpCapabilityPayloadField.OBSERVED.wireKey(), true);
        return item;
    }

    /** gateway 聚合工具名解析出的真实 server 坐标(record 自带 equals/hashCode,直接作分组键)。 */
    private record GatewayServerRef(String sourceProvider, String serverId) {
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
