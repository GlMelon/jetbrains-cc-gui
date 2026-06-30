package com.github.claudecodegui.provider.codex;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * Codex SDK MCP Gateway 对称性:与 Claude 的 SDK gateway(ClaudeSDKBridge +
 * SdkMcpGatewaySymmetryTest)平行,验证 Codex SDK 调用模式同样注入 gateway 绑定。
 *
 * <p>Codex 与 Claude 的不对称:Codex SDK 无 per-call mcpServers option(已查 @openai/codex-sdk
 * 类型确认),只能经 codexOptions.config(mcp_servers.melon_gateway)叠加——该翻译在 Node 侧
 * (services/codex/mcp-gateway-binding.js 的 applyCodexGateway),Java 侧只负责把
 * buildSdkMcpServers(CODEX) 产出的绑定序列化进 stdin(mcpGatewayBinding 字段),并经
 * ProjectBridgeRegistry 把 McpGatewayService 注入 CodexSDKBridge。
 *
 * <p>与 Claude 一致,Codex 有两条 send 路径(per-process sendMessage + daemon
 * sendMessageWithDaemonPreferred),均须注入;两者都在 CodexSDKBridge 内直接构建 stdinInput
 * (不像 Claude 把构建下沉到 executor),故注入点在本类内。
 */
public class CodexSdkMcpGatewaySymmetryTest {
    private static final String SERVICE =
            "src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java";
    private static final String BRIDGE =
            "src/main/java/com/github/claudecodegui/provider/codex/CodexSDKBridge.java";
    private static final String REGISTRY =
            "src/main/java/com/github/claudecodegui/provider/common/ProjectBridgeRegistry.java";

    @Test
    public void serviceAllowsCodexInSdkPath() throws Exception {
        String source = Files.readString(Path.of(SERVICE));
        assertTrue("SDK 路径必须放行 CODEX(与 CLAUDE 对称),而非硬性拒绝",
                source.contains("ProviderType.CODEX"));
    }

    @Test
    public void bridgeHoldsMcpGatewayServiceField() throws Exception {
        String source = Files.readString(Path.of(BRIDGE));
        assertTrue("CodexSDKBridge 必须持有 McpGatewayService 字段(与 ClaudeSDKBridge 对称)",
                source.contains("McpGatewayService mcpGatewayService"));
    }

    @Test
    public void bridgeBuildsCodexBindingOnBothSendPaths() throws Exception {
        String source = Files.readString(Path.of(BRIDGE));
        assertTrue("CodexSDKBridge 必须为 CODEX provider 构建 SDK 绑定",
                source.contains("buildSdkMcpServers(ProviderType.CODEX"));
        long buildCalls = source.lines()
                .filter(line -> line.contains("buildSdkMcpServers(ProviderType.CODEX"))
                .count();
        assertTrue("两条 send 路径(per-process + daemon)均须构建 CODEX 绑定,实际 " + buildCalls,
                buildCalls >= 2);
    }

    @Test
    public void bridgeSerializesBindingUnderStdinKey() throws Exception {
        String source = Files.readString(Path.of(BRIDGE));
        assertTrue("CodexSDKBridge 必须把绑定序列化进 stdin 的 mcpGatewayBinding 键",
                source.contains("\"mcpGatewayBinding\""));
    }

    @Test
    public void registryPassesGatewayServiceToCodexBridge() throws Exception {
        String source = Files.readString(Path.of(REGISTRY));
        assertTrue("ProjectBridgeRegistry 必须把 McpGatewayService 传入 CodexSDKBridge(与 Claude 对称)",
                source.contains("new CodexSDKBridge(McpGatewayService"));
    }
}
