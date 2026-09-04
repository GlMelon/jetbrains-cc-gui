package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SessionMcpItemCodec:appendServer 的 sourceProvider 过滤(currentProvider 非空过滤 /
 * null·空串不过滤、ignoreCase)与 groupByProviderThenServerId 的稳定分组重排。
 */
public class SessionMcpItemCodecTest {

    private static JsonObject server(String sourceProvider, String serverId, String state) {
        JsonObject server = new JsonObject();
        server.addProperty(McpGatewayConstants.KEY_SOURCE_PROVIDER, sourceProvider);
        server.addProperty(McpGatewayConstants.KEY_SERVER_ID, serverId);
        server.addProperty(McpGatewayConstants.KEY_STATE, state);
        return server;
    }

    private static String name(JsonObject item) {
        return item.get(SessionMcpCapabilityPayloadField.NAME.wireKey()).getAsString();
    }

    @Test
    public void appendServerSkipsOtherProvider() {
        List<JsonObject> items = new ArrayList<>();
        SessionMcpItemCodec.appendServer(
                items, server("claude", "agentmemory", McpGatewayConstants.STATE_READY), "codex");

        assertTrue(items.isEmpty());
    }

    @Test
    public void appendServerAcceptsSameProviderIgnoreCase() {
        List<JsonObject> items = new ArrayList<>();
        SessionMcpItemCodec.appendServer(
                items, server("claude", "agentmemory", McpGatewayConstants.STATE_READY), "CLAUDE");

        assertEquals(1, items.size());
        assertEquals("agentmemory", name(items.get(0)));
    }

    @Test
    public void appendServerNullOrEmptyProviderMeansNoFilter() {
        List<JsonObject> items = new ArrayList<>();
        SessionMcpItemCodec.appendServer(
                items, server("claude", "agentmemory", McpGatewayConstants.STATE_READY), null);
        SessionMcpItemCodec.appendServer(
                items, server("codex", "filesystem", McpGatewayConstants.STATE_READY), "");

        assertEquals(2, items.size());
        assertEquals("agentmemory", name(items.get(0)));
        assertEquals("filesystem", name(items.get(1)));
    }

    @Test
    public void appendServerGlobalSourcePassesForAnyProviderSession() {
        // sourceProvider=global(全局统一列表)的条目对 claude / codex 会话一律放行
        List<JsonObject> items = new ArrayList<>();
        SessionMcpItemCodec.appendServer(
                items, server(McpGatewayConstants.SOURCE_GLOBAL, "shared", McpGatewayConstants.STATE_READY), "claude");
        SessionMcpItemCodec.appendServer(
                items, server(McpGatewayConstants.SOURCE_GLOBAL, "websearch", McpGatewayConstants.STATE_READY), "codex");

        assertEquals(2, items.size());
        assertEquals("shared", name(items.get(0)));
        assertEquals("websearch", name(items.get(1)));
    }

    @Test
    public void appendServerStillSkipsRealOtherProviderForClaudeSession() {
        List<JsonObject> items = new ArrayList<>();
        SessionMcpItemCodec.appendServer(
                items, server("codex", "filesystem", McpGatewayConstants.STATE_READY), "claude");

        assertTrue(items.isEmpty());
    }

    @Test
    public void groupByProviderThenServerIdGroupsByFirstAppearance() {
        List<JsonObject> items = new ArrayList<>();
        SessionMcpItemCodec.appendServer(items, server("codex", "filesystem", McpGatewayConstants.STATE_READY), null);
        SessionMcpItemCodec.appendServer(items, server("claude", "agentmemory", McpGatewayConstants.STATE_READY), null);
        SessionMcpItemCodec.appendServer(items, server("codex", "dbx", McpGatewayConstants.STATE_STOPPED), null);
        // 无 provider 字段的条目 → 归入空 provider 组(按首次出现位置)
        JsonObject bare = new JsonObject();
        bare.addProperty(SessionMcpCapabilityPayloadField.NAME.wireKey(), "mytools");
        items.add(bare);
        SessionMcpItemCodec.appendServer(items, server("claude", "websearch", McpGatewayConstants.STATE_READY), null);

        List<JsonObject> grouped = SessionMcpItemCodec.groupByProviderThenServerId(items);

        // codex 组(保持原相对顺序)→ claude 组 → 空 provider 组
        assertEquals(5, grouped.size());
        assertEquals("filesystem", name(grouped.get(0)));
        assertEquals("dbx", name(grouped.get(1)));
        assertEquals("agentmemory", name(grouped.get(2)));
        assertEquals("websearch", name(grouped.get(3)));
        assertEquals("mytools", name(grouped.get(4)));
    }
}
