package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.model.FileSortItem;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Container for file action handlers (B2 迁移).
 * Coordinates file listing/opening/browser/class/linkify/path-resolve operations.
 * Also carries the static file-collection infrastructure shared by the
 * {@code *Collector} classes in this package.
 */
public class FileActionHandlers {

    private static final Logger LOG = Logger.getInstance(FileActionHandlers.class);

    private static final Gson GSON = GsonHolder.GSON;

    private final HandlerContext context;

    private final OpenFileHandler openFileHandler;
    private final OpenClassHandler openClassHandler;
    private final OpenFileCollector openFileCollector;
    private final RecentFileCollector recentFileCollector;
    private final FileSystemCollector fileSystemCollector;
    private final RuntimeContextCollector runtimeContextCollector;

    public FileActionHandlers(HandlerContext context) {
        this.context = context;
        this.openFileHandler = new OpenFileHandler(context);
        this.openClassHandler = new OpenClassHandler(context);
        this.openFileCollector = new OpenFileCollector(context);
        this.recentFileCollector = new RecentFileCollector(context);
        this.fileSystemCollector = new FileSystemCollector();
        this.runtimeContextCollector = new RuntimeContextCollector(context);
    }

    // ── dispatch helpers ──

    private void dispatchEvent(String event, String data) {
        context.dispatchEvent(event, data);
    }

    private String escapeJs(String s) {
        return context.escapeJs(s);
    }

    // ============================================================================
    // Operations
    // ============================================================================

    public void handleListFiles(String content) {
        CompletableFuture.runAsync(() -> {
            // Parse outside try so we can still echo requestId on failure
            FileListRequest request = parseRequest(content);
            try {
                // 1. Get base path
                String basePath = getEffectiveBasePath();

                // 2. Initialize file set (deduplication)
                FileSet fileSet = new FileSet();

                // 3. Collect files
                List<JsonObject> files = new ArrayList<>();

                // Priority 0: Active Terminals
                runtimeContextCollector.collectTerminals(files, request);

                // Priority 0: Active Services
                runtimeContextCollector.collectServices(files, request);

                // Priority 1: Currently open files
                openFileCollector.collect(files, fileSet, basePath, request);

                // Priority 2: Recently opened files
                recentFileCollector.collect(files, fileSet, basePath, request);

                // Priority 3: File system scan
                fileSystemCollector.collect(files, fileSet, basePath, request);

                // 4. Sort (match score when searching, else priority/path)
                sortFiles(files, request);

                // 5. Return result (echo requestId so frontend can drop stale responses)
                sendResult(files, request.requestId);
            } catch (ProcessCanceledException e) {
                // Still reply so the frontend does not hang on an empty dropdown
                sendResult(new ArrayList<>(), request.requestId);
                throw e;
            } catch (Exception e) {
                LOG.error("[FileHandler] Failed to list files: " + e.getMessage(), e);
                // Always respond — a silent failure shows as "flash then empty" in the UI
                sendResult(new ArrayList<>(), request.requestId);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    public void handleOpenFile(String content) {
        openFileHandler.handleOpenFile(content);
    }

    public void handleOpenBrowser(String content) {
        openFileHandler.handleOpenBrowser(content);
    }

    public void handleOpenClass(String content) {
        openClassHandler.handleOpenClass(content);
    }

    public void handleGetLinkifyCapabilities() {
        String capabilitiesJson = OpenClassHandler.buildCapabilitiesJson();
        ApplicationManager.getApplication().invokeLater(() ->
            dispatchEvent(DownstreamEvent.LINKIFY_UPDATE.value(), escapeJs(capabilitiesJson))
        );
    }

    /**
     * Resolve a file path to a project-relative display path and return the result to the frontend.
     *
     * [归一化重构] 后端收到的 content 是 JSON 格式 { path, __requestId }(由前端 bridgeHub.request 注入)。
     * 响应经 dispatchEvent(DownstreamEvent.FILE_PATH_RESOLVED.value(), ...) 派发到 hub 的响应路由,携带 __requestId 供
     * hub 按 requestId 匹配并 resolve 对应的 Promise。兼容旧格式(纯字符串 content)作为 fallback。
     */
    public void handleResolveFilePath(String content) {
        // 解析 content:新格式 { path, __requestId } 或旧格式纯字符串(兼容)。
        String filePath = content;
        String requestId = null;
        try {
            JsonObject parsed = GSON.fromJson(content, JsonObject.class);
            if (parsed != null && parsed.has("path")) {
                filePath = parsed.get("path").getAsString();
                if (parsed.has("__requestId")) {
                    requestId = parsed.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON(旧格式纯字符串 filePath)——用原始 content 作为 filePath。
        }

        final String finalFilePath = filePath;
        final String finalRequestId = requestId;

        CompletableFuture.runAsync(() -> {
            try {
                String resolvedPath = openFileHandler.resolveDisplayPath(finalFilePath);

                JsonObject result = new JsonObject();
                result.addProperty("path", finalFilePath);
                result.addProperty("resolvedPath", resolvedPath);
                if (finalRequestId != null) {
                    result.addProperty("__requestId", finalRequestId);
                }
                String resultJson = GSON.toJson(result);

                ApplicationManager.getApplication().invokeLater(() -> {
                    dispatchEvent(DownstreamEvent.FILE_PATH_RESOLVED.value(), resultJson);
                });
            } catch (Exception e) {
                LOG.error("[FileHandler] Failed to resolve file path: " + e.getMessage(), e);
                JsonObject errorResult = new JsonObject();
                errorResult.addProperty("path", finalFilePath);
                errorResult.addProperty("resolvedPath", (String) null);
                if (finalRequestId != null) {
                    errorResult.addProperty("__requestId", finalRequestId);
                }
                String errorJson = GSON.toJson(errorResult);
                ApplicationManager.getApplication().invokeLater(() -> {
                    dispatchEvent(DownstreamEvent.FILE_PATH_RESOLVED.value(), errorJson);
                });
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    /**
     * Send results back to the frontend.
     */
    private void sendResult(List<JsonObject> files, Long requestId) {
        JsonObject result = new JsonObject();
        result.add("files", GSON.toJsonTree(files));
        if (requestId != null) {
            result.addProperty("requestId", requestId);
        }
        String resultJson = GSON.toJson(result);

        ApplicationManager.getApplication().invokeLater(() -> {
            dispatchEvent(DownstreamEvent.FILE_LIST_RESULT.value(), escapeJs(resultJson));
        });
    }

    /**
     * Parse the request.
     */
    private FileListRequest parseRequest(String content) {
        if (content == null || content.isEmpty()) {
            return new FileListRequest("", "", null);
        }

        try {
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            String query = json.has("query") ? json.get("query").getAsString() : "";
            String currentPath = json.has("currentPath") ? json.get("currentPath").getAsString() : "";
            Long requestId = null;
            if (json.has("requestId") && !json.get("requestId").isJsonNull()) {
                try {
                    requestId = json.get("requestId").getAsLong();
                } catch (Exception ignored) {
                    // Non-numeric requestId — leave null; frontend falls back to client re-filter
                }
            }
            return new FileListRequest(query, currentPath, requestId);
        } catch (Exception e) {
            // If not JSON, treat as plain text query
            return new FileListRequest(content.trim(), "", null);
        }
    }

    /**
     * Get the effective base path.
     * Ensures a non-null path is always returned with proper fallback chain
     */
    private String getEffectiveBasePath() {
        if (context.getSession() != null) {
            String cwd = context.getSession().getCwd();
            if (cwd != null && !cwd.isEmpty()) {
                LOG.debug("[FileHandler] Using session cwd as base path: " + cwd);
                return cwd;
            }
        }

        if (context.getProject() != null) {
            String projectPath = context.getProject().getBasePath();
            if (projectPath != null) {
                LOG.debug("[FileHandler] Using project base path: " + projectPath);
                return projectPath;
            }
        }

        String userHome = PlatformUtils.getHomeDirectory();
        if (userHome != null && !userHome.isEmpty()) {
            LOG.debug("[FileHandler] Using user.home as base path: " + userHome);
            return userHome;
        }

        // Final fallback - should never happen but prevents null
        LOG.warn("[FileHandler] All base path sources failed, using current directory");
        return System.getProperty("user.dir", ".");
    }

    /**
     * Sort files.
     * When the user is searching, rank by fuzzy match score (filename prefix >
     * substring > relative path > subsequence) before priority/path heuristics.
     */
    private void sortFiles(List<JsonObject> files, FileListRequest request) {
        if (files.isEmpty()) { return; }

        // 1. Wrap as SortItem, pre-read/compute sorting fields
        List<FileSortItem> items = new ArrayList<>(files.size());
        for (JsonObject json : files) {
            items.add(new FileSortItem(json));
        }

        final boolean rankByQuery = request != null && request.hasQuery;

        // 2. Sort
        items.sort((a, b) -> {
            if (rankByQuery) {
                int scoreDiff = request.score(b.name, b.path) - request.score(a.name, a.path);
                if (scoreDiff != 0) {
                    return scoreDiff;
                }
            }

            // Priority 1 & 2: Keep original order (stability) when not searching
            if (!rankByQuery && a.priority < 3 && b.priority < 3) {
                return 0;
            }

            // Different priority: lower value comes first
            if (a.priority != b.priority) {
                return a.priority - b.priority;
            }

            // Priority 3+: Sort by depth -> parent -> type -> name
            int depthDiff = a.getDepth() - b.getDepth();
            if (depthDiff != 0) { return depthDiff; }

            int parentDiff = a.getParentPath().compareToIgnoreCase(b.getParentPath());
            if (parentDiff != 0) { return parentDiff; }

            if (a.isDir != b.isDir) {
                return a.isDir ? -1 : 1;
            }

            return a.name.compareToIgnoreCase(b.name);
        });

        // 3. Write back to original list
        files.clear();
        for (FileSortItem item : items) {
            files.add(item.json);
        }
    }

    // ============================================================================
    // Static utility methods shared with collectors
    // ============================================================================

    /**
     * Get relative path. Never throws — falls back to the file name when the
     * absolute path is not under basePath (symlink / normalization mismatch).
     */
    static String getRelativePath(File file, String basePath) {
        if (file == null) {
            return "";
        }
        String absolute = file.getAbsolutePath().replace("\\", "/");
        if (basePath == null || basePath.isEmpty()) {
            return absolute;
        }
        String normalizedBase = basePath.replace("\\", "/");
        // Ensure base ends without trailing slash for clean prefix check
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        if (absolute.equals(normalizedBase)) {
            return "";
        }
        if (absolute.startsWith(normalizedBase + "/")) {
            return absolute.substring(normalizedBase.length() + 1);
        }
        // Not under base (different canonicalization) — use name only so matching
        // does not use absolute path segments like "jetbrains".
        return file.getName();
    }

    /**
     * Create a file object.
     */
    static JsonObject createFileObject(File file, String name, String relativePath) {
        JsonObject fileObj = new JsonObject();
        fileObj.addProperty("name", name);
        fileObj.addProperty("path", relativePath);
        fileObj.addProperty("absolutePath", file.getAbsolutePath().replace("\\", "/"));
        fileObj.addProperty("type", file.isDirectory() ? "directory" : "file");

        if (file.isFile()) {
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex > 0) {
                fileObj.addProperty("extension", name.substring(dotIndex + 1));
            }
        }
        return fileObj;
    }

    /**
     * Create a file object (from VirtualFile, avoiding physical I/O).
     */
    static JsonObject createFileObject(VirtualFile file, String relativePath) {
        JsonObject fileObj = new JsonObject();
        String name = file.getName();
        fileObj.addProperty("name", name);
        fileObj.addProperty("path", relativePath);
        fileObj.addProperty("absolutePath", file.getPath()); // VirtualFile path uses /
        fileObj.addProperty("type", file.isDirectory() ? "directory" : "file");

        if (!file.isDirectory()) {
            String extension = file.getExtension();
            if (extension != null) {
                fileObj.addProperty("extension", extension);
            }
        }
        return fileObj;
    }

    /**
     * Add a VirtualFile to the list.
     */
    static void addVirtualFile(VirtualFile vf, String basePath, List<JsonObject> files, FileSet fileSet, FileListRequest request, int priority) {
        // Enhanced null safety checks
        if (vf == null || !vf.isValid() || vf.isDirectory()) { return; }
        if (basePath == null) {
            LOG.warn("[FileHandler] basePath is null in addVirtualFile, skipping file");
            return;
        }

        String name = vf.getName();
        if (FileSystemCollector.shouldSkipInSearch(name, false)) { return; }

        String path = vf.getPath();
        if (path == null) {
            LOG.warn("[FileHandler] VirtualFile path is null for: " + name);
            return;
        }
        if (!fileSet.tryAdd(path)) { return; }

        // Calculate relative path (safe when path is not under basePath)
        String relativePath = path;
        String normalizedBase = basePath.replace("\\", "/");
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        String normalizedPath = path.replace("\\", "/");
        if (normalizedPath.equals(normalizedBase)) {
            relativePath = name;
        } else if (normalizedPath.startsWith(normalizedBase + "/")) {
            relativePath = normalizedPath.substring(normalizedBase.length() + 1);
        } else {
            // Outside base / normalization mismatch — use basename only so
            // absolute segments (e.g. "jetbrains") never drive matching.
            relativePath = name;
        }

        // Match query
        if (request.matches(name, relativePath)) {
            JsonObject obj = createFileObject(vf, relativePath);
            obj.addProperty("priority", priority);
            files.add(obj);
        }
    }

    // ============================================================================
    // Internal helper classes (shared with collectors)
    // ============================================================================

    /**
     * File list request wrapper.
     */
    static class FileListRequest {

        final String query;
        final String queryLower;
        final String currentPath;
        final boolean hasQuery;
        /** Correlates async frontend requests; may be null for legacy callers. */
        final Long requestId;

        FileListRequest(String query, String currentPath) {
            this(query, currentPath, null);
        }

        FileListRequest(String query, String currentPath, Long requestId) {
            this.query = query != null ? query : "";
            this.queryLower = this.query.toLowerCase();
            this.currentPath = currentPath != null ? currentPath : "";
            this.hasQuery = !this.query.isEmpty();
            this.requestId = requestId;
        }

        /**
         * Match query against file name and relative path only.
         * Supports substring and subsequence (fuzzy) matching.
         * Absolute paths are intentionally ignored — a project root like
         * {@code jetbrains-cc-gui} would otherwise match nearly every letter.
         */
        boolean matches(String name, String relativePath) {
            return score(name, relativePath) > 0 || !hasQuery;
        }

        /**
         * Higher is better. 0 means no match when {@link #hasQuery} is true.
         */
        int score(String name, String relativePath) {
            if (!hasQuery) {
                return 1;
            }
            String lowerName = name != null ? name.toLowerCase() : "";
            String pathForMatch = pathForMatching(relativePath);

            if (lowerName.equals(queryLower)) {
                return 1000;
            }
            if (lowerName.startsWith(queryLower)) {
                return 900;
            }
            if (lowerName.contains(queryLower)) {
                return 800;
            }
            if (!pathForMatch.isEmpty() && pathForMatch.contains(queryLower)) {
                return 600;
            }
            if (fuzzySubsequenceMatch(lowerName, queryLower)) {
                return 400;
            }
            if (!pathForMatch.isEmpty() && fuzzySubsequenceMatch(pathForMatch, queryLower)) {
                return 200;
            }
            return 0;
        }

        /**
         * Use only relative-looking paths for matching. Absolute paths (and
         * failed relativization leftovers) would over-match via project folder
         * names.
         */
        private static String pathForMatching(String relativePath) {
            if (relativePath == null || relativePath.isEmpty()) {
                return "";
            }
            String normalized = relativePath.replace('\\', '/');
            if (isAbsoluteLikePath(normalized)) {
                return "";
            }
            return normalized.toLowerCase();
        }

        private static boolean isAbsoluteLikePath(String path) {
            if (path.startsWith("/") || path.startsWith("\\")) {
                return true;
            }
            if (path.length() >= 3
                    && Character.isLetter(path.charAt(0))
                    && path.charAt(1) == ':'
                    && (path.charAt(2) == '/' || path.charAt(2) == '\\')) {
                return true;
            }
            return path.startsWith("//") || path.startsWith("\\\\");
        }

        /**
         * Every query character appears in order in text (IDE-style fuzzy).
         */
        static boolean fuzzySubsequenceMatch(String text, String query) {
            if (query == null || query.isEmpty()) {
                return true;
            }
            if (text == null || text.isEmpty()) {
                return false;
            }
            int ti = 0;
            int qi = 0;
            while (ti < text.length() && qi < query.length()) {
                if (text.charAt(ti) == query.charAt(qi)) {
                    qi++;
                }
                ti++;
            }
            return qi == query.length();
        }
    }

    /**
     * File set that automatically handles path normalization and deduplication.
     */
    static class FileSet {

        private final HashSet<String> paths = new HashSet<>();

        boolean tryAdd(String path) {
            return paths.add(normalizePath(path));
        }

        boolean contains(String path) {
            return paths.contains(normalizePath(path));
        }

        private String normalizePath(String path) {
            return path == null ? "" : path.replace('\\', '/');
        }
    }
}
