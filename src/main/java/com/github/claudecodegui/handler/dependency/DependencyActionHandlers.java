package com.github.claudecodegui.handler.dependency;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.dependency.DependencyManager;
import com.github.claudecodegui.dependency.InstallResult;
import com.github.claudecodegui.dependency.SdkDefinition;
import com.github.claudecodegui.dependency.UpdateInfo;
import com.github.claudecodegui.dependency.VersionAction;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Container for SDK dependency action handlers (B2 迁移).
 * Holds shared state and all business logic for dependency install/uninstall/update/version/env operations.
 */
public class DependencyActionHandlers {

    private static final Logger LOG = Logger.getInstance(DependencyActionHandlers.class);
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";

    private final HandlerContext context;
    private final DependencyManager dependencyManager;
    private final Gson gson;
    private final NodeDetector nodeDetector;
    private volatile CompletableFuture<Void> initFuture;
    private final Object initLock;

    public DependencyActionHandlers(HandlerContext context) {
        this.context = context;
        this.nodeDetector = NodeDetector.getInstance();
        this.dependencyManager = new DependencyManager(this.nodeDetector);
        this.gson = GsonHolder.GSON;
        this.initFuture = null;
        this.initLock = new Object();
        // 首次使用即触发惰性 Node.js 缓存预热(幂等)
        ensureInitializedAsync();
    }

    // ── dispatch helpers ──

    private void dispatchEvent(String event, String data) {
        context.dispatchEvent(event, data);
    }

    private String escapeJs(String s) {
        return context.escapeJs(s);
    }

    // ── node path + lazy init ──

    private String getConfiguredNodePath() {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedPath = props.getValue(NODE_PATH_PROPERTY_KEY);
            if (savedPath != null && !savedPath.trim().isEmpty()) {
                return savedPath.trim();
            }
        } catch (Exception e) {
            LOG.warn("[DependencyHandler] Failed to get configured Node.js path: " + e.getMessage());
        }
        return null;
    }

    /**
     * Performs deferred Node.js cache warm-up for configured path.
     * After the first call, subsequent invocations return early (idempotent).
     */
    private void ensureInitializedAsync() {
        if (this.initFuture != null) {
            return;
        }

        synchronized (this.initLock) {
            if (this.initFuture != null) {
                return;
            }
            this.initFuture = CompletableFuture.runAsync(() -> {
                try {
                    String configuredNodePath = this.getConfiguredNodePath();
                    if (configuredNodePath == null || configuredNodePath.isEmpty()) {
                        return;
                    }

                    NodeDetectionResult result = this.nodeDetector.verifyAndCacheNodePath(configuredNodePath);
                    if (result.isFound()) {
                        LOG.info("[DependencyHandler] Using configured Node.js path: " +
                                 configuredNodePath + " (" + result.getNodeVersion() + ")");
                    } else {
                        LOG.warn("[DependencyHandler] Configured Node.js path is invalid: " + configuredNodePath);
                    }
                } catch (Exception e) {
                    LOG.warn("[DependencyHandler] Lazy initialization failed: " + e.getMessage(), e);
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[DependencyHandler] Unexpected error in ensureInitializedAsync: " + ex.getMessage(), ex);
                return null;
            });
        }
    }

    // ── business logic ──

    public void handleGetStatus() {
        long startTime = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject status = this.dependencyManager.getAllSdkStatus();
                String statusJson = this.gson.toJson(status);

                ApplicationManager.getApplication().invokeLater(() ->
                    dispatchEvent(DownstreamEvent.DEPENDENCY_STATUS.value(), escapeJs(statusJson))
                );
            } catch (Exception e) {
                LOG.error("[DependencyHandler] Failed to get dependency status: " + e.getMessage(), e);
                this.sendErrorResult(DownstreamEvent.DEPENDENCY_STATUS.value(), e.getMessage());
                this.sendShowError("获取依赖状态失败: " + e.getMessage());
            } finally {
                long elapsed = System.currentTimeMillis() - startTime;
                LOG.debug("[DependencyHandler] handleGetStatus completed in " + elapsed +
                          "ms on thread " + Thread.currentThread().getName());
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[DependencyHandler] Unexpected error in handleGetStatus: " + ex.getMessage(), ex);
            return null;
        });
    }

    public void handleInstall(String content) {
        try {
            JsonObject json = this.gson.fromJson(content, JsonObject.class);
            String sdkId = json.get("id").getAsString();
            String requestedVersion = json.has("version") && !json.get("version").isJsonNull()
                    ? json.get("version").getAsString()
                    : null;

            SdkDefinition sdk = SdkDefinition.fromId(sdkId);
            if (sdk == null) {
                this.sendInstallResult(InstallResult.failure(sdkId, "Unknown SDK: " + sdkId, ""));
                return;
            }

            // Move the entire install flow (including Node env check) to background thread
            // to avoid blocking the CEF IO thread if the cache is cold.
            CompletableFuture.runAsync(() -> {
                try {
                    // Check Node.js environment (may involve process I/O on cache miss)
                    if (!this.dependencyManager.checkNodeEnvironment()) {
                        JsonObject errorResult = new JsonObject();
                        errorResult.addProperty("success", false);
                        errorResult.addProperty("sdkId", sdkId);
                        errorResult.addProperty("error", "node_not_configured");
                        errorResult.addProperty(
                            "message",
                            "Node.js not configured. Please set Node.js path in Settings > Basic."
                        );

                        ApplicationManager.getApplication().invokeLater(() ->
                            dispatchEvent(
                                DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(),
                                escapeJs(this.gson.toJson(errorResult))
                            )
                        );
                        return;
                    }

                    InstallResult result = this.dependencyManager.installSdkSync(sdkId, requestedVersion, (logLine) -> {
                        this.sendInstallProgress(sdkId, logLine);
                    });

                    this.sendInstallResult(result);

                    // Refresh status after installation completes
                    if (result.isSuccess()) {
                        this.handleGetStatus();
                    }
                } catch (Exception e) {
                    LOG.error("[DependencyHandler] Failed during dependency installation: " + e.getMessage(), e);
                    this.sendErrorResult(DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(), e.getMessage());
                    this.sendShowError("依赖安装失败: " + e.getMessage());
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[DependencyHandler] Unexpected error in handleInstall: " + ex.getMessage(), ex);
                return null;
            });

        } catch (Exception e) {
            LOG.error("[DependencyHandler] Failed to install dependency: " + e.getMessage(), e);
            this.sendErrorResult(DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(), e.getMessage());
            this.sendShowError("依赖安装失败: " + e.getMessage());
        }
    }

    public void handleUninstall(String content) {
        try {
            JsonObject json = this.gson.fromJson(content, JsonObject.class);
            String sdkId = json.get("id").getAsString();

            CompletableFuture.runAsync(() -> {
                try {
                    boolean success = this.dependencyManager.uninstallSdk(sdkId);

                    JsonObject result = new JsonObject();
                    result.addProperty("success", success);
                    result.addProperty("sdkId", sdkId);
                    if (!success) {
                        result.addProperty("error", "Failed to uninstall SDK");
                    }

                    ApplicationManager.getApplication().invokeLater(() ->
                        dispatchEvent(DownstreamEvent.DEPENDENCY_UNINSTALL_RESULT.value(), escapeJs(this.gson.toJson(result)))
                    );

                    // Refresh status after uninstall completes
                    this.handleGetStatus();
                } catch (Exception e) {
                    LOG.error("[DependencyHandler] Failed during dependency uninstall: " + e.getMessage(), e);
                    this.sendErrorResult(DownstreamEvent.DEPENDENCY_UNINSTALL_RESULT.value(), e.getMessage());
                    this.sendShowError("依赖卸载失败: " + e.getMessage());
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[DependencyHandler] Unexpected error in handleUninstall: " + ex.getMessage(), ex);
                return null;
            });

        } catch (Exception e) {
            LOG.error("[DependencyHandler] Failed to uninstall dependency: " + e.getMessage(), e);
            this.sendErrorResult(DownstreamEvent.DEPENDENCY_UNINSTALL_RESULT.value(), e.getMessage());
            this.sendShowError("依赖卸载失败: " + e.getMessage());
        }
    }

    public void handleUpdate(String content) {
        try {
            JsonObject json = this.gson.fromJson(content, JsonObject.class);
            String sdkId = json.get("id").getAsString();
            String requestedVersion = json.has("version") && !json.get("version").isJsonNull()
                    ? json.get("version").getAsString()
                    : null;

            SdkDefinition sdk = SdkDefinition.fromId(sdkId);
            if (sdk == null) {
                this.sendInstallResult(InstallResult.failure(sdkId, "Unknown SDK: " + sdkId, ""));
                return;
            }

            CompletableFuture.runAsync(() -> {
                try {
                    // Check Node.js environment
                    if (!this.dependencyManager.checkNodeEnvironment()) {
                        JsonObject errorResult = new JsonObject();
                        errorResult.addProperty("success", false);
                        errorResult.addProperty("sdkId", sdkId);
                        errorResult.addProperty("error", "node_not_configured");
                        errorResult.addProperty(
                            "message",
                            "Node.js not configured. Please set Node.js path in Settings > Basic."
                        );

                        ApplicationManager.getApplication().invokeLater(() ->
                            dispatchEvent(
                                DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(),
                                escapeJs(this.gson.toJson(errorResult))
                            )
                        );
                        return;
                    }

                    this.sendInstallProgress(sdkId, "Updating SDK with npm install...");
                    InstallResult result = this.dependencyManager.installSdkSync(sdkId, requestedVersion, (logLine) -> {
                        this.sendInstallProgress(sdkId, logLine);
                    });

                    this.sendInstallResult(result);

                    // Refresh status after update completes
                    if (result.isSuccess()) {
                        this.handleGetStatus();
                    }
                } catch (Exception e) {
                    LOG.error("[DependencyHandler] Failed during dependency update: " + e.getMessage(), e);
                    this.sendErrorResult(DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(), e.getMessage());
                    this.sendShowError("依赖更新失败: " + e.getMessage());
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[DependencyHandler] Unexpected error in handleUpdate: " + ex.getMessage(), ex);
                return null;
            });

        } catch (Exception e) {
            LOG.error("[DependencyHandler] Failed to update dependency: " + e.getMessage(), e);
            this.sendErrorResult(DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(), e.getMessage());
            this.sendShowError("依赖更新失败: " + e.getMessage());
        }
    }

    public void handleCheckUpdates(String content) {
        try {
            String sdkId = null;
            if (content != null && !content.isEmpty()) {
                JsonObject json = this.gson.fromJson(content, JsonObject.class);
                if (json.has("id")) {
                    sdkId = json.get("id").getAsString();
                }
            }

            final String targetSdkId = sdkId;

            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject updates = new JsonObject();

                    if (targetSdkId != null) {
                        // Check specified SDK
                        UpdateInfo info = this.dependencyManager.checkForUpdates(targetSdkId);
                        updates.add(targetSdkId, this.toJson(info));
                    } else {
                        // Check all installed SDKs
                        for (SdkDefinition sdk : SdkDefinition.values()) {
                            if (this.dependencyManager.isInstalled(sdk.getId())) {
                                UpdateInfo info = this.dependencyManager.checkForUpdates(sdk.getId());
                                updates.add(sdk.getId(), this.toJson(info));
                            }
                        }
                    }

                    ApplicationManager.getApplication().invokeLater(
                        () -> dispatchEvent(
                            DownstreamEvent.DEPENDENCY_UPDATE_AVAILABLE.value(),
                            escapeJs(this.gson.toJson(updates))
                        )
                    );
                } catch (Exception e) {
                    LOG.error("[DependencyHandler] Failed during update check: " + e.getMessage(), e);
                    this.sendErrorResult(DownstreamEvent.DEPENDENCY_UPDATE_AVAILABLE.value(), e.getMessage());
                    this.sendShowError("检查依赖更新失败: " + e.getMessage());
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[DependencyHandler] Unexpected error in handleCheckUpdates: " + ex.getMessage(), ex);
                return null;
            });

        } catch (Exception e) {
            LOG.error("[DependencyHandler] Failed to check updates: " + e.getMessage(), e);
            this.sendErrorResult(DownstreamEvent.DEPENDENCY_UPDATE_AVAILABLE.value(), e.getMessage());
            this.sendShowError("检查依赖更新失败: " + e.getMessage());
        }
    }

    public void handleGetDependencyVersions(String content) {
        try {
            String sdkId = null;
            if (content != null && !content.isEmpty()) {
                JsonObject json = this.gson.fromJson(content, JsonObject.class);
                if (json.has("id")) {
                    sdkId = json.get("id").getAsString();
                }
            }

            final String targetSdkId = sdkId;
            CompletableFuture.runAsync(() -> {
                try {
                    JsonObject payload = new JsonObject();
                    if (targetSdkId != null) {
                        payload.add(targetSdkId, this.buildVersionPayload(targetSdkId));
                    } else {
                        for (SdkDefinition sdk : SdkDefinition.values()) {
                            payload.add(sdk.getId(), this.buildVersionPayload(sdk.getId()));
                        }
                    }

                    ApplicationManager.getApplication().invokeLater(
                        () -> dispatchEvent(
                            DownstreamEvent.DEPENDENCY_VERSIONS_LOADED.value(),
                            escapeJs(this.gson.toJson(payload))
                        )
                    );
                } catch (Exception e) {
                    LOG.error("[DependencyHandler] Failed to get dependency versions: " + e.getMessage(), e);
                    this.sendErrorResult(DownstreamEvent.DEPENDENCY_VERSIONS_LOADED.value(), e.getMessage());
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[DependencyHandler] Unexpected error in handleGetDependencyVersions: " + ex.getMessage(), ex);
                return null;
            });
        } catch (Exception e) {
            LOG.error("[DependencyHandler] Failed to parse dependency versions request: " + e.getMessage(), e);
            this.sendErrorResult(DownstreamEvent.DEPENDENCY_VERSIONS_LOADED.value(), e.getMessage());
        }
    }

    public void handleCheckNodeEnvironment() {
        long startTime = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                boolean available = false;
                String detectedPath = null;
                String detectedVersion = null;

                // Fast-path: use cached shared detection result with no process/file I/O.
                String cachedPath = this.nodeDetector.getCachedNodePath();
                String cachedVersion = this.nodeDetector.getCachedNodeVersion();
                if (cachedPath != null && cachedVersion != null) {
                    available = true;
                    detectedPath = cachedPath;
                    detectedVersion = cachedVersion;
                }

                // If cache miss, first check if there is a configured Node.js path.
                if (!available) {
                    String configuredPath = this.getConfiguredNodePath();
                    if (configuredPath != null && !configuredPath.isEmpty()) {
                        NodeDetectionResult verifyResult =
                            this.nodeDetector.verifyAndCacheNodePath(configuredPath);
                        if (verifyResult.isFound()) {
                            available = true;
                            detectedPath = verifyResult.getNodePath();
                            detectedVersion = verifyResult.getNodeVersion();
                            LOG.info("[DependencyHandler] Node.js found at configured path: " +
                                     configuredPath + " (" + detectedVersion + ")");
                        } else {
                            LOG.warn("[DependencyHandler] Configured Node.js path is invalid: " + configuredPath);
                        }
                    }
                }

                // If the configured path is invalid, try auto-detection
                if (!available) {
                    available = this.dependencyManager.checkNodeEnvironment();
                    if (available) {
                        detectedPath = this.nodeDetector.getCachedNodePath();
                        detectedVersion = this.nodeDetector.getCachedNodeVersion();
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("available", available);
                if (detectedPath != null) {
                    result.addProperty("path", detectedPath);
                }
                if (detectedVersion != null) {
                    result.addProperty("version", detectedVersion);
                }

                this.sendNodeEnvironmentStatus(result);
            } catch (Exception e) {
                LOG.error("[DependencyHandler] Failed to check Node environment: " + e.getMessage(), e);
                JsonObject result = new JsonObject();
                result.addProperty("available", false);
                result.addProperty("error", e.getMessage());
                this.sendNodeEnvironmentStatus(result);
                this.sendShowError("检查 Node.js 环境失败: " + e.getMessage());
            } finally {
                long elapsed = System.currentTimeMillis() - startTime;
                LOG.debug("[DependencyHandler] handleCheckNodeEnvironment completed in " + elapsed +
                          "ms on thread " + Thread.currentThread().getName());
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[DependencyHandler] Unexpected error in handleCheckNodeEnvironment: " + ex.getMessage(), ex);
            return null;
        });
    }

    // ==================== Helper Methods ====================

    private void sendNodeEnvironmentStatus(JsonObject result) {
        ApplicationManager.getApplication().invokeLater(() ->
            dispatchEvent(DownstreamEvent.NODE_ENV_STATUS.value(), escapeJs(this.gson.toJson(result)))
        );
    }

    private void sendInstallProgress(String sdkId, String logLine) {
        JsonObject progress = new JsonObject();
        progress.addProperty("sdkId", sdkId);
        progress.addProperty("log", logLine);

        ApplicationManager.getApplication().invokeLater(
            () -> dispatchEvent(
                DownstreamEvent.DEPENDENCY_INSTALL_PROGRESS.value(),
                escapeJs(this.gson.toJson(progress))
            )
        );
    }

    private void sendInstallResult(InstallResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("success", result.isSuccess());
        json.addProperty("sdkId", result.getSdkId());

        if (result.isSuccess()) {
            json.addProperty("installedVersion", result.getInstalledVersion());
        } else {
            json.addProperty("error", result.getErrorMessage());
        }
        json.addProperty("logs", result.getLogs());

        ApplicationManager.getApplication().invokeLater(() ->
            dispatchEvent(DownstreamEvent.DEPENDENCY_INSTALL_RESULT.value(), escapeJs(this.gson.toJson(json)))
        );
    }

    private JsonObject toJson(UpdateInfo info) {
        JsonObject json = new JsonObject();
        json.addProperty("sdkId", info.getSdkId());
        json.addProperty("sdkName", info.getSdkName());
        json.addProperty("hasUpdate", info.hasUpdate());
        json.addProperty("currentVersion", info.getCurrentVersion());
        json.addProperty("latestVersion", info.getLatestVersion());

        if (info.getErrorMessage() != null) {
            json.addProperty("error", info.getErrorMessage());
        }

        return json;
    }

    private JsonObject buildVersionPayload(String sdkId) {
        JsonObject json = new JsonObject();
        List<String> remoteVersions = this.dependencyManager.getAvailableVersions(sdkId);
        List<String> fallbackVersions = this.dependencyManager.getFallbackVersions(sdkId);
        boolean usingRemote = !remoteVersions.isEmpty();
        List<String> effectiveVersions = usingRemote ? remoteVersions : fallbackVersions;

        json.addProperty("sdkId", sdkId);
        json.add("versions", this.gson.toJsonTree(effectiveVersions));
        json.add("fallbackVersions", this.gson.toJsonTree(fallbackVersions));
        json.addProperty("source", usingRemote ? "remote" : "fallback");

        String latestVersion = this.dependencyManager.getLatestVersion(sdkId);
        if (latestVersion != null && !latestVersion.isEmpty()) {
            json.addProperty("latestVersion", latestVersion);
        }

        // A6:已安装时预计算每个可选版本相对已安装版本的动作(update/rollback/current),
        // 前端按用户选择的目标版本查表渲染按钮态,消除前端 getVersionAction 决策双写。
        // 全集对齐前端 buildVersionOptions(effectiveVersions ∪ fallbackVersions ∪ installedVersion)。
        if (this.dependencyManager.isInstalled(sdkId)) {
            String installedVersion = this.dependencyManager.getInstalledVersion(sdkId);
            LinkedHashSet<String> versionSet = new LinkedHashSet<>();
            versionSet.addAll(effectiveVersions);
            versionSet.addAll(fallbackVersions);
            if (installedVersion != null && !installedVersion.isEmpty()) {
                versionSet.add(installedVersion);
            }
            JsonObject versionActions = new JsonObject();
            for (String version : versionSet) {
                VersionAction action = DependencyManager.resolveVersionAction(true, installedVersion, version);
                versionActions.addProperty(version, action.value());
            }
            json.add("versionActions", versionActions);
        }

        if (!usingRemote) {
            json.addProperty("error", "remote_versions_unavailable");
        }

        return json;
    }

    private void sendShowError(String message) {
        ApplicationManager.getApplication().invokeLater(() ->
            dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), escapeJs(message))
        );
    }

    private void sendErrorResult(String type, String errorMessage) {
        JsonObject error = new JsonObject();
        error.addProperty("success", false);
        error.addProperty("error", errorMessage);

        ApplicationManager.getApplication().invokeLater(() ->
            dispatchEvent(type, escapeJs(this.gson.toJson(error)))
        );
    }
}
