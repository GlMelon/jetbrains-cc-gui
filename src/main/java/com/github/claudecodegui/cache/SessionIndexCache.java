package com.github.claudecodegui.cache;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory cache for session indexes.
 * Caches historical session lists to avoid reading from the filesystem on every access.
 */
public class SessionIndexCache {

    private static final Logger LOG = Logger.getInstance(SessionIndexCache.class);

    // Singleton
    private static final SessionIndexCache INSTANCE = new SessionIndexCache();

    // Cache TTL: 5 minutes
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    // Claude cache: projectPath -> CacheEntry
    private final Map<String, CacheEntry<?>> claudeCache = new ConcurrentHashMap<>();

    // Codex cache: projectPath -> CacheEntry
    private final Map<String, CacheEntry<?>> codexCache = new ConcurrentHashMap<>();

    private SessionIndexCache() {
        // Private constructor
    }

    public static SessionIndexCache getInstance() {
        return INSTANCE;
    }

    /**
     * Cache entry.
     */
    public static class CacheEntry<T> {
        private final List<T> sessions;
        private final long lastDirModified;
        private final long fileCount;
        private final long totalFileSize;
        private final long cacheCreatedAt;

        public CacheEntry(List<T> sessions, long lastDirModified) {
            this(sessions, lastDirModified, -1, -1);
        }

        public CacheEntry(List<T> sessions, long lastDirModified, long fileCount) {
            this(sessions, lastDirModified, fileCount, -1);
        }

        public CacheEntry(List<T> sessions, long lastDirModified, long fileCount, long totalFileSize) {
            this.sessions = sessions;
            this.lastDirModified = lastDirModified;
            this.fileCount = fileCount;
            this.totalFileSize = totalFileSize;
            this.cacheCreatedAt = System.currentTimeMillis();
        }

        public List<T> getSessions() {
            return sessions;
        }

        public long getLastDirModified() {
            return lastDirModified;
        }

        public long getFileCount() {
            return fileCount;
        }

        public long getTotalFileSize() {
            return totalFileSize;
        }

        public long getCacheCreatedAt() {
            return cacheCreatedAt;
        }

        /**
         * Checks whether the cache has expired.
         */
        public boolean isExpired() {
            return System.currentTimeMillis() - cacheCreatedAt > CACHE_TTL_MS;
        }

        /**
         * Checks whether the cache is still valid.
         * @param currentDirModified current directory modification time
         */
        public boolean isValid(long currentDirModified) {
            if (isExpired()) {
                return false;
            }
            // If the directory modification time hasn't changed, the cache is still valid
            return currentDirModified == lastDirModified;
        }

        /**
         * Checks whether a recursive file tree fingerprint is still valid.
         *
         * @param currentLatestModified latest modification time among session files
         * @param currentFileCount      current session file count
         * @param currentTotalFileSize  current total session file size
         */
        public boolean isValidFileTree(long currentLatestModified, long currentFileCount, long currentTotalFileSize) {
            if (isExpired()) {
                return false;
            }
            return currentLatestModified == lastDirModified
                    && currentFileCount == fileCount
                    && currentTotalFileSize == totalFileSize;
        }
    }

    /**
     * Returns the cached Claude session list.
     * @param projectPath the project path
     * @param projectDir the project directory Path (used to check modification time)
     * @return the cached session list, or null if the cache is invalid
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getClaudeSessions(String projectPath, Path projectDir) {
        CacheEntry<T> entry = (CacheEntry<T>) claudeCache.get(projectPath);
        if (entry == null) {
            LOG.info("[SessionIndexCache] Claude cache miss: no entry for " + projectPath);
            return null;
        }

        long currentDirModified = getDirModifiedTime(projectDir);
        if (!entry.isValid(currentDirModified)) {
            LOG.info("[SessionIndexCache] Claude cache invalid: expired or dir changed for " + projectPath);
            claudeCache.remove(projectPath);
            return null;
        }

        LOG.info("[SessionIndexCache] Claude cache hit for " + projectPath + ", sessions: " + entry.getSessions().size());
        return entry.getSessions();
    }

    /**
     * Updates the Claude cache.
     */
    public <T> void updateClaudeCache(String projectPath, Path projectDir, List<T> sessions) {
        long dirModified = getDirModifiedTime(projectDir);
        CacheEntry<T> entry = new CacheEntry<>(sessions, dirModified);
        claudeCache.put(projectPath, entry);
        LOG.info("[SessionIndexCache] Claude cache updated for " + projectPath + ", sessions: " + sessions.size());
    }

    /**
     * Returns the cached Codex session list.
     * @param projectPath the project path
     * @param sessionsDir the sessions directory Path
     * @return the cached session list, or null if the cache is invalid
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getCodexSessions(String projectPath, Path sessionsDir) {
        CacheEntry<T> entry = (CacheEntry<T>) codexCache.get(projectPath);
        if (entry == null) {
            LOG.info("[SessionIndexCache] Codex cache miss: no entry for " + projectPath);
            return null;
        }

        FileTreeFingerprint fingerprint = getJsonlFileTreeFingerprint(sessionsDir);
        if (!entry.isValidFileTree(fingerprint.latestModified, fingerprint.fileCount, fingerprint.totalFileSize)) {
            LOG.info("[SessionIndexCache] Codex cache invalid: expired or file tree changed for " + projectPath);
            codexCache.remove(projectPath);
            return null;
        }

        LOG.info("[SessionIndexCache] Codex cache hit for " + projectPath + ", sessions: " + entry.getSessions().size());
        return entry.getSessions();
    }

    /**
     * Updates the Codex cache.
     */
    public <T> void updateCodexCache(String projectPath, Path sessionsDir, List<T> sessions) {
        FileTreeFingerprint fingerprint = getJsonlFileTreeFingerprint(sessionsDir);
        CacheEntry<T> entry = new CacheEntry<>(sessions, fingerprint.latestModified, fingerprint.fileCount, fingerprint.totalFileSize);
        codexCache.put(projectPath, entry);
        LOG.info("[SessionIndexCache] Codex cache updated for " + projectPath + ", sessions: " + sessions.size());
    }

    /**
     * Clears all caches.
     */
    public void clearAll() {
        claudeCache.clear();
        codexCache.clear();
        LOG.info("[SessionIndexCache] All caches cleared");
    }

    /**
     * Clears the cache for a specific project.
     */
    public void clearProject(String projectPath) {
        claudeCache.remove(projectPath);
        codexCache.remove(projectPath);
        LOG.info("[SessionIndexCache] Cache cleared for project: " + projectPath);
    }

    /**
     * Clears all Codex caches.
     * Codex uses "__all__" as the cache key, so deleting a session requires clearing the entire Codex cache.
     */
    public void clearAllCodexCache() {
        codexCache.clear();
        LOG.info("[SessionIndexCache] All Codex caches cleared");
    }

    /**
     * Returns the modification time of a directory.
     */
    private long getDirModifiedTime(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (Exception e) {
            LOG.warn("[SessionIndexCache] Failed to get dir modified time: " + e.getMessage());
            return 0;
        }
    }

    private FileTreeFingerprint getJsonlFileTreeFingerprint(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return new FileTreeFingerprint(0, 0, 0);
        }
        long fileCount = 0;
        long latestModified = 0;
        long totalFileSize = 0;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .toList()) {
                fileCount++;
                try {
                    totalFileSize += Files.size(path);
                    latestModified = Math.max(latestModified, Files.getLastModifiedTime(path).toMillis());
                } catch (IOException e) {
                    LOG.debug("[SessionIndexCache] Failed to stat Codex session file: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("[SessionIndexCache] Failed to fingerprint Codex session tree: " + e.getMessage());
            return new FileTreeFingerprint(0, 0, 0);
        }
        return new FileTreeFingerprint(latestModified, fileCount, totalFileSize);
    }

    private record FileTreeFingerprint(long latestModified, long fileCount, long totalFileSize) {
    }
}
