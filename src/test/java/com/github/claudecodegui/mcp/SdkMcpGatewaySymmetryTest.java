package com.github.claudecodegui.mcp;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * SDK 调用模式对称性:与 CLI 模式(CliMcpGatewaySymmetryTest)平行,验证 Service
 * 暴露 SDK 外观、快照刷新由 CLI 与 SDK 路径共享、首阶段仅 Claude。
 */
public class SdkMcpGatewaySymmetryTest {
    private static final String SERVICE =
            "src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java";

    @Test
    public void serviceExposesBuildSdkMcpServers() throws Exception {
        String source = Files.readString(Path.of(SERVICE));
        assertTrue("Service must expose buildSdkMcpServers returning the SDK binding",
                source.contains("public McpGatewaySdkBinding buildSdkMcpServers"));
        assertTrue("SDK path must be gated by the SDK feature flag",
                source.contains("McpGatewayFeatureFlags.isSdkEnabled"));
        assertTrue("SDK path must reuse the disabled binding factory on fallback",
                source.contains("McpGatewaySdkBinding.disabled"));
        assertTrue("SDK path must spawn the stdio client script",
                source.contains("STDIO_CLIENT_SCRIPT_PATH"));
    }

    @Test
    public void applySnapshotSharedByCliAndSdkPaths() throws Exception {
        String source = Files.readString(Path.of(SERVICE));
        assertTrue(source.contains("applySnapshot(projectPath)"));
        long applyCalls = source.lines()
                .filter(line -> line.contains("applySnapshot(projectPath)"))
                .count();
        assertTrue("applySnapshot must be shared by CLI refreshConfig and SDK buildSdkMcpServers",
                applyCalls >= 2);
    }

    @Test
    public void sdkBindingCommandCarriesStateFileAndRevision() throws Exception {
        String source = Files.readString(Path.of(SERVICE));
        assertTrue(source.contains("ARG_STATE_FILE"));
        assertTrue(source.contains("ARG_REVISION"));
    }
}
