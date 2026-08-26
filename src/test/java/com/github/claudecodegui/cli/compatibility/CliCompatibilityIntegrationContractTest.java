package com.github.claudecodegui.cli.compatibility;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** Source-level guard for the three provider CLI detection paths and startup refresh registration. */
public class CliCompatibilityIntegrationContractTest {

    @Test
    public void allProviderCliDetectorsUseBackendCompatibilityService() throws IOException {
        assertIntegration(
                "src/main/java/com/github/claudecodegui/provider/claude/ClaudeCliDetector.java",
                "ProviderType.CLAUDE");
        assertIntegration(
                "src/main/java/com/github/claudecodegui/session/runtime/CodexCliResolver.java",
                "ProviderType.CODEX");
        // grok/kimi/pi/opencode 四 provider 的 CLI 检测路径合并到 ProviderCliResolver
        // (OpenCodeCliResolver 等为薄委托门面),版本门禁断言参数化形式。
        assertIntegration(
                "src/main/java/com/github/claudecodegui/cli/common/ProviderCliResolver.java",
                "type");
    }

    @Test
    public void pluginRegistersNonBlockingManifestUpdater() throws IOException {
        String pluginXml = readSource("src/main/resources/META-INF/plugin.xml");
        String updater = readSource(
                "src/main/java/com/github/claudecodegui/startup/CliCompatibilityManifestUpdater.java");

        assertTrue(pluginXml.contains(
                "com.github.claudecodegui.startup.CliCompatibilityManifestUpdater"));
        assertTrue(updater.contains("executeOnPooledThread"));
        assertTrue(updater.contains("REFRESH_STARTED.compareAndSet(false, true)"));
        assertTrue(updater.contains("CliCompatibilityService.getInstance().refreshManifest()"));
    }

    private static void assertIntegration(String sourcePath, String providerConstant) throws IOException {
        String source = readSource(sourcePath);
        assertTrue(source.contains("CliCompatibilityService.getInstance()"));
        assertTrue(source.contains("isVersionAccepted(" + providerConstant + ", trimmed)"));
    }

    private static String readSource(String sourcePath) throws IOException {
        return Files.readString(Path.of(sourcePath), StandardCharsets.UTF_8);
    }
}
