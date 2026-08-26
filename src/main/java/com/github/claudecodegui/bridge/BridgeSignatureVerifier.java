package com.github.claudecodegui.bridge;

import com.github.claudecodegui.util.HashingUtil;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Computes and verifies the ai-bridge archive signature so the resolver can
 * decide whether the cached extraction matches the bundled archive.
 *
 * <p>Package-private helper extracted from {@link BridgeDirectoryResolver}.
 */
final class BridgeSignatureVerifier {

    private static final Logger LOG = Logger.getInstance(BridgeSignatureVerifier.class);

    static final String BRIDGE_HASH_FILE_NAME = "ai-bridge.hash";

    private BridgeSignatureVerifier() {
    }

    static boolean bridgeSignatureMatches(File versionFile, String expectedSignature) {
        if (versionFile == null || !versionFile.exists()) {
            return false;
        }
        try {
            String content = Files.readString(versionFile.toPath(), StandardCharsets.UTF_8).trim();
            return expectedSignature.equals(content);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Read precomputed hash from ai-bridge.hash file (generated at build time).
     * This avoids expensive runtime hash calculation.
     *
     * @param pluginDir The plugin directory containing ai-bridge.hash
     * @return The hash string, or null if file doesn't exist or read fails
     */
    static String readPrecomputedHash(File pluginDir) {
        File hashFile = new File(pluginDir, BRIDGE_HASH_FILE_NAME);
        if (!hashFile.exists()) {
            LOG.debug("[BridgeResolver] Precomputed hash file not found: " + hashFile.getAbsolutePath());
            return null;
        }

        try {
            String hash = Files.readString(hashFile.toPath(), StandardCharsets.UTF_8).trim();
            if (hash.isEmpty()) {
                LOG.warn("[BridgeResolver] Precomputed hash file is empty");
                return null;
            }
            LOG.debug("[BridgeResolver] Using precomputed hash: " + hash);
            return hash;
        } catch (IOException e) {
            LOG.warn("[BridgeResolver] Failed to read precomputed hash: " + e.getMessage());
            return null;
        }
    }

    /**
     * Calculate SHA-256 hash of a file.
     * NOTE: This is a fallback method only used when precomputed hash file is missing.
     * Prefer using readPrecomputedHash() when available.
     *
     * @param file The file to hash
     * @return Hex string of the hash, or null if calculation fails
     */
    static String calculateFileHash(File file) {
        if (file == null || !file.exists()) {
            return null;
        }

        LOG.info("[BridgeResolver] Calculating archive hash at runtime (fallback mode)");
        try {
            return HashingUtil.sha256Hex(file);
        } catch (Exception e) {
            LOG.warn("[BridgeResolver] Failed to calculate file hash: " + e.getMessage());
            return null;
        }
    }
}
