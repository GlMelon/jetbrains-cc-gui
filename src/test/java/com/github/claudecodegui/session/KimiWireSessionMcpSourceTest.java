package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.github.claudecodegui.provider.kimi.KimiMcpDiscoveryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KimiWireSessionMcpSource 最终语义(2026-09-04 用户确认,纯静态脱离 Project):
 * ① wire 展开(实际加载)+ catalog 失败补充(只补 wire 出现过的来源 provider);
 * ② 已加载 server 用 catalog 实时状态富化;③ 直连 server 并集(provider=kimi);
 * ④ wire 不可读 → 全量 catalog 兜底;⑤ 工具名全畸形 → 全量 catalog;
 * ⑥ 按 provider 分组排序;另保留 expandGatewayTools 自身的分组/畸形/富化用例。
 */
public class KimiWireSessionMcpSourceTest {

    private static String field(JsonObject item, SessionMcpCapabilityPayloadField key) {
        return item.get(key.wireKey()).getAsString();
    }

    /** gateway statusJson servers 原始元素。 */
    private static JsonObject catalogServer(String sourceProvider, String serverId, String state) {
        JsonObject server = new JsonObject();
        server.addProperty(McpGatewayConstants.KEY_SOURCE_PROVIDER, sourceProvider);
        server.addProperty(McpGatewayConstants.KEY_SERVER_ID, serverId);
        server.addProperty(McpGatewayConstants.KEY_STATE, state);
        return server;
    }

    private static Map<String, JsonObject> statusByServer(JsonObject... servers) {
        Map<String, JsonObject> map = new LinkedHashMap<>();
        for (JsonObject server : servers) {
            map.put(server.get(McpGatewayConstants.KEY_SERVER_ID).getAsString(), server);
        }
        return map;
    }

    private static KimiMcpDiscoveryReader.DiscoveredMcpServer wireServer(String name, String... toolNames) {
        return new KimiMcpDiscoveryReader.DiscoveredMcpServer(name, toolNames.length, List.of(toolNames));
    }

    // ---------- 最终语义:wire 展开 + catalog 失败补充 ----------

    @Test
    public void wireExpansionPlusCatalogFailureSupplement() {
        JsonObject agentmemory = catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_READY);
        JsonObject webstorm = catalogServer("claude", "webstorm_mcp", McpGatewayConstants.STATE_BACKOFF);
        webstorm.addProperty(McpGatewayConstants.KEY_LAST_ERROR, "spawn failed");
        JsonObject codexFs = catalogServer("codex", "filesystem", McpGatewayConstants.STATE_READY);
        JsonObject codexDbx = catalogServer("codex", "dbx", McpGatewayConstants.STATE_STOPPED);
        List<JsonObject> catalog = List.of(agentmemory, webstorm, codexFs, codexDbx);
        Map<String, JsonObject> status = statusByServer(agentmemory, webstorm, codexFs, codexDbx);

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(catalog, status, List.of(
                wireServer(McpGatewayConstants.GATEWAY_SERVER_ID,
                        "mcp__claude__agentmemory__memory_recall",
                        "mcp__claude__websearch__search")));

        // 已加载:agentmemory(catalog 命中富化)+ websearch(catalog 无,ready);
        // 失败补充:claude 来源的 webstorm_mcp(backoff);codex 来源不出现(没进这个会话)。
        assertEquals(3, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("ready", field(items.get(0), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("websearch", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("ready", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("webstorm_mcp", field(items.get(2), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("backoff", field(items.get(2), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("spawn failed", field(items.get(2), SessionMcpCapabilityPayloadField.LAST_ERROR));
        assertTrue(items.get(2).get(SessionMcpCapabilityPayloadField.OBSERVED.wireKey()).getAsBoolean());
    }

    @Test
    public void loadedServerEnrichedWithCatalogLiveState() {
        JsonObject agentmemory = catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_DEGRADED);
        agentmemory.addProperty(McpGatewayConstants.KEY_LAST_ERROR, "timeout");
        agentmemory.addProperty(McpGatewayConstants.KEY_LAST_SUCCESS_AT, 456L);
        agentmemory.addProperty(McpGatewayConstants.KEY_FAILURE_COUNT, 3);

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(
                List.of(agentmemory), statusByServer(agentmemory), List.of(
                        wireServer(McpGatewayConstants.GATEWAY_SERVER_ID,
                                "mcp__claude__agentmemory__memory_recall")));

        // 已加载条目不写死 ready,用 catalog 实时状态
        assertEquals(1, items.size());
        JsonObject item = items.get(0);
        assertEquals("degraded", field(item, SessionMcpCapabilityPayloadField.STATE));
        assertEquals("timeout", field(item, SessionMcpCapabilityPayloadField.LAST_ERROR));
        assertEquals(456L, item.get(SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey()).getAsLong());
        assertEquals(3, item.get(SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey()).getAsInt());
    }

    @Test
    public void directWireServersUnionedWithKimiProviderSupplement() {
        JsonObject kimiLocalfs = catalogServer(ProviderType.KIMI.value(), "localfs", McpGatewayConstants.STATE_STOPPED);
        JsonObject claudeAgentmemory = catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_READY);
        List<JsonObject> catalog = List.of(kimiLocalfs, claudeAgentmemory);
        Map<String, JsonObject> status = statusByServer(kimiLocalfs, claudeAgentmemory);

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(catalog, status, List.of(
                wireServer("mytools", "do_thing")));

        // 直连 server 列出(provider=kimi,ready);kimi 来源的 catalog 失败条目补充,
        // claude 来源不出现(wire 里没出现过该来源)。
        assertEquals(2, items.size());
        assertEquals("mytools", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals(ProviderType.KIMI.value(), field(items.get(0), SessionMcpCapabilityPayloadField.PROVIDER));
        assertEquals("ready", field(items.get(0), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("localfs", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("stopped", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
    }

    @Test
    public void wireUnreadableFallsBackToFullCatalog() {
        JsonObject claudeAgentmemory = catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_READY);
        JsonObject codexFs = catalogServer("codex", "filesystem", McpGatewayConstants.STATE_BACKOFF);

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(
                List.of(claudeAgentmemory, codexFs), statusByServer(claudeAgentmemory, codexFs), null);

        // wire 不可读 → 无法圈定来源,全量 catalog(宁多勿漏)
        assertEquals(2, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("filesystem", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("backoff", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
    }

    @Test
    public void allMalformedGatewayToolsFallsBackToFullCatalog() {
        JsonObject claudeAgentmemory = catalogServer("claude", "agentmemory", McpGatewayConstants.STATE_READY);
        JsonObject codexFs = catalogServer("codex", "filesystem", McpGatewayConstants.STATE_READY);

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(
                List.of(claudeAgentmemory, codexFs), statusByServer(claudeAgentmemory, codexFs), List.of(
                        wireServer(McpGatewayConstants.GATEWAY_SERVER_ID, "junk", "mcp__onlyone")));

        // 无直连且展开全畸形 → 拿不到来源信息,全量 catalog 兜底
        assertEquals(2, items.size());
        assertEquals("agentmemory", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("filesystem", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
    }

    @Test
    public void mergeGroupsByProviderThenServerId() {
        JsonObject codexFs = catalogServer("codex", "filesystem", McpGatewayConstants.STATE_READY);
        JsonObject claudeWebsearch = catalogServer("claude", "websearch", McpGatewayConstants.STATE_BACKOFF);
        JsonObject codexDbx = catalogServer("codex", "dbx", McpGatewayConstants.STATE_STOPPED);
        List<JsonObject> catalog = List.of(codexFs, claudeWebsearch, codexDbx);
        Map<String, JsonObject> status = statusByServer(codexFs, claudeWebsearch, codexDbx);

        List<JsonObject> items = KimiWireSessionMcpSource.mergeItems(catalog, status, List.of(
                // 已加载:codex/fs(展开)+ 直连 mytools;补充:codex/dbx(codex 来源已出现)。
                // claude/websearch 不出现(claude 来源没进 wire)。
                wireServer(McpGatewayConstants.GATEWAY_SERVER_ID, "mcp__codex__filesystem__read_file"),
                wireServer("mytools", "do_thing")));

        // 合并后按 provider 首次出现分组:codex 组(fs + dbx)→ kimi 组(mytools)
        assertEquals(3, items.size());
        assertEquals("filesystem", field(items.get(0), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("codex", field(items.get(0), SessionMcpCapabilityPayloadField.PROVIDER));
        assertEquals("dbx", field(items.get(1), SessionMcpCapabilityPayloadField.NAME));
        assertEquals("codex", field(items.get(1), SessionMcpCapabilityPayloadField.PROVIDER));
        assertEquals("stopped", field(items.get(1), SessionMcpCapabilityPayloadField.STATE));
        assertEquals("mytools", field(items.get(2), SessionMcpCapabilityPayloadField.NAME));
        assertEquals(ProviderType.KIMI.value(), field(items.get(2), SessionMcpCapabilityPayloadField.PROVIDER));
    }

    @Test
    public void bothUnavailableYieldsUnavailable() {
        // project=null → catalog 不可用;session=null → 无会话坐标,wire 不可观测。
        SessionMcpSource.McpPanelData data = new KimiWireSessionMcpSource().collect(null, null);

        assertFalse(data.available());
        assertTrue(data.items().isEmpty());
    }

    // ---------- expandGatewayTools 自身(展开解析 / 富化) ----------

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
