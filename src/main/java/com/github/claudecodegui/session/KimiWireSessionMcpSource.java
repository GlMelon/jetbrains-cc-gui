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
 * kimi 会话的 MCP 面板数据源(2026-09-04 用户确认的最终语义):
 * 显示「本会话实际加载的 + 相关来源里配置但加载失败的」,按 provider 分组展示。
 * <p>
 * ① 实际加载:以会话 wire 的 mcp.tools_discovered 为准(见 {@link KimiMcpDiscoveryReader})。
 *    melon_gateway(插件 ACP session/new 注入的聚合 gateway)条目按工具名
 *    ({@code mcp__<sourceProvider>__<serverId>__<toolName>},见 McpGatewayConstants)
 *    展开成 (provider × serverId) 条目;kimi CLI 自读 ~/.kimi-code/mcp.json 直连的
 *    server 直接列出(provider=kimi,state=ready)。已加载 server 若 gateway catalog
 *    (statusJson servers)里有状态,用 catalog 实时状态富化,否则 ready。
 * ② 失败补充:catalog 里 sourceProvider 属于「wire 展开实际出现过的来源 provider 集合」
 *    (有直连 server 时含 kimi)但 serverId 不在已加载集合的,按 catalog 真实状态
 *    (backoff/degraded/stopped 等)追加显示;其他来源 provider 的 server 不出现
 *    (没进这个会话)。
 * ③ 兜底:wire 不可读(新会话无 wire)或 melon_gateway 工具名全部解析失败且无直连
 *    server(拿不到来源信息)→ 退化为全量 catalog 收录(无法圈定来源时宁多勿漏)。
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
        // 1. gateway catalog 读取(一次):原始 servers 元素用于「失败补充 / 全量兜底」,
        //    statusByServer 索引用于已加载条目的实时状态富化;异常仅告警,catalog 视为不可用。
        List<JsonObject> catalogServers = new ArrayList<>();
        Map<String, JsonObject> statusByServer = new LinkedHashMap<>();
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
                            if (!serverElement.isJsonObject()) {
                                continue;
                            }
                            JsonObject server = serverElement.getAsJsonObject();
                            catalogServers.add(server);
                            String serverId = SessionMcpItemCodec.stringValue(server, McpGatewayConstants.KEY_SERVER_ID);
                            if (serverId != null && !serverId.isEmpty()) {
                                statusByServer.put(serverId, server);
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                // catalog 视为不可用,不致死(不设 mcpError:还有 wire 观测路径)。
                LOG.warn("[KimiWireSessionMcpSource] read gateway catalog failed: " + e.getMessage());
            }
        }
        // 2. wire 观测(无会话坐标即跳过)。
        String sessionId = session == null ? null : session.getSessionId();
        String cwd = session == null ? null : session.getCwd();
        List<KimiMcpDiscoveryReader.DiscoveredMcpServer> wireServers = null;
        if (sessionId != null && !sessionId.isBlank() && cwd != null && !cwd.isBlank()) {
            wireServers = new KimiMcpDiscoveryReader().readDiscoveredServers(sessionId, cwd);
        }
        // 3. 双不可用 → 面板不可用;否则按最终语义合并。
        if (!catalogAvailable && wireServers == null) {
            return McpPanelData.unavailable();
        }
        return new McpPanelData(true, null, mergeItems(catalogServers, statusByServer, wireServers));
    }

    /**
     * 合并 wire 观测与 gateway catalog(包私有静态纯函数,脱离 Project 单测):
     * 实际加载条目(melon_gateway 展开富化 + 直连 ready)优先;再从 catalog 补充
     * 「来源 provider 在 wire 展开里出现过、但 serverId 未加载」的失败条目(真实状态);
     * wire 为 null 或拿不到任何来源信息(无直连且展开为空)→ 全量 catalog 兜底;
     * 最终按 provider 分组重排(见 {@link SessionMcpItemCodec#groupByProviderThenServerId})。
     */
    static List<JsonObject> mergeItems(
            List<JsonObject> catalogServers,
            Map<String, JsonObject> statusByServer,
            List<KimiMcpDiscoveryReader.DiscoveredMcpServer> wireServers) {
        List<JsonObject> items = new ArrayList<>();
        if (wireServers == null) {
            // wire 不可读 → 无法圈定来源,全量 catalog 兜底(宁多勿漏)。
            appendAllCatalog(items, catalogServers);
            return SessionMcpItemCodec.groupByProviderThenServerId(items);
        }
        // 已加载条目:melon_gateway 按工具名展开(catalog 命中则富化),直连 server 直接列出。
        List<JsonObject> loaded = new ArrayList<>();
        boolean hasDirect = false;
        for (KimiMcpDiscoveryReader.DiscoveredMcpServer server : wireServers) {
            if (McpGatewayConstants.GATEWAY_SERVER_ID.equals(server.name())) {
                loaded.addAll(expandGatewayTools(server.toolNames(), statusByServer));
            } else {
                hasDirect = true;
                loaded.add(toItem(server));
            }
        }
        if (loaded.isEmpty()) {
            // 无直连且展开为空(全畸形 / 无 MCP 事件)→ 拿不到来源信息,全量 catalog 兜底。
            appendAllCatalog(items, catalogServers);
            return SessionMcpItemCodec.groupByProviderThenServerId(items);
        }
        items.addAll(loaded);
        // 已加载 serverId 集合 + 实际出现过的来源 provider 集合(有直连时含 kimi)。
        Set<String> loadedServerIds = new HashSet<>();
        Set<String> relevantProviders = new HashSet<>();
        for (JsonObject item : loaded) {
            String name = SessionMcpItemCodec.stringValue(item, SessionMcpCapabilityPayloadField.NAME.wireKey());
            if (name != null) {
                loadedServerIds.add(name);
            }
            String provider = SessionMcpItemCodec.stringValue(item, SessionMcpCapabilityPayloadField.PROVIDER.wireKey());
            if (provider != null && !provider.isEmpty()) {
                relevantProviders.add(provider);
            }
        }
        if (hasDirect) {
            relevantProviders.add(ProviderType.KIMI.value());
        }
        // catalog 失败补充:相关来源里配置但未加载的 server,按 catalog 真实状态追加。
        for (JsonObject server : catalogServers) {
            String sourceProvider = SessionMcpItemCodec.stringValue(server, McpGatewayConstants.KEY_SOURCE_PROVIDER);
            if (sourceProvider == null || !relevantProviders.contains(sourceProvider)) {
                continue;
            }
            String serverId = SessionMcpItemCodec.stringValue(server, McpGatewayConstants.KEY_SERVER_ID);
            if (serverId == null || loadedServerIds.contains(serverId)) {
                continue;
            }
            SessionMcpItemCodec.appendServer(items, server, null);
        }
        return SessionMcpItemCodec.groupByProviderThenServerId(items);
    }

    /** 全量 catalog 收录(不过滤 sourceProvider):兜底路径用,无法圈定来源时宁多勿漏。 */
    private static void appendAllCatalog(List<JsonObject> items, List<JsonObject> catalogServers) {
        for (JsonObject server : catalogServers) {
            SessionMcpItemCodec.appendServer(items, server, null);
        }
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
