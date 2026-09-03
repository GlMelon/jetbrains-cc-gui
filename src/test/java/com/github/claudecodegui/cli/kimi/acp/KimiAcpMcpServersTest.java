package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Kimi ACP 通道的 MCP gateway 动态注入(session/new mcpServers http 条目)。
 * 2026-09-03 实测 kimi 0.38.0:ACP initialize 声明 mcpCapabilities.http=true,
 * session/new 传 {type:"http",url,headers:[{name,value}]} 可发现并成功调用 gateway 工具。
 */
public class KimiAcpMcpServersTest {

    private static final String ENDPOINT = "http://127.0.0.1:11634" + McpGatewayConstants.MCP_ENDPOINT_PATH;
    private static final String TOKEN = "unit-test-gateway-token";

    private static McpGatewayCliConfig usableConfig() {
        return new McpGatewayCliConfig(true, true, 1L, null, ENDPOINT,
                Map.of(McpGatewayConstants.ENV_GATEWAY_TOKEN, TOKEN), List.of(), null);
    }

    @Test
    public void usableConfigProducesSingleHttpGatewayEntry() {
        JsonArray servers = KimiAcpCliSession.buildMcpServers(usableConfig());

        assertEquals(1, servers.size());
        JsonObject server = servers.get(0).getAsJsonObject();
        assertEquals(McpGatewayConstants.GATEWAY_SERVER_ID,
                server.get(McpGatewayConstants.KEY_NAME).getAsString());
        assertEquals(McpGatewayConstants.TRANSPORT_HTTP,
                server.get(McpGatewayConstants.KEY_TYPE).getAsString());
        assertEquals(ENDPOINT, server.get(McpGatewayConstants.KEY_URL).getAsString());

        JsonArray headers = server.getAsJsonArray(McpGatewayConstants.KEY_HEADERS);
        assertEquals(1, headers.size());
        JsonObject auth = headers.get(0).getAsJsonObject();
        assertEquals(McpGatewayConstants.HEADER_AUTHORIZATION,
                auth.get(McpGatewayConstants.KEY_NAME).getAsString());
        assertEquals("ACP 头值只接受字面值,token 内联", "Bearer " + TOKEN,
                auth.get(McpGatewayConstants.KEY_VALUE).getAsString());
    }

    @Test
    public void disabledOrNullConfigYieldsEmptyArray() {
        assertTrue(KimiAcpCliSession.buildMcpServers(null).isEmpty());
        assertTrue(KimiAcpCliSession.buildMcpServers(
                McpGatewayCliConfig.disabled("test")).isEmpty());
    }

    @Test
    public void blankEndpointYieldsEmptyArray() {
        McpGatewayCliConfig cfg = new McpGatewayCliConfig(true, true, 1L, null, "  ",
                Map.of(McpGatewayConstants.ENV_GATEWAY_TOKEN, TOKEN), List.of(), null);
        assertTrue(KimiAcpCliSession.buildMcpServers(cfg).isEmpty());
    }

    @Test
    public void nullTokenStillProducesEntryWithEmptyBearer() {
        McpGatewayCliConfig cfg = new McpGatewayCliConfig(true, true, 1L, null, ENDPOINT,
                Map.of(), List.of(), null);
        JsonObject server = KimiAcpCliSession.buildMcpServers(cfg).get(0).getAsJsonObject();
        JsonObject auth = server.getAsJsonArray(McpGatewayConstants.KEY_HEADERS)
                .get(0).getAsJsonObject();
        assertEquals("Bearer ", auth.get(McpGatewayConstants.KEY_VALUE).getAsString());
    }
}
