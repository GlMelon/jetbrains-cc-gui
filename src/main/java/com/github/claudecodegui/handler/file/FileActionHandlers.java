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
            try {
                // 1. Parse request
                FileListRequest request = parseRequest(content);

                // 2. Get base path
                String basePath = getEffectiveBasePath();

                // 3. Initialize file set (deduplication)
                FileSet fileSet = new FileSet();

                // 4. Collect files
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

                // 5. Sort
                sortFiles(files);

                // 6. Return result
                sendResult(files);
            } catch (ProcessCanceledException e) {
                throw e;
            } catch (Exception e) {
                LOG.error("[FileHandler] Failed to list files: " + e.getMessage(), e);
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
    private void sendResult(List<JsonObject> files) {
        JsonObject result = new JsonObject();
        result.add("files", GSON.toJsonTree(files));
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
            return new FileListRequest("", "");
        }

        try {
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            String query = json.has("query") ? json.get("query").getAsString() : "";
            String currentPath = json.has("currentPath") ? json.get("currentPath").getAsString() : "";
            return new FileListRequest(query, currentPath);
        } catch (Exception e) {
            // If not JSON, treat as plain text query
            return new FileListRequest(content.trim(), "");
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
     */
    private void sortFiles(List<JsonObject> files) {
        if (files.isEmpty()) { return; }

        // 1. Wrap as SortItem, pre-read/compute sorting fields
        List<FileSortItem> items = new ArrayList<>(files.size());
        for (JsonObject json : files) {
            items.add(new FileSortItem(json));
        }

        // 2. Sort
        items.sort((a, b) -> {
            // Priority 1 & 2: Keep original order (stability)
            if (a.priority < 3 && b.priority < 3) {
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
     * Get relative path.
     */
    static String getRelativePath(File file, String basePath) {
        String relativePath = file.getAbsolutePath().substring(basePath.length());
        if (relativePath.startsWith(File.separator)) {
            relativePath = relativePath.substring(1);
        }
        return relativePath.replace("\\", "/");
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

        // Calculate relative path
        String relativePath = path;
        if (path.startsWith(basePath)) {
            relativePath = path.substring(basePath.length());
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
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

        FileListRequest(String query, String currentPath) {
            this.query = query != null ? query : "";
            this.queryLower = this.query.toLowerCase();
            this.currentPath = currentPath != null ? currentPath : "";
            this.hasQuery = !this.query.isEmpty();
        }

        boolean matches(String name, String relativePath) {
            if (!hasQuery) { return true; }
            String lowerName = name.toLowerCase();
            String lowerPath = relativePath.toLowerCase();
            return (lowerName.contains(queryLower) || lowerPath.contains(queryLower));
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
