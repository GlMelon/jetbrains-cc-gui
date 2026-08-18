package com.github.claudecodegui.handler.node;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * Typed handler for {@link UpstreamAction#CHECK_NODE_ENVIRONMENT}.
 *
 * <p>迁自原 {@code handler/dependency} 包(整组删除后唯一保留的 Node 环境检测入口)。
 * 行为与原 {@code DependencyActionHandlers.handleCheckNodeEnvironment} 等价:缓存优先 →
 * 配置路径校验 → 自动检测,经 {@link DownstreamEvent#NODE_ENV_STATUS} 下发结果。
 * CLI 模式下 mcp-gateway/favorites/titles 仍需 Node,故保留。</p>
 */
public class NodeEnvironmentActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(NodeEnvironmentActionHandler.class);
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";
    private static final Gson GSON = new Gson();

    private final NodeDetector nodeDetector;
    private volatile CompletableFuture<Void> initFuture;
    private final Object initLock = new Object();

    public NodeEnvironmentActionHandler() {
        this.nodeDetector = NodeDetector.getInstance();
        ensureInitializedAsync();
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CHECK_NODE_ENVIRONMENT;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
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
                            LOG.info("[NodeEnv] Node.js found at configured path: " +
                                    configuredPath + " (" + detectedVersion + ")");
                        } else {
                            LOG.warn("[NodeEnv] Configured Node.js path is invalid: " + configuredPath);
                        }
                    }
                }

                // If the configured path is invalid, try auto-detection
                if (!available) {
                    available = this.nodeDetector.checkEnvironment();
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

                this.sendNodeEnvironmentStatus(ctx, result);
            } catch (Exception e) {
                LOG.error("[NodeEnv] Failed to check Node environment: " + e.getMessage(), e);
                JsonObject result = new JsonObject();
                result.addProperty("available", false);
                result.addProperty("error", e.getMessage());
                this.sendNodeEnvironmentStatus(ctx, result);
                this.sendShowError(ctx, "检查 Node.js 环境失败: " + e.getMessage());
            } finally {
                long elapsed = System.currentTimeMillis() - startTime;
                LOG.debug("[NodeEnv] handleCheckNodeEnvironment completed in " + elapsed +
                        "ms on thread " + Thread.currentThread().getName());
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[NodeEnv] Unexpected error in handleCheckNodeEnvironment: " + ex.getMessage(), ex);
            return null;
        });
    }

    private String getConfiguredNodePath() {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedPath = props.getValue(NODE_PATH_PROPERTY_KEY);
            if (savedPath != null && !savedPath.trim().isEmpty()) {
                return savedPath.trim();
            }
        } catch (Exception e) {
            LOG.warn("[NodeEnv] Failed to get configured Node.js path: " + e.getMessage());
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
                        LOG.info("[NodeEnv] Using configured Node.js path: " +
                                configuredNodePath + " (" + result.getNodeVersion() + ")");
                    } else {
                        LOG.warn("[NodeEnv] Configured Node.js path is invalid: " + configuredNodePath);
                    }
                } catch (Exception e) {
                    LOG.warn("[NodeEnv] Lazy initialization failed: " + e.getMessage(), e);
                }
            }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
                LOG.error("[NodeEnv] Unexpected error in ensureInitializedAsync: " + ex.getMessage(), ex);
                return null;
            });
        }
    }

    private void sendNodeEnvironmentStatus(HandlerContext ctx, JsonObject result) {
        ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent(DownstreamEvent.NODE_ENV_STATUS.value(), ctx.escapeJs(GSON.toJson(result)))
        );
    }

    private void sendShowError(HandlerContext ctx, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), ctx.escapeJs(message))
        );
    }
}
