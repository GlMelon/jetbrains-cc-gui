package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class CliCompatibilityServiceTest {

    @Test
    public void evaluatesCompatibleBlockedOutdatedAheadAndUnknownVersions() throws IOException {
        CliCompatibilityService service = service(manifest(1, "1.0.0", "2.0.0", "1.5.0",
                CliVersionPolicy.WARN_ALLOW, CliVersionPolicy.WARN_ALLOW));

        assertDecision(service.evaluate(ProviderType.CLAUDE, "Claude Code 1.4.0"),
                CliCompatibilityStatus.COMPATIBLE, true, false);
        assertDecision(service.evaluate(ProviderType.CODEX, "codex-cli 1.5.0"),
                CliCompatibilityStatus.BLOCKED, false, true);
        assertDecision(service.evaluate(ProviderType.OPENCODE, "OpenCode 0.9.9"),
                CliCompatibilityStatus.UNSUPPORTED, false, true);
        assertDecision(service.evaluate(ProviderType.CLAUDE, "Claude Code 3.0.0"),
                CliCompatibilityStatus.AHEAD_ALLOWED, true, true);
        assertDecision(service.evaluate(ProviderType.CODEX, "development snapshot"),
                CliCompatibilityStatus.UNKNOWN_ALLOWED, true, true);
    }

    @Test
    public void appliesBlockingPoliciesForUnknownAndHigherVersions() throws IOException {
        CliCompatibilityService service = service(manifest(1, "1.0.0", "2.0.0", null,
                CliVersionPolicy.BLOCK, CliVersionPolicy.BLOCK));

        assertDecision(service.evaluate(ProviderType.CLAUDE, "nightly"),
                CliCompatibilityStatus.UNKNOWN_BLOCKED, false, true);
        assertDecision(service.evaluate(ProviderType.OPENCODE, "OpenCode 2.0.1"),
                CliCompatibilityStatus.AHEAD_BLOCKED, false, true);
    }


    @Test
    public void appliesUnknownPolicyWhenParsedNumericVersionOverflowsComparator() throws IOException {
        CliCompatibilityService service = service(manifest(1, "1.0.0", "2.0.0", null,
                CliVersionPolicy.WARN_ALLOW, CliVersionPolicy.BLOCK));

        assertDecision(service.evaluate(ProviderType.CLAUDE, "Claude Code 999999999999999999.0.0"),
                CliCompatibilityStatus.UNKNOWN_ALLOWED, true, true);
    }

    @Test
    public void refreshReplacesCurrentManifestSnapshot() throws IOException {
        byte[] bundled = manifest(1, "0.0.0", "1.0.0", null,
                CliVersionPolicy.WARN_ALLOW, CliVersionPolicy.WARN_ALLOW);
        byte[] remote = manifest(2, "0.0.0", "3.0.0", null,
                CliVersionPolicy.WARN_ALLOW, CliVersionPolicy.WARN_ALLOW);
        Path cache = Files.createTempDirectory("cli-compat-service");
        CliCompatibilityManifestRepository repository = new CliCompatibilityManifestRepository(
                cache,
                ignored -> bundled,
                (url, maxBytes) -> url.endsWith(".sig") ? bytes("valid") : remote,
                (manifest, signature) -> new String(signature, StandardCharsets.US_ASCII).equals("valid"),
                new CliCompatibilityManifestCodec(),
                "https://example.test/manifest.json",
                "https://example.test/manifest.json.sig");
        CliCompatibilityService service = new CliCompatibilityService(repository, CliVersionParserRegistry.defaults());

        assertEquals(1, service.currentManifest().manifest().revision());
        assertEquals(2, service.refreshManifest().manifest().revision());
        assertEquals(CliCompatibilityManifestSource.REMOTE, service.currentManifest().source());
    }

    private static CliCompatibilityService service(byte[] bundled) throws IOException {
        Path cache = Files.createTempDirectory("cli-compat-service");
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

    private static void assertDecision(
            CliCompatibilityDecision decision,
            CliCompatibilityStatus status,
            boolean allowed,
            boolean warning) {
        assertEquals(status, decision.status());
        assertEquals(allowed, decision.allowed());
        assertEquals(warning, decision.warning());
    }

    private static byte[] manifest(
            long revision,
            String minimum,
            String maximum,
            String blocked,
            CliVersionPolicy unknownPolicy,
            CliVersionPolicy higherPolicy) {
        String blockedJson = blocked == null ? "[]" : "[\"" + blocked + "\"]";
        String rule = "{\"minimumSupported\":\"" + minimum
                + "\",\"maximumTested\":\"" + maximum
                + "\",\"blockedVersions\":" + blockedJson
                + ",\"unknownVersionPolicy\":\"" + unknownPolicy
                + "\",\"higherVersionPolicy\":\"" + higherPolicy + "\"}";
        // providers 必须覆盖全部 ProviderType(codec.validate() fail-fast),按枚举 SSOT 动态生成。
        StringBuilder providers = new StringBuilder();
        for (ProviderType provider : ProviderType.values()) {
            if (providers.length() > 0) {
                providers.append(',');
            }
            providers.append('"').append(provider.value()).append("\":").append(rule);
        }
        String json = "{\"schemaVersion\":1,\"revision\":" + revision
                + ",\"generatedAt\":\"2026-07-22\",\"providers\":{" + providers + "}}";
        return bytes(json);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
