package com.github.claudecodegui.cli.compatibility;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Loads the bundled CLI compatibility SSOT and accepts only signed remote updates.
 * A valid signed cache is used offline; otherwise loading falls back to the bundled resource.
 */
public final class CliCompatibilityManifestRepository {

    private static final Logger LOG = Logger.getInstance(CliCompatibilityManifestRepository.class);
    private static final String BUNDLED_RESOURCE = "/compatibility/cli-compatibility-manifest.json";
    private static final String REMOTE_MANIFEST_URL =
            "https://raw.githubusercontent.com/GlMelon/jetbrains-cc-gui/main/"
                    + "src/main/resources/compatibility/cli-compatibility-manifest.json";
    private static final String REMOTE_SIGNATURE_URL = REMOTE_MANIFEST_URL + ".sig";
    private static final String CACHE_MANIFEST_FILE = "cli-compatibility-manifest.json";
    private static final String CACHE_SIGNATURE_FILE = "cli-compatibility-manifest.sig";
    static final String PUBLIC_KEY_BASE64 =
            "MCowBQYDK2VwAyEAmoNuhgtBuDr4Ldy+DCOyCVLmwuLg9hqN70S4RYZq7+E=";
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final long MAX_SIGNATURE_BYTES = 8L * 1024L;

    @FunctionalInterface
    public interface ResourceLoader {
        byte[] load(String resourcePath) throws IOException;
    }

    @FunctionalInterface
    public interface RemoteFetcher {
        byte[] fetch(String url, long maxBytes) throws IOException;
    }

    private final Path cacheDirectory;
    private final ResourceLoader resourceLoader;
    private final RemoteFetcher remoteFetcher;
    private final ManifestSignatureVerifier signatureVerifier;
    private final CliCompatibilityManifestCodec codec;
    private final String remoteManifestUrl;
    private final String remoteSignatureUrl;

    public CliCompatibilityManifestRepository() {
        this(Paths.get(PathManager.getSystemPath(), "codriver", "cli-compatibility"),
                CliCompatibilityManifestRepository::loadClasspathResource,
                CliCompatibilityManifestRepository::httpGet,
                new Ed25519ManifestSignatureVerifier(PUBLIC_KEY_BASE64),
                new CliCompatibilityManifestCodec(),
                REMOTE_MANIFEST_URL,
                REMOTE_SIGNATURE_URL);
    }

    public CliCompatibilityManifestRepository(
            Path cacheDirectory,
            ResourceLoader resourceLoader,
            RemoteFetcher remoteFetcher,
            ManifestSignatureVerifier signatureVerifier,
            CliCompatibilityManifestCodec codec,
            String remoteManifestUrl,
            String remoteSignatureUrl) {
        this.cacheDirectory = cacheDirectory;
        this.resourceLoader = resourceLoader;
        this.remoteFetcher = remoteFetcher;
        this.signatureVerifier = signatureVerifier;
        this.codec = codec;
        this.remoteManifestUrl = remoteManifestUrl;
        this.remoteSignatureUrl = remoteSignatureUrl;
    }

    public CliCompatibilityManifestSnapshot load() {
        CliCompatibilityManifest bundled = loadBundled();
        CliCompatibilityManifest cached = loadCached();
        if (cached != null && cached.revision() >= bundled.revision()) {
            return new CliCompatibilityManifestSnapshot(cached, CliCompatibilityManifestSource.CACHED_REMOTE);
        }
        return new CliCompatibilityManifestSnapshot(bundled, CliCompatibilityManifestSource.BUNDLED);
    }

    public CliCompatibilityManifestSnapshot refresh() {
        CliCompatibilityManifestSnapshot fallback = load();
        try {
            byte[] manifestBytes = remoteFetcher.fetch(remoteManifestUrl, MAX_MANIFEST_BYTES);
            byte[] signatureBytes = remoteFetcher.fetch(remoteSignatureUrl, MAX_SIGNATURE_BYTES);
            if (!signatureVerifier.verify(manifestBytes, signatureBytes)) {
                LOG.warn("Rejected CLI compatibility manifest with invalid signature");
                return fallback;
            }
            CliCompatibilityManifest remote = codec.parse(manifestBytes);
            if (remote.revision() < fallback.manifest().revision()) {
                LOG.warn("Rejected stale CLI compatibility manifest revision " + remote.revision());
                return fallback;
            }
            writeCache(manifestBytes, signatureBytes);
            return new CliCompatibilityManifestSnapshot(remote, CliCompatibilityManifestSource.REMOTE);
        } catch (Exception e) {
            LOG.warn("Using offline CLI compatibility fallback: " + e.getMessage());
            return fallback;
        }
    }

    private CliCompatibilityManifest loadBundled() {
        try {
            return codec.parse(resourceLoader.load(BUNDLED_RESOURCE));
        } catch (IOException e) {
            throw new IllegalStateException("Bundled CLI compatibility manifest is unavailable", e);
        }
    }

    private CliCompatibilityManifest loadCached() {
        Path manifestPath = cacheDirectory.resolve(CACHE_MANIFEST_FILE);
        Path signaturePath = cacheDirectory.resolve(CACHE_SIGNATURE_FILE);
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(signaturePath)) {
            return null;
        }
        try {
            byte[] manifestBytes = Files.readAllBytes(manifestPath);
            byte[] signatureBytes = Files.readAllBytes(signaturePath);
            if (!signatureVerifier.verify(manifestBytes, signatureBytes)) {
                LOG.warn("Ignoring cached CLI compatibility manifest with invalid signature");
                return null;
            }
            return codec.parse(manifestBytes);
        } catch (Exception e) {
            LOG.warn("Ignoring invalid cached CLI compatibility manifest: " + e.getMessage());
            return null;
        }
    }

    private void writeCache(byte[] manifestBytes, byte[] signatureBytes) throws IOException {
        Files.createDirectories(cacheDirectory);
        writeAtomically(cacheDirectory.resolve(CACHE_MANIFEST_FILE), manifestBytes);
        writeAtomically(cacheDirectory.resolve(CACHE_SIGNATURE_FILE), signatureBytes);
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] loadClasspathResource(String resourcePath) throws IOException {
        try (InputStream stream = CliCompatibilityManifestRepository.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Missing resource " + resourcePath);
            }
            return readBody(stream, MAX_MANIFEST_BYTES);
        }
    }

    private static byte[] httpGet(String urlValue, long maxBytes) throws IOException {
        URI uri = URI.create(urlValue);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("CLI compatibility update URL must use HTTPS");
        }
        URL url = uri.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, text/plain");
            connection.setRequestProperty("User-Agent", "CoDriver-CLI-Compatibility");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " from CLI compatibility endpoint");
            }
            try (InputStream stream = connection.getInputStream()) {
                return readBody(stream, maxBytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    static byte[] readBody(InputStream stream, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read = stream.read(buffer);
        while (read != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("CLI compatibility response exceeds size limit");
            }
            output.write(buffer, 0, read);
            read = stream.read(buffer);
        }
        return output.toByteArray();
    }
}
