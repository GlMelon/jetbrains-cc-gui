package com.github.claudecodegui.mcp;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * gateway 注入 Streamable HTTP 改造(2026-09):三 provider 统一写 url 形态配置直连 gateway
 * {@code /mcp} 端点,替代 stdio 代理进程;token 一律经 env 变量注入,配置体只写变量引用
 * (Claude {@code ${VAR}} / Codex {@code bearer_token_env_var} / OpenCode {@code {env:VAR}})。
 *
 * <p>本测试覆盖:
 * <ul>
 *   <li>{@link McpGatewayConfigWriter#buildCodexOverrideArgs(String, List)} —— 生成 {@code -c} 扁平列表。</li>
 *   <li>{@link McpGatewayConfigWriter#buildOpenCodeConfigContent(String, List)} —— 生成 inline JSON。</li>
 *   <li>{@link McpGatewayConfigWriter#write} 的 Claude 文件产出与 token env(原 stdio 时代无覆盖)。</li>
 * </ul>
 */
public class McpGatewayConfigWriterTest {

    private static final String ENDPOINT = "http://127.0.0.1:11634" + McpGatewayConstants.MCP_ENDPOINT_PATH;
    private static final String TOKEN = "unit-test-gateway-token";

    // ============ buildCodexOverrideArgs ============

    @Test
    public void codexOverrideArgsInjectsGatewayUrlAndDisablesRealServers() {
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(
                ENDPOINT, Arrays.asList("dbx", "webstorm_mcp"));

        // 每条 -c 覆盖 = 2 个 argv 元素:3 条 melon_gateway(url/bearer_token_env_var/enabled) + 2 条禁真实 server
        assertEquals(10, args.size());
        for (int i = 0; i < args.size(); i += 2) {
            assertEquals("index " + i + " 应为 -c flag", "-c", args.get(i));
        }

        String joined = String.join("\n", args);
        assertTrue("须注入 melon_gateway.url(TOML literal 字符串)",
                joined.contains("mcp_servers.melon_gateway.url='" + ENDPOINT + "'"));
        assertTrue("token 只经 bearer_token_env_var 引用 env 变量名",
                joined.contains("mcp_servers.melon_gateway.bearer_token_env_var='"
                        + McpGatewayConstants.ENV_GATEWAY_TOKEN + "'"));
        assertTrue(joined.contains("mcp_servers.melon_gateway.enabled=true"));
        assertFalse("argv 不得再出现 stdio 时代的 command/args 覆盖",
                joined.contains("mcp_servers.melon_gateway.command")
                        || joined.contains("mcp_servers.melon_gateway.args"));
        // 逐个禁真实 server(合并语义:不禁则真实 server 仍直连=慢)
        assertTrue(joined.contains("mcp_servers.dbx.enabled=false"));
        assertTrue(joined.contains("mcp_servers.webstorm_mcp.enabled=false"));
    }

    @Test
    public void codexOverrideArgsNoRealServersOnlyInjectsGateway() {
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(
                ENDPOINT, Collections.emptyList());
        // 仅 3 条 melon_gateway 覆盖 = 6 元素,无禁真实 server 条目
        assertEquals(6, args.size());
        assertFalse("无真实 server 时不应生成 enabled=false",
                String.join(" ", args).contains(".enabled=false"));
    }

    @Test
    public void codexOverrideArgsSkipsGatewayServerIdInRealList() {
        // 防御:即便调用方误传 melon_gateway,也不应生成禁用自身的条目
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(
                ENDPOINT, Arrays.asList("dbx", McpGatewayConstants.GATEWAY_SERVER_ID));
        String joined = String.join(" ", args);
        assertTrue(joined.contains("mcp_servers.dbx.enabled=false"));
        assertFalse("不得禁用 melon_gateway 自身",
                joined.contains("mcp_servers." + McpGatewayConstants.GATEWAY_SERVER_ID + ".enabled=false"));
    }

    // ============ buildOpenCodeConfigContent ============

    @Test
    public void openCodeConfigContentInjectsRemoteGatewayAndDisablesRealServers() {
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                ENDPOINT, Arrays.asList("dbx", "ops-automation"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject mcp = root.getAsJsonObject("mcp");

        JsonObject gateway = mcp.getAsJsonObject(McpGatewayConstants.GATEWAY_SERVER_ID);
        assertEquals("remote", gateway.get("type").getAsString());
        assertEquals(ENDPOINT, gateway.get("url").getAsString());
        assertTrue(gateway.get("enabled").getAsBoolean());
        assertEquals("remote 默认 5s 超时对慢工具太短,须显式放大到 60s",
                60_000, gateway.get("timeout").getAsInt());
        assertEquals("token 只经 {env:VAR} 引用",
                "Bearer {env:" + McpGatewayConstants.ENV_GATEWAY_TOKEN + "}",
                gateway.getAsJsonObject("headers").get("Authorization").getAsString());
        // 逐个禁真实 server
        assertFalse(mcp.getAsJsonObject("dbx").get("enabled").getAsBoolean());
        assertFalse(mcp.getAsJsonObject("ops-automation").get("enabled").getAsBoolean());
    }

    @Test
    public void openCodeConfigContentNoRealServersOnlyGateway() {
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                ENDPOINT, Collections.emptyList());
        JsonObject mcp = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mcp");
        assertEquals(1, mcp.size()); // 仅 melon_gateway
        assertTrue(mcp.has(McpGatewayConstants.GATEWAY_SERVER_ID));
    }

    @Test
    public void openCodeConfigContentSkipsGatewayServerIdInRealList() {
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                ENDPOINT, Arrays.asList("dbx", McpGatewayConstants.GATEWAY_SERVER_ID));
        JsonObject mcp = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mcp");
        assertTrue(mcp.has("dbx"));
        JsonObject gateway = mcp.getAsJsonObject(McpGatewayConstants.GATEWAY_SERVER_ID);
        assertTrue("melon_gateway 须保持 enabled=true 定义", gateway.get("enabled").getAsBoolean());
    }

    // ============ write(Claude 文件产出 + token env)============

    @Test
    public void writeClaudeProducesHttpEntryWithTokenEnvReference() throws Exception {
        Path baseDir = Files.createTempDirectory("mcp-gateway-writer-test");
        McpGatewayConfigWriter writer = new McpGatewayConfigWriter(baseDir);

        McpGatewayCliConfig config = writer.write(
                ProviderType.CLAUDE, "tab1", 7L, ENDPOINT, TOKEN, Collections.emptyList());

        assertTrue(config.usable());
        assertEquals(ENDPOINT, config.endpoint());
        assertEquals("token 经 environment 注入 CLI 进程",
                TOKEN, config.environment().get(McpGatewayConstants.ENV_GATEWAY_TOKEN));

        String content = Files.readString(config.configPath());
        assertFalse("token 明文不得落入配置文件", content.contains(TOKEN));
        JsonObject gateway = JsonParser.parseString(content).getAsJsonObject()
                .getAsJsonObject("mcpServers")
                .getAsJsonObject(McpGatewayConstants.GATEWAY_SERVER_ID);
        assertEquals("http", gateway.get("type").getAsString());
        assertEquals(ENDPOINT, gateway.get("url").getAsString());
        assertEquals("Bearer ${" + McpGatewayConstants.ENV_GATEWAY_TOKEN + "}",
                gateway.getAsJsonObject("headers").get("Authorization").getAsString());
    }

    @Test
    public void writeCodexCarriesTokenEnvironmentForBearerEnvVar() throws Exception {
        Path baseDir = Files.createTempDirectory("mcp-gateway-writer-test");
        McpGatewayConfigWriter writer = new McpGatewayConfigWriter(baseDir);

        McpGatewayCliConfig config = writer.write(
                ProviderType.CODEX, "tab1", 7L, ENDPOINT, TOKEN, Collections.singletonList("dbx"));

        assertTrue(config.usable());
        assertEquals("bearer_token_env_var 引用的变量须随 spawn env 注入",
                TOKEN, config.environment().get(McpGatewayConstants.ENV_GATEWAY_TOKEN));
        assertFalse(String.join(" ", config.overrideArgs()).contains(TOKEN));
    }

    @Test
    public void writeOpenCodeCarriesConfigContentAndTokenEnvironment() throws Exception {
        Path baseDir = Files.createTempDirectory("mcp-gateway-writer-test");
        McpGatewayConfigWriter writer = new McpGatewayConfigWriter(baseDir);

        McpGatewayCliConfig config = writer.write(
                ProviderType.OPENCODE, "tab1", 7L, ENDPOINT, TOKEN, Collections.emptyList());

        assertTrue(config.usable());
        assertTrue(config.environment().containsKey(CliConstants.ENV_OPENCODE_CONFIG_CONTENT));
        assertEquals(TOKEN, config.environment().get(McpGatewayConstants.ENV_GATEWAY_TOKEN));
        assertFalse(config.environment().get(CliConstants.ENV_OPENCODE_CONFIG_CONTENT).contains(TOKEN));
    }

    @Test
    public void writeKimiProducesAcpHttpInjectionPayload() throws Exception {
        Path baseDir = Files.createTempDirectory("mcp-gateway-writer-test");
        McpGatewayConfigWriter writer = new McpGatewayConfigWriter(baseDir);

        McpGatewayCliConfig config = writer.write(
                ProviderType.KIMI, "tab1", 7L, ENDPOINT, TOKEN, Collections.emptyList());

        // kimi 走 ACP session/new mcpServers 动态注入:无文件产出,endpoint + token env 即可
        assertTrue(config.usable());
        assertEquals(ENDPOINT, config.endpoint());
        assertNull("kimi 不产配置文件", config.configPath());
        assertTrue(config.overrideArgs().isEmpty());
        assertEquals("token 经 environment 供 buildMcpServers 组装 ACP 头值",
                TOKEN, config.environment().get(McpGatewayConstants.ENV_GATEWAY_TOKEN));
    }

    @Test
    public void writeUnsupportedProviderStaysDisabled() throws Exception {
        Path baseDir = Files.createTempDirectory("mcp-gateway-writer-test");
        McpGatewayConfigWriter writer = new McpGatewayConfigWriter(baseDir);

        McpGatewayCliConfig config = writer.write(
                ProviderType.GROK, "tab1", 7L, ENDPOINT, TOKEN, Collections.emptyList());

        assertFalse(config.usable());
    }

    @Test
    public void supportsCoversKimiAcpChannel() {
        McpGatewayConfigWriter writer = new McpGatewayConfigWriter(Path.of("unused"));
        assertTrue(writer.supports(ProviderType.KIMI));
        assertFalse("grok/pi 等仍无注入机制", writer.supports(ProviderType.GROK));
    }
}
