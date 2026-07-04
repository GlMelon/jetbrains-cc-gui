package com.github.claudecodegui.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * gateway 注入免临时 home 重构(2026-07-02):Codex 走 {@code -c} 命令行覆盖 + 原生 codex.exe,
 * OpenCode 走 {@code OPENCODE_CONFIG_CONTENT} env 内联 JSON。两者都不再造临时 home / 复制配置文件。
 *
 * <p>本测试覆盖两个纯函数(包级 static,供 writeCodex/writeOpenCode 复用):
 * <ul>
 *   <li>{@link McpGatewayConfigWriter#buildCodexOverrideArgs(List, List)} —— 生成 {@code -c} 扁平列表。</li>
 *   <li>{@link McpGatewayConfigWriter#buildOpenCodeConfigContent(List, List)} —— 生成 inline JSON。</li>
 * </ul>
 */
public class McpGatewayConfigWriterTest {

    private static final List<String> GATEWAY_COMMAND = Arrays.asList(
            "node",
            "/bridge/mcp-gateway/gateway-stdio-client.js",
            "--state-file", "/tmp/gateway-state.json",
            "--revision", "7");

    // ============ buildCodexOverrideArgs ============

    @Test
    public void codexOverrideArgsInjectsGatewayAndDisablesRealServers() {
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(
                GATEWAY_COMMAND, Arrays.asList("dbx", "webstorm_mcp"));

        // 每条 -c 覆盖 = 2 个 argv 元素("-c" + value):4 条 melon_gateway + 2 条禁真实 server = 12
        assertEquals(12, args.size());
        // 每个 -c flag 都是 CODEX_ARG_C_CONFIG("-c")
        for (int i = 0; i < args.size(); i += 2) {
            assertEquals("index " + i + " 应为 -c flag", "-c", args.get(i));
        }

        String joined = String.join("\n", args);
        // melon_gateway 定义(元素用 TOML literal 字符串,见 codexOverrideArgsUsesTomlLiteralStringForWindowsBackslashPaths 的根因说明)
        assertTrue("须注入 melon_gateway.command(node,literal 字符串)",
                joined.contains("mcp_servers.melon_gateway.command='node'"));
        assertTrue("须注入 melon_gateway.args 数组(含 --state-file/--revision,原生 exe argv 直传)",
                joined.contains("mcp_servers.melon_gateway.args=['/bridge/mcp-gateway/gateway-stdio-client.js', '--state-file', '/tmp/gateway-state.json', '--revision', '7']"));
        assertTrue(joined.contains("mcp_servers.melon_gateway.enabled=true"));
        assertTrue(joined.contains("mcp_servers.melon_gateway.startup_timeout_sec=1"));
        // 逐个禁真实 server(合并语义:不禁则真实 server 仍直连=慢)
        assertTrue(joined.contains("mcp_servers.dbx.enabled=false"));
        assertTrue(joined.contains("mcp_servers.webstorm_mcp.enabled=false"));
    }

    @Test
    public void codexOverrideArgsNoRealServersOnlyInjectsGateway() {
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(
                GATEWAY_COMMAND, Collections.emptyList());
        // 仅 4 条 melon_gateway 覆盖 = 8 元素,无禁真实 server 条目
        assertEquals(8, args.size());
        assertFalse("无真实 server 时不应生成 enabled=false",
                String.join(" ", args).contains(".enabled=false"));
    }

    @Test
    public void codexOverrideArgsPreservesSpacesInPathViaTomlLiteral() {
        // 路径含空格(Windows 用户目录常见):经原生 exe argv 直传时单 arg 完整,
        // literal 字符串单引号包裹,空格原样保留在 arg 内。
        List<String> cmd = Arrays.asList("node", "/path with space/client.js", "--revision", "1");
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(cmd, Collections.emptyList());
        assertTrue("空格须保留在 TOML literal 字符串内",
                String.join(" ", args).contains("mcp_servers.melon_gateway.args=['/path with space/client.js', '--revision', '1']"));
    }

    @Test
    public void codexOverrideArgsUsesTomlLiteralStringForWindowsBackslashPaths() {
        // ═══════════════════════════════════════════════════════════════════════
        // Regression(2026-07-03 实测确认的 bug):
        // codex 的 -c key=value 在 TOML 解析前会对 value 做一次 \\→\ 反转义。
        // 若 args 元素用 TOML 基本字符串 "D:\\project",tomlString 把 \ 加倍成 \\,
        // 被 codex 还原成 \,致 "D:\project" 出现非法 TOML 转义 \p → TOML 解析失败
        // → codex 退回把整个值当字符串 → "invalid type: string, expected a sequence
        //   in `mcp_servers.melon_gateway.args`"(用户实测报错,IDE CLI 模式)。
        //
        // 修复:args 元素改用 TOML literal 字符串(单引号,不处理转义),
        // 不含 \\ 序列 → codex 的 \\→\ 预反转义是空操作 → TOML 正确解析数组。
        // 实测(D:/nodejs/.../codex.exe 直接 spawn):
        //   -c '...args=["D:\\path"]'  → invalid type: string  ✗
        //   -c '...args=['D:\path']'   → 正确解析为数组         ✓
        // exec 与 exec resume 都支持 -c( resume 不支持 -p,故不能改用 profile 文件)。
        // ═══════════════════════════════════════════════════════════════════════
        List<String> cmd = Arrays.asList("node",
                "D:\\project\\ai-bridge\\mcp-gateway\\gateway-stdio-client.js",
                "--state-file", "C:\\Users\\test\\state.json",
                "--revision", "1");
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(cmd, Collections.emptyList());
        String joined = String.join(" ", args);
        assertTrue("args 须用 TOML literal 字符串(单引号),反斜杠原样保留、不加倍",
                joined.contains("mcp_servers.melon_gateway.args=['D:\\project\\ai-bridge\\mcp-gateway\\gateway-stdio-client.js', '--state-file', 'C:\\Users\\test\\state.json', '--revision', '1']"));
        assertFalse("args 不得用基本字符串(双引号)——codex -c 会把反斜杠加倍序列预反转义致 TOML 解析失败",
                joined.contains("mcp_servers.melon_gateway.args=[\""));
    }

    @Test
    public void codexOverrideArgsSkipsGatewayServerIdInRealList() {
        // 防御:即便调用方误传 melon_gateway,也不应生成禁用自身的条目
        List<String> args = McpGatewayConfigWriter.buildCodexOverrideArgs(
                GATEWAY_COMMAND, Arrays.asList("dbx", McpGatewayConstants.GATEWAY_SERVER_ID));
        String joined = String.join(" ", args);
        assertTrue(joined.contains("mcp_servers.dbx.enabled=false"));
        assertFalse("不得禁用 melon_gateway 自身",
                joined.contains("mcp_servers." + McpGatewayConstants.GATEWAY_SERVER_ID + ".enabled=false"));
    }

    // ============ buildOpenCodeConfigContent ============

    @Test
    public void openCodeConfigContentInjectsGatewayAndDisablesRealServers() throws Exception {
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                GATEWAY_COMMAND, Arrays.asList("dbx", "ops-automation"));

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject mcp = root.getAsJsonObject("mcp");

        // melon_gateway 定义
        JsonObject gateway = mcp.getAsJsonObject(McpGatewayConstants.GATEWAY_SERVER_ID);
        assertEquals("local", gateway.get("type").getAsString());
        assertTrue(gateway.get("enabled").getAsBoolean());
        JsonArray cmd = gateway.getAsJsonArray("command");
        assertEquals("command 须为完整数组(含 node 二进制)", GATEWAY_COMMAND.size(), cmd.size());
        for (int i = 0; i < GATEWAY_COMMAND.size(); i++) {
            assertEquals(GATEWAY_COMMAND.get(i), cmd.get(i).getAsString());
        }
        // 逐个禁真实 server
        assertEquals(false, mcp.getAsJsonObject("dbx").get("enabled").getAsBoolean());
        assertEquals(false, mcp.getAsJsonObject("ops-automation").get("enabled").getAsBoolean());
    }

    @Test
    public void openCodeConfigContentNoRealServersOnlyGateway() throws Exception {
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                GATEWAY_COMMAND, Collections.emptyList());
        JsonObject mcp = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mcp");
        assertEquals(1, mcp.size()); // 仅 melon_gateway
        assertTrue(mcp.has(McpGatewayConstants.GATEWAY_SERVER_ID));
    }

    @Test
    public void openCodeConfigContentContainsNoHomeOrXdgKeys() throws Exception {
        // HOME/XDG 保持真实(不重定向)→ override 内容只含 mcp 段,无 home 重定向污染
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                GATEWAY_COMMAND, Collections.singletonList("dbx"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("root 仅含 mcp 键", 1, root.size());
        assertTrue(root.has("mcp"));
        for (String key : root.keySet()) {
            JsonElement el = root.get(key);
            assertFalse("不得包含 home/xdg 重定向键: " + key, key.equalsIgnoreCase("HOME")
                    || key.toUpperCase().contains("XDG") || key.equalsIgnoreCase("USERPROFILE"));
            // 值也不应是路径形态(防止误把 home 路径写进任何值)
        }
    }

    @Test
    public void openCodeConfigContentSkipsGatewayServerIdInRealList() throws Exception {
        String json = McpGatewayConfigWriter.buildOpenCodeConfigContent(
                GATEWAY_COMMAND, Arrays.asList("dbx", McpGatewayConstants.GATEWAY_SERVER_ID));
        JsonObject mcp = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("mcp");
        assertTrue(mcp.has("dbx"));
        // melon_gateway 存在(作为 gateway 定义),但不会被重复添加为 disabled 条目
        JsonObject gateway = mcp.getAsJsonObject(McpGatewayConstants.GATEWAY_SERVER_ID);
        assertTrue("melon_gateway 须保持 enabled=true 定义", gateway.get("enabled").getAsBoolean());
    }
}
