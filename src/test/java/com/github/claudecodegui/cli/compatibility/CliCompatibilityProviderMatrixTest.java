package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** End-to-end provider matrix for parser selection and compatibility policy evaluation. */
public class CliCompatibilityProviderMatrixTest {

    @Test
    public void everyProviderParsesItsCliOutputAndAppliesTheSamePolicy() throws IOException {
        CliCompatibilityService service = service();
        Map<ProviderType, String> compatibleOutputs = new EnumMap<>(ProviderType.class);
        compatibleOutputs.put(ProviderType.CLAUDE, "Claude Code 1.4.0");
        compatibleOutputs.put(ProviderType.CODEX, "codex-cli 1.4.0");
        compatibleOutputs.put(ProviderType.OPENCODE, "OpenCode 1.4.0");

        Map<ProviderType, String> blockedOutputs = new EnumMap<>(ProviderType.class);
        blockedOutputs.put(ProviderType.CLAUDE, "Claude Code 1.5.0");
        blockedOutputs.put(ProviderType.CODEX, "codex-cli 1.5.0");
        blockedOutputs.put(ProviderType.OPENCODE, "OpenCode 1.5.0");

        for (ProviderType provider : ProviderType.values()) {
            CliCompatibilityDecision compatible = service.evaluate(provider, compatibleOutputs.get(provider));
            assertEquals(provider, compatible.provider());
            assertEquals("1.4.0", compatible.normalizedVersion());
            assertEquals(CliCompatibilityStatus.COMPATIBLE, compatible.status());
            assertTrue(provider.value() + " compatible version must be accepted", compatible.allowed());
            assertFalse(provider.value() + " compatible version must not warn", compatible.warning());

            CliCompatibilityDecision blocked = service.evaluate(provider, blockedOutputs.get(provider));
            assertEquals(provider, blocked.provider());
            assertEquals("1.5.0", blocked.normalizedVersion());
            assertEquals(CliCompatibilityStatus.BLOCKED, blocked.status());
            assertFalse(provider.value() + " blocked version must be rejected", blocked.allowed());
            assertTrue(provider.value() + " blocked version must warn", blocked.warning());
        }
    }

    private static CliCompatibilityService service() throws IOException {
        byte[] bundled = manifest();
        Path cache = Files.createTempDirectory("cli-compat-provider-matrix");
        CliCompatibilityManifestRepository repository = new CliCompatibilityManifestRepository(
                cache,
                ignored -> bundled,
                (url, maxBytes) -> {
                    throw new IOException("offline");
                },
                (manifest, signature) -> false,
                new CliCompatibilityManifestCodec(),
                "https://example.test/manifest.json",
                "https://example.test/manifest.json.sig");
        return new CliCompatibilityService(repository, CliVersionParserRegistry.defaults());
    }

    private static byte[] manifest() {
        String rule = "{\"minimumSupported\":\"1.0.0\","
                + "\"maximumTested\":\"2.0.0\","
                + "\"blockedVersions\":[\"1.5.0\"],"
                + "\"unknownVersionPolicy\":\"BLOCK\","
                + "\"higherVersionPolicy\":\"BLOCK\"}";
        String json = "{\"schemaVersion\":1,\"revision\":2026072901,"
                + "\"generatedAt\":\"2026-07-29\",\"providers\":{"
                + "\"claude\":" + rule + ","
                + "\"codex\":" + rule + ","
                + "\"opencode\":" + rule + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
