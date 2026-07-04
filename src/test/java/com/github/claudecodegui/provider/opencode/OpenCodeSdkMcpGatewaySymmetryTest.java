package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * OpenCode SDK MCP Gateway 对称性:与 Claude/Codex 的 SDK gateway 平行。
 *
 * <p>OpenCode 与 Claude/Codex 的根本不对称:OpenCode SDK = {@code opencode serve}(HTTP+SSE
 * 长驻进程),MCP 固化于 serve 启动期(从 opencode.json 经 XDG_CONFIG_HOME 读取),无法 per-turn
 * 注入。故 gateway 注入发生在 serve 启动:把 {@code buildSdkServeConfig(OPENCODE)} 产出的 env
 * (HOME/XDG_CONFIG_HOME 指向含 melon_gateway 的临时 opencode.json)注入 serve ProcessBuilder;
 * revision 漂移(MCP 设置变化)时重启 serve 以加载新 gateway 工具集——与 Claude/Codex 的
 * per-query revision 防漂移对称(只是 OpenCode 的"重建"对象是 serve 进程而非 per-query 实例)。
 */
public class OpenCodeSdkMcpGatewaySymmetryTest {
    private static final String SERVICE =
            "src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java";
    private static final String COORDINATOR =
            "src/main/java/com/github/claudecodegui/provider/opencode/OpenCodeDaemonCoordinator.java";
    private static final String BRIDGE =
            "src/main/java/com/github/claudecodegui/provider/opencode/OpenCodeSDKBridge.java";
    private static final String REGISTRY =
            "src/main/java/com/github/claudecodegui/provider/common/ProjectBridgeRegistry.java";

    // serveRevisionOf:从 gateway config 推导 serve 应固化的 revision 维度。
    // 不可用(功能关闭/未就绪)→ -1(serve 不带 gateway);可用(enabled && ready,2026-07-02 重构后
    // 不再要求 configPath:OpenCode 走 env OPENCODE_CONFIG_CONTENT、Codex 走 -c overrideArgs,均无文件)→ revision。

    @Test
    public void serveRevisionOfReturnsRevisionWhenUsable() {
        McpGatewayCliConfig cfg = new McpGatewayCliConfig(
                true, true, 7L, Path.of("/tmp/opencode.json"), Path.of("/tmp/state"),
                List.of("node", "client.js"), Map.of(), List.of(), null);
        assertEquals(7L, OpenCodeDaemonCoordinator.serveRevisionOf(cfg));
    }

    @Test
    public void serveRevisionOfReturnsRevisionWithoutConfigPathWhenUsable() {
        // 重构后 OpenCode gateway 走 env(无 configPath),usable() 仅看 enabled && ready → 仍固化 revision。
        McpGatewayCliConfig envBased = new McpGatewayCliConfig(
                true, true, 9L, null, null, List.of(), Map.of(), List.of(), null);
        assertEquals(9L, OpenCodeDaemonCoordinator.serveRevisionOf(envBased));
    }

    @Test
    public void serveRevisionOfReturnsNegativeWhenNotUsable() {
        assertEquals(-1L, OpenCodeDaemonCoordinator.serveRevisionOf(null));
        assertEquals(-1L, OpenCodeDaemonCoordinator.serveRevisionOf(McpGatewayCliConfig.disabled("off")));
        // enabled 但 not ready → usable()==false → -1
        McpGatewayCliConfig notReady = new McpGatewayCliConfig(
                true, false, 3L, null, null, List.of(), Map.of(), List.of(), null);
        assertEquals(-1L, OpenCodeDaemonCoordinator.serveRevisionOf(notReady));
    }

    @Test
    public void serviceExposesBuildSdkServeConfig() throws Exception {
        String source = Files.readString(Path.of(SERVICE));
        assertTrue("Service 必须暴露 buildSdkServeConfig(OpenCode serve 的 env 配置)",
                source.contains("public McpGatewayCliConfig buildSdkServeConfig"));
        assertTrue("buildSdkServeConfig 必须由 SDK 功能开关门控(对称 Claude/Codex SDK)",
                source.contains("McpGatewayFeatureFlags.isSdkEnabled"));
    }

    @Test
    public void coordinatorHoldsGatewayServiceAndAppliesEnv() throws Exception {
        String source = Files.readString(Path.of(COORDINATOR));
        assertTrue("Coordinator 必须持有 McpGatewayService 字段",
                source.contains("McpGatewayService mcpGatewayService"));
        assertTrue("Coordinator 必须构建 OpenCode serve gateway 配置",
                source.contains("buildSdkServeConfig(ProviderType.OPENCODE"));
        assertTrue("Coordinator 必须把 gateway env 注入 serve ProcessBuilder",
                source.contains("gatewayConfig.environment()"));
    }

    @Test
    public void bridgeHoldsMcpGatewayServiceField() throws Exception {
        String source = Files.readString(Path.of(BRIDGE));
        assertTrue("OpenCodeSDKBridge 必须持有 McpGatewayService 字段(对称 Claude/Codex)",
                source.contains("McpGatewayService mcpGatewayService"));
    }

    @Test
    public void registryPassesGatewayServiceToOpenCodeBridge() throws Exception {
        String source = Files.readString(Path.of(REGISTRY));
        assertTrue("ProjectBridgeRegistry 必须把 McpGatewayService 传入 OpenCodeSDKBridge(对称 Claude/Codex)",
                source.contains("new OpenCodeSDKBridge(McpGatewayService"));
    }
}
