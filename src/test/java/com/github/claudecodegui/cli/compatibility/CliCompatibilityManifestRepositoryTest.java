package com.github.claudecodegui.cli.compatibility;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CliCompatibilityManifestRepositoryTest {

    @Test
    public void loadUsesBundledManifestWhenCacheIsAbsent() throws IOException {
        CliCompatibilityManifestRepository repository = repository(
                Files.createTempDirectory("cli-compat-repo"), manifest(1), null, false, new AtomicBoolean());

        CliCompatibilityManifestSnapshot snapshot = repository.load();

        assertEquals(1, snapshot.manifest().revision());
        assertEquals(CliCompatibilityManifestSource.BUNDLED, snapshot.source());
    }

    @Test
    public void signedRemoteRefreshPersistsForOfflineUse() throws IOException {
        Path cache = Files.createTempDirectory("cli-compat-repo");
        AtomicBoolean offline = new AtomicBoolean();
        CliCompatibilityManifestRepository repository = repository(cache, manifest(1), manifest(2), true, offline);

        CliCompatibilityManifestSnapshot refreshed = repository.refresh();
        assertEquals(2, refreshed.manifest().revision());
        assertEquals(CliCompatibilityManifestSource.REMOTE, refreshed.source());

        offline.set(true);
        CliCompatibilityManifestSnapshot cached = repository.load();
        assertEquals(2, cached.manifest().revision());
        assertEquals(CliCompatibilityManifestSource.CACHED_REMOTE, cached.source());
    }

    @Test
    public void invalidSignatureFallsBackWithoutWritingCache() throws IOException {
        Path cache = Files.createTempDirectory("cli-compat-repo");
        CliCompatibilityManifestRepository repository = repository(
                cache, manifest(1), manifest(2), false, new AtomicBoolean());

        CliCompatibilityManifestSnapshot snapshot = repository.refresh();

        assertEquals(1, snapshot.manifest().revision());
        assertEquals(CliCompatibilityManifestSource.BUNDLED, snapshot.source());
        assertFalse(Files.exists(cache.resolve("cli-compatibility-manifest.json")));
    }

    @Test
    public void staleSignedRemoteCannotRollBackCachedOrBundledRevision() throws IOException {
        CliCompatibilityManifestRepository repository = repository(
                Files.createTempDirectory("cli-compat-repo"), manifest(5), manifest(4), true, new AtomicBoolean());

        CliCompatibilityManifestSnapshot snapshot = repository.refresh();

        assertEquals(5, snapshot.manifest().revision());
        assertEquals(CliCompatibilityManifestSource.BUNDLED, snapshot.source());
    }

    @Test
    public void networkFailureUsesValidSignedCache() throws IOException {
        Path cache = Files.createTempDirectory("cli-compat-repo");
        AtomicBoolean offline = new AtomicBoolean();
        CliCompatibilityManifestRepository repository = repository(cache, manifest(1), manifest(3), true, offline);
        repository.refresh();
        offline.set(true);

        CliCompatibilityManifestSnapshot snapshot = repository.refresh();

        assertEquals(3, snapshot.manifest().revision());
        assertEquals(CliCompatibilityManifestSource.CACHED_REMOTE, snapshot.source());
    }

    private static CliCompatibilityManifestRepository repository(
            Path cache,
            byte[] bundled,
            byte[] remote,
            boolean signatureValid,
            AtomicBoolean offline) {
        return new CliCompatibilityManifestRepository(
                cache,
                ignored -> bundled,
                (url, maxBytes) -> {
                    if (offline.get()) {
                        throw new IOException("offline");
                    }
                    return url.endsWith(".sig") ? bytes("signature") : remote;
                },
                (manifestBytes, signatureBytes) -> signatureValid
                        && new String(signatureBytes, StandardCharsets.US_ASCII).equals("signature"),
                new CliCompatibilityManifestCodec(),
                "https://example.test/manifest.json",
                "https://example.test/manifest.json.sig");
    }

    private static byte[] manifest(long revision) {
        String rule = "{\"minimumSupported\":\"0.0.0\",\"maximumTested\":\"2.0.0\","
                + "\"blockedVersions\":[],\"unknownVersionPolicy\":\"WARN_ALLOW\","
                + "\"higherVersionPolicy\":\"WARN_ALLOW\"}";
        return bytes("{\"schemaVersion\":1,\"revision\":" + revision
                + ",\"generatedAt\":\"2026-07-22\",\"providers\":{"
                + "\"claude\":" + rule + ",\"codex\":" + rule + ",\"opencode\":" + rule + "}}");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
