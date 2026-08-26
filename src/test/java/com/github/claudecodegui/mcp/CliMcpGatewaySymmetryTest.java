package com.github.claudecodegui.mcp;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class CliMcpGatewaySymmetryTest {
    @Test
    public void allCliProvidersReferenceGatewayService() throws Exception {
        assertContains("src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliSession.java");
        assertContains("src/main/java/com/github/claudecodegui/cli/codex/CodexCliSession.java");
        // opencode(及合并进的 grok/kimi/pi)的 gateway 接入已上移到公共基类。
        assertContains("src/main/java/com/github/claudecodegui/cli/common/AbstractRunOnceCliSession.java");
    }

    @Test
    public void nodeProcessRegistryRecognizesGatewayProcesses() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/github/claudecodegui/service/NodeProcessRegistry.java"));
        assertTrue(source.contains(McpGatewayConstants.SERVER_SCRIPT_NAME));
        assertTrue(source.contains("gateway-stdio-client.js"));
    }

    private static void assertContains(String file) throws Exception {
        String source = Files.readString(Path.of(file));
        assertTrue(source.contains("McpGatewayService"));
        assertTrue(source.contains("buildCliConfig"));
        assertTrue(source.contains("McpGatewayCliConfig.disabled"));
    }
}
