package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 SettingsHandler 对 get_node_path 的字符串派发
 * + NodePathHandler.handleGetNodePath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>后台线程检测/校验 Node.js 路径(避免阻塞 CEF IO 线程)→ 经 {@code node.path}
 * 事件回传 path/version/minVersion,与旧实现逐字等价。失败经 {@code toast.error} 回传。
 */
public final class GetNodePathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetNodePathActionHandler.class);
    private static final Gson GSON = GsonHolder.GSON;
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_NODE_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        CompletableFuture.runAsync(() -> {
            try {
                PropertiesComponent props = PropertiesComponent.getInstance();
                String saved = props.getValue(NODE_PATH_PROPERTY_KEY);
                String pathToSend = "";
                String versionToSend = null;

                if (saved != null && !saved.trim().isEmpty()) {
                    String trimmedPath = saved.trim();
                    NodeDetectionResult result = ctx.getNodeService().verifyAndCacheNodePath(trimmedPath);
                    if (result != null && result.isFound()) {
                        pathToSend = trimmedPath;
                        versionToSend = result.getNodeVersion();
                    } else {
                        // Saved path is invalid, clear it and trigger re-detection
                        LOG.warn("[GetNodePathActionHandler] Saved Node.js path is invalid: " + trimmedPath
                            + ", clearing and triggering re-detection");
                        props.unsetValue(NODE_PATH_PROPERTY_KEY);
                        ctx.getNodeService().setNodeExecutable(null);

                        NodeDetectionResult detected = ctx.getNodeService().detectNodeWithDetails();
                        if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                            pathToSend = detected.getNodePath();
                            versionToSend = detected.getNodeVersion();
                            props.setValue(NODE_PATH_PROPERTY_KEY, pathToSend);
                            ctx.getNodeService().verifyAndCacheNodePath(pathToSend);
                        }
                    }
                } else {
                    NodeDetectionResult detected = ctx.getNodeService().detectNodeWithDetails();
                    if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                        pathToSend = detected.getNodePath();
                        versionToSend = detected.getNodeVersion();
                        props.setValue(NODE_PATH_PROPERTY_KEY, pathToSend);
                        // Use verifyAndCacheNodePath instead of setNodeExecutable to ensure version info is cached
                        ctx.getNodeService().verifyAndCacheNodePath(pathToSend);
                    }
                }

                final String finalPath = pathToSend;
                final String finalVersion = versionToSend;

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", finalPath);
                    response.addProperty("version", finalVersion);
                    response.addProperty("minVersion", NodeDetector.MIN_NODE_MAJOR_VERSION);
                    ctx.dispatchEvent(DownstreamEvent.NODE_PATH.value(), GSON.toJson(response));
                });
            } catch (Exception e) {
                LOG.error("[GetNodePathActionHandler] Failed to get Node.js path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "获取 Node.js 路径失败: " + e.getMessage())
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[GetNodePathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });
    }
}
