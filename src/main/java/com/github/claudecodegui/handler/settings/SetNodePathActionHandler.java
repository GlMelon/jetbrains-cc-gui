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
 * OCP typed handler:取代旧 SettingsHandler 对 set_node_path 的字符串派发
 * + NodePathHandler.handleSetNodePath 委托(AGENTS.md §2 开闭原则)。
 *
 * <p>CEF 线程同步解析 JSON(纯解析无 I/O)→ 后台线程校验/写盘/检测(避免阻塞 CEF IO 线程)
 * → 经 {@code node.path} 回传 + 成功时 {@code toast.switch_success} + {@code node.check_env}
 * 触发环境重检,失败时 {@code toast.error},与旧实现逐字等价。
 */
public final class SetNodePathActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(SetNodePathActionHandler.class);
    private static final Gson GSON = GsonHolder.GSON;
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";

    @Override
    public UpstreamAction action() {
        return UpstreamAction.SET_NODE_PATH;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        LOG.debug("[SetNodePathActionHandler] ========== handle START ==========");
        LOG.debug("[SetNodePathActionHandler] Received content: " + payload);

        // Parse path on the CEF IO thread — pure JSON parsing, no I/O, safe to do synchronously
        String parsedPath = null;
        try {
            JsonObject json = GSON.fromJson(payload, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[SetNodePathActionHandler] Failed to parse set_node_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "保存 Node.js 路径失败: " + e.getMessage())
            );
            return;
        }
        final String pathArg = (parsedPath != null) ? parsedPath.trim() : null;

        // All I/O and process-spawning runs in a background thread
        CompletableFuture.runAsync(() -> {
            try {
                PropertiesComponent props = PropertiesComponent.getInstance();
                String finalPath = "";
                String versionToSend = null;
                boolean verifySuccess = false;
                String failureMsg = null;

                if (pathArg == null || pathArg.isEmpty()) {
                    props.unsetValue(NODE_PATH_PROPERTY_KEY);
                    ctx.getNodeService().setNodeExecutable(null);
                    LOG.info("[SetNodePathActionHandler] Cleared manual Node.js path from settings");

                    NodeDetectionResult detected = ctx.getNodeService().detectNodeWithDetails();
                    if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                        finalPath = detected.getNodePath();
                        versionToSend = detected.getNodeVersion();
                        props.setValue(NODE_PATH_PROPERTY_KEY, finalPath);
                        // Use verifyAndCacheNodePath to ensure version info is cached
                        ctx.getNodeService().verifyAndCacheNodePath(finalPath);
                        verifySuccess = true;
                    } else {
                        failureMsg = "已清空自定义路径，但无法自动检测到 Node.js，请手动配置路径";
                    }
                } else {
                    // Verify before saving to avoid caching invalid path
                    NodeDetectionResult result = ctx.getNodeService().verifyAndCacheNodePath(pathArg);
                    if (result != null && result.isFound()) {
                        // Only save if verification succeeds
                        props.setValue(NODE_PATH_PROPERTY_KEY, pathArg);
                        finalPath = pathArg;
                        versionToSend = result.getNodeVersion();
                        verifySuccess = true;
                        LOG.info("[SetNodePathActionHandler] Saved manual Node.js path: " + pathArg);
                    } else {
                        // Verification failed, don't save invalid path
                        finalPath = "";
                        failureMsg = result != null ? result.getErrorMessage() : "无法验证指定的 Node.js 路径";
                        LOG.warn("[SetNodePathActionHandler] Node.js path verification failed: " + pathArg + " - " + failureMsg);
                    }
                }

                final boolean successFlag = verifySuccess;
                final String failureMsgFinal = failureMsg;
                final String finalPathToSend = finalPath;
                final String finalVersionToSend = versionToSend;

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", finalPathToSend);
                    response.addProperty("version", finalVersionToSend);
                    response.addProperty("minVersion", NodeDetector.MIN_NODE_MAJOR_VERSION);
                    ctx.dispatchEvent(DownstreamEvent.NODE_PATH.value(), GSON.toJson(response));

                    if (successFlag) {
                        // Trigger environment re-check, no IDE restart needed
                        ctx.dispatchEvent(DownstreamEvent.TOAST_SWITCH_SUCCESS.value(), "Node.js 路径已保存并生效,无需重启IDE");

                        // Notify DependencySection to re-check Node.js environment
                        ctx.dispatchEvent(DownstreamEvent.NODE_CHECK_ENV.value(), "");
                    } else {
                        String msg = failureMsgFinal != null ? failureMsgFinal : "无法验证指定的 Node.js 路径";
                        ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "保存的 Node.js 路径无效: " + msg);
                    }
                });
            } catch (Exception e) {
                LOG.error("[SetNodePathActionHandler] Failed to set Node.js path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    ctx.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "保存 Node.js 路径失败: " + e.getMessage())
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[SetNodePathActionHandler] Unexpected error in handle: " + ex.getMessage(), ex);
            return null;
        });

        LOG.debug("[SetNodePathActionHandler] ========== handle END (async dispatched) ==========");
    }
}
