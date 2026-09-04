package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.github.claudecodegui.provider.kimi.KimiMcpDiscoveryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KimiWireSessionMcpSource 的「gateway 目录优先 + wire 并集」语义(纯静态,脱离 Project):
 * catalog 优先不展开、catalog 空时 wire 展开兜底、wire 直连 server 并集去重(按 serverId)、
 * 双不可用 → unavailable;另保留 gateway 工具名展开(兜底路径)的分组/畸形/富化用例。
 */
public class KimiWireSessionMcpSourceTest {

    private static String field(JsonObject item, SessionMcpCapabilityPayloadField key) {
        return item.get(key.wireKey()).getAsString();
    }

    /** gateway statusJson servers 元素(经 codec 转面板条目,与 collect 的 catalog 段同路径)。 */
    private static JsonObject catalogServer(String sourceProvider, String serverId, String state) {
        JsonObject server = new JsonObject();
        server.addProperty(McpGatewayConstants.KEY_SOURCE_PROVIDER, sourceProvider);
        server.addProperty(McpGatewayConstants.KEY_SERVER_ID, serverId);
        server.addProperty(McpGatewayConstants.KEY_STATE, state);
        return server;
    }

    private static List<JsonObject> catalogItems(JsonObject... servers) {
        List<JsonObject> items = new ArrayList<>();
        for (JsonObject server : servers) {
            SessionMcpItemCodec.appendServer(items, server);
        }
        return items;
    }

    private static KimiMcpDiscoveryReader.DiscoveredMcpServer wireServer(String name, String... toolNames) {
        return new KimiMcpDiscoveryReader.DiscoveredMcpServer(name, toolNames.length, List.of(toolNames));
    }

    // ---------- 新语义:catalog 优先 + wire 并集 ----------

    @Test
    public void catalogPreferredAndGatewayEntryNotExpanded() {
        List<JsonObject> catalog = catalogItems(
                catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_READY),
                catalogServer("codex", "filesystem", McpGatewayConstants.STATE_BACKOFF));

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(catalog, List.of(
                wireServer(McpGatewayConstants.GATEWAY_SERVER_ID,
                        "mcp__claude__agentmemory__memory_recall",
                        "mcp__other__extra__tool")));

        // catalog 全量收录(含真实状态),melon_gateway 不展开、不重复
        assertEquals(2, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("ready", field(items.get(0), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("filesystem", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("backoff", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
    }

    @Test
    public void wireDirectServersUnionedAndDedupedByServerId() {
        List<JsonObject> catalog = catalogItems(
                catalogServer("claude", "filesystem", McpGatewayConstants.STATE_READY));

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(catalog, List.of(
                // kimi CLI 直连的同名 server:catalog 已有 → 不重复追加
                wireServer("filesystem", "read_file"),
                // catalog 没有的直连 server → 追加为 ready 观测条目
                wireServer("mytools", "do_thing")));

        assertEquals(2, items.size());
        assertEquals("filesystem", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("mytools", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals(ProviderType.KIMI.value() + ":mytools", field(items.get(1), SessionMcpCapabilityPayloadField.ID));
        assertEquals(ProviderType.KIMI.value(), field(items.get(1), SessionMcpCapabilityPayloadField.PROVIDER));
        assertEquals("ready", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
        assertTrue(items.get(1).get(SessionMcpCapabilityPayloadField.OBSERVED.wireKey()).getAsBoolean());
    }

    @Test
    public void emptyCatalogFallsBackToWireExpansion() {
        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(List.of(), List.of(
                wireServer(McpGatewayConstants.GATEWAY_SERVER_ID,
                        "mcp__claude__agentmemory__memory_recall",
                        "mcp__codex__filesystem__read_file")));

        // 无 status 可富化 → 全 ready
        assertEquals(2, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("ready", field(items.get(0), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("filesystem", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("ready", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
    }

    @Test
    public void emptyCatalogAndAllMalformedToolsKeepsSingleGatewayItem() {
        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(List.of(), List.of(
                wireServer(McpGatewayConstants.GATEWAY_SERVER_ID, "junk", "mcp__onlyone")));

        // 展开全畸形 → 保留 melon_gateway 单条现状输出
        assertEquals(1, items.size());
        assertEquals(McpGatewayConstants.GATEWAY_SERVER_ID, field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("ready", field(items.get(0), SessionMcpCapabilityPayloadField.STATE));
    }

    @Test
    public void nullWireServersYieldsCatalogOnly() {
        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(
                catalogItems(catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_READY)), null);

        assertEquals(1, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
    }

    @Test
    public void bothUnavailableYieldsUnavailable() {
        // project=null → catalog 不可用;session=null → 无会话坐标,wire 不可观测。
        SessionMcpSource.McpPanelData data = new KimiWireSessionMcpSource().collect(null, null);

        assertFalse(data.available());
        assertTrue(data.items().isEmpty());
    }

    // ---------- 展开兜底路径(catalog 不可用/为空时) ----------

    @Test
    public void groupsByProviderAndServerInDiscoveryOrder() {
        List<JsonObject> items = KimiWireSessionMcpSource.expandGatewayTools(List.of(
                "mcp__claude__agentmemory__memory_recall",
                "mcp__codex__filesystem__read_file",
                // 同 server 的第二个工具:去重,不产生新条目
                "mcp__claude__agentmemory__memory_store",
                "mcp__claude__websearch__search",
                // 工具名含分隔符:toolName 段允许 "__",不影响分组
                "mcp__codex__filesystem__write__file"
        ), Map.of());

        assertEquals(3, items.size());
        String kimi = ProviderType.KIMI.value();
        assertEquals(kimi + ":claude:agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.ID));
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("claude", field(items.get(0), SessionMcpCapabilityPayloadField.PROVIDER));
        assertEquals(kimi + ":codex:filesystem", field(items.get(1), SessionMcpCapabilityPayloadField.ID));
        assertEquals("filesystem", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("codex", field(items.get(1), SessionMcpCapabilityPayloadField.PROVIDER));
        assertEquals(kimi + ":claude:websearch", field(items.get(2), SessionMcpCapabilityPayloadField.ID));
        assertTrue(items.get(0).get(SessionMcpCapabilityPayloadField.OBSERVED.wireKey()).getAsBoolean());
    }

    @Test
    public void malformedToolNamesSkipped() {
        List<JsonObject> items = KimiWireSessionMcpSource.expandGatewayTools(List.of(
                "not-a-gateway-tool",
                "mcp__claude",                       // 段数不足
                "mcp____agentmemory__recall",        // sourceProvider 空段
                "mcp__claude__agentmemory__recall"
        ), Map.of());

        assertEquals(1, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
    }

    @Test
    public void statusHitCopiesHealthFields() {
        JsonObject server = new JsonObject();
        server.addProperty(McpGatewayConstants.KEY_SERVER_ID, "agentmemory");
        server.addProperty(McpGatewayConstants.KEY_STATE, McpGatewayConstants.STATE_DEGRADED);
        server.addProperty(McpGatewayConstants.KEY_LAST_ERROR, "boom");
        server.addProperty(McpGatewayConstants.KEY_LAST_SUCCESS_AT, 123L);
        server.addProperty(McpGatewayConstants.KEY_FAILURE_COUNT, 2);

        List<JsonObject> items = KimiWireSessionMcpSource.expandGatewayTools(
                List.of("mcp__claude__agentmemory__memory_recall"),
                Map.of("agentmemory", server));

        assertEquals(1, items.size());
        JsonObject item = items.get(0);
        assertEquals("degraded", field(item, SessionMcpCapabilityPayloadField.STATE));
        assertEquals("boom", field(item, SessionMcpCapabilityPayloadField.LAST_ERROR));
        assertEquals(123L, item.get(SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey()).getAsLong());
        assertEquals(2, item.get(SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey()).getAsInt());
    }

    @Test
    public void statusMissFallsBackToReady() {
        List<JsonObject> items = KimiWireSessionMcpSource.expandGatewayTools(
                List.of("mcp__claude__agentmemory__memory_recall"), Map.of());

        assertEquals(1, items.size());
        JsonObject item = items.get(0);
        assertEquals("ready", field(item, SessionMcpCapabilityPayloadField.STATE));
        assertTrue(item.get(SessionMcpCapabilityPayloadField.LAST_ERROR.wireKey()).isJsonNull());
        assertTrue(item.get(SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey()).isJsonNull());
        assertEquals(0, item.get(SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey()).getAsInt());
        assertTrue(item.get(SessionMcpCapabilityPayloadField.OBSERVED.wireKey()).getAsBoolean());
    }

    @Test
    public void allMalformedYieldsEmpty() {
        assertTrue(KimiWireSessionMcpSource.expandGatewayTools(
                List.of("x", "mcp__a", "", "mcp____b"), Map.of()).isEmpty());
    }
}
