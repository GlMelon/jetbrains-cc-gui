package com.github.claudecodegui.mcp;

import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link McpGatewayConfigCollector#normalizeOpenCodeConfig} 的归一化单测,
 * 以及 {@link McpGatewayConfigCollector#mergeServers} 的全局优先收录 + serverId 去重单测。
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

    @Test
    public void mergeServersTagsGlobalEntriesWithGlobalSource() {
        List<McpGatewayServerSpec> specs = McpGatewayConfigCollector.mergeServers(
                List.of(parse("{\"id\":\"shared\",\"command\":\"npx\"}")),
                List.of(), List.of(), List.of());

        assertEquals(1, specs.size());
        assertEquals(McpGatewayConstants.SOURCE_GLOBAL, specs.get(0).sourceProvider());
        assertEquals("shared", specs.get(0).serverId());
    }

    @Test
    public void mergeServersSkipsNativeWriteThroughMirrorsOfGlobal() {
        // 同一 serverId 出现在全局列表与三家原生配置(write-through 镜像)→ 只留全局条目
        List<McpGatewayServerSpec> specs = McpGatewayConfigCollector.mergeServers(
                List.of(parse("{\"id\":\"shared\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"shared\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"shared\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"shared\",\"command\":[\"npx\"]}")));

        assertEquals(1, specs.size());
        assertEquals(McpGatewayConstants.SOURCE_GLOBAL, specs.get(0).sourceProvider());
    }

    @Test
    public void mergeServersKeepsNativeOnlyExtrasWithOwnProviderTag() {
        List<McpGatewayServerSpec> specs = McpGatewayConfigCollector.mergeServers(
                List.of(),
                List.of(parse("{\"id\":\"claude-only\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"codex-only\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"opencode-only\",\"command\":[\"npx\"]}")));

        assertEquals(3, specs.size());
        assertEquals(CommonConstants.PROVIDER_CLAUDE, specs.get(0).sourceProvider());
        assertEquals("claude-only", specs.get(0).serverId());
        assertEquals(CommonConstants.PROVIDER_CODEX, specs.get(1).sourceProvider());
        assertEquals("codex-only", specs.get(1).serverId());
        assertEquals(CommonConstants.PROVIDER_OPENCODE, specs.get(2).sourceProvider());
        assertEquals("opencode-only", specs.get(2).serverId());
    }

    @Test
    public void mergeServersNativeOnlyIdCollisionPrefersClaudeThenCodex() {
        // claude > codex > opencode:同 serverId 先到先得
        List<McpGatewayServerSpec> claudeWins = McpGatewayConfigCollector.mergeServers(
                List.of(),
                List.of(parse("{\"id\":\"dup\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"dup\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"dup\",\"command\":[\"npx\"]}")));
        assertEquals(1, claudeWins.size());
        assertEquals(CommonConstants.PROVIDER_CLAUDE, claudeWins.get(0).sourceProvider());

        List<McpGatewayServerSpec> codexWins = McpGatewayConfigCollector.mergeServers(
                List.of(), List.of(),
                List.of(parse("{\"id\":\"dup\",\"command\":\"npx\"}")),
                List.of(parse("{\"id\":\"dup\",\"command\":[\"npx\"]}")));
        assertEquals(1, codexWins.size());
        assertEquals(CommonConstants.PROVIDER_CODEX, codexWins.get(0).sourceProvider());
    }
}
