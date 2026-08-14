package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpGatewayConfigCollector#normalizeOpenCodeConfig} 的归一化单测。
 *
 * <p>背景:OpenCode 原生 mcp 配置为 {@code command: string[]} + {@code environment},Gateway
 * StdioMcpClient 期望 {@code command: string} + {@code args: string[]} + {@code env}。
 * 不归一化则 spawn(数组) 失败、全部 server BACKOFF(面板全红)——见 2026-08-14 bug。
 */
public class McpGatewayConfigCollectorTest {

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void commandArraySplitIntoCommandAndArgs() {
        JsonObject server = parse("{\"command\":[\"npx\",\"-y\",\"@agentmemory/mcp\"]}");
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(server);
        assertEquals("npx", config.get("command").getAsString());
        assertEquals("[\"-y\",\"@agentmemory/mcp\"]", config.get("args").toString());
    }

    @Test
    public void singleElementCommandArrayProducesNoArgs() {
        JsonObject server = parse("{\"command\":[\"dbx-mcp-server\"]}");
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(server);
        assertEquals("dbx-mcp-server", config.get("command").getAsString());
        assertFalse(config.has("args"));
    }

    @Test
    public void environmentKeyRenamedToEnv() {
        JsonObject server = parse("{\"command\":[\"cmd\",\"/c\"],\"environment\":{\"DBX_MCP_ALLOW_WRITES\":\"0\"}}");
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(server);
        assertEquals("{\"DBX_MCP_ALLOW_WRITES\":\"0\"}", config.get("env").toString());
        assertFalse(config.has("environment"));
    }

    @Test
    public void urlPreservedForRemoteTransport() {
        JsonObject server = parse("{\"type\":\"remote\",\"url\":\"https://mcp.example.com/sse\"}");
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(server);
        assertEquals("https://mcp.example.com/sse", config.get("url").getAsString());
        assertFalse(config.has("command"));
    }

    @Test
    public void stringCommandKeptAsIs() {
        // 防御分支:手工编辑/格式演进使 command 已是 string 时原样保留
        JsonObject server = parse("{\"command\":\"npx\",\"args\":[\"-y\",\"pkg\"]}");
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(server);
        assertEquals("npx", config.get("command").getAsString());
        assertEquals("[\"-y\",\"pkg\"]", config.get("args").toString());
    }

    @Test
    public void ideExeWithArgsAndEnvRealWorldShape() {
        // 真实 opencode.json 形状(idea_mcp):绝对路径 exe + 参数 + environment
        JsonObject server = parse("{\"type\":\"local\",\"enabled\":true,"
                + "\"command\":[\"D:\\\\IDEA\\\\idea64.exe\",\"stdioMcpServer\"],"
                + "\"environment\":{\"IDEA_MCP\":\"1\"}}");
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(server);
        assertEquals("D:\\IDEA\\idea64.exe", config.get("command").getAsString());
        assertEquals("[\"stdioMcpServer\"]", config.get("args").toString());
        assertEquals("{\"IDEA_MCP\":\"1\"}", config.get("env").toString());
        // id/type/enabled 由调用方单独处理,不进 config
        assertFalse(config.has("type"));
        assertFalse(config.has("enabled"));
        assertNull(config.get("id"));
    }

    @Test
    public void emptyServerYieldsEmptyConfig() {
        JsonObject config = McpGatewayConfigCollector.normalizeOpenCodeConfig(new JsonObject());
        assertTrue(config.entrySet().isEmpty());
    }
}
