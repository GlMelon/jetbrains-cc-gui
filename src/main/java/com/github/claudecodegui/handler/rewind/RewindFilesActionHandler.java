package com.github.claudecodegui.handler.rewind;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeRewindService;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * OCP typed handler:取代旧 {@code RewindHandler} 对 {@code rewind_files} 的字符串派发
 * (AGENTS.md §2 开闭原则)。
 *
 * <p>逐字搬移 {@code RewindHandler.handleRewindFiles} + {@code showError}:
 * {@code CompletableFuture.runAsync} 异步解析请求 → 校验 sessionId/userMessageId →
 * {@code ClaudeRewindService.rewindFiles} → EDT 经 {@code onRewindResult} 回传结果/错误,
 * 与旧实现逐字等价。
 *
 * <p>payload 为请求 JSON 字符串(sessionId/userMessageId),{@code payloadType=String},
 * handler 内部 {@code gson.fromJson} 解析(dispatcher 不预解析,与旧 {@code handle(content)} 等价)。
 */
public final class RewindFilesActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(RewindFilesActionHandler.class);
    private static final Gson gson = GsonHolder.GSON;

    @Override
    public UpstreamAction action() {
        return UpstreamAction.REWIND_FILES;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        final String content = payload;
        CompletableFuture.runAsync(() -> {
            try {
                // Parse request
                JsonObject request = gson.fromJson(content, JsonObject.class);
                String sessionId = request.has("sessionId") ? request.get("sessionId").getAsString() : null;
                String userMessageId = request.has("userMessageId") ? request.get("userMessageId").getAsString() : null;

                if (sessionId == null || sessionId.isEmpty()) {
                    LOG.warn("[RewindFilesActionHandler] Missing sessionId");
                    showError(ctx, "Session ID is required for rewind operation");
                    return;
                }

                if (userMessageId == null || userMessageId.isEmpty()) {
                    LOG.warn("[RewindFilesActionHandler] Missing userMessageId");
                    showError(ctx, "User message ID is required for rewind operation");
                    return;
                }

                LOG.info("[RewindFilesActionHandler] Rewinding files - Session: " + sessionId + ", Message: " + userMessageId);

                String cwd = null;
                if (ctx.getSession() != null) {
                    cwd = ctx.getSession().getCwd();
                }
                if ((cwd == null || cwd.isEmpty()) && ctx.getProject() != null) {
                    cwd = ctx.getProject().getBasePath();
                }

                // Call the SDK to rewind files
                new ClaudeRewindService().rewindFiles(sessionId, userMessageId, cwd)
                    .thenAccept(result -> {
                        boolean success = result.has("success") && result.get("success").getAsBoolean();
                        LOG.info("[RewindFilesActionHandler] Rewind result: success=" + success + ", result=" + result);

                        // Build the result JSON for the frontend callback
                        JsonObject callbackResult = new JsonObject();
                        callbackResult.addProperty("success", success);

                        if (success) {
                            LOG.info("[RewindFilesActionHandler] Rewind successful");
                            // Pass filesRestored if available (SDK may return it)
                            if (result.has("filesRestored")) {
                                callbackResult.addProperty("filesRestored", result.get("filesRestored").getAsInt());
                            }
                        } else {
                            String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
                            LOG.warn("[RewindFilesActionHandler] Rewind failed: " + error);
                            callbackResult.addProperty("message", "Failed to restore files: " + error);
                        }

                        // Call the frontend callback to update UI state
                        String callbackJson = gson.toJson(callbackResult);
                        LOG.info("[RewindFilesActionHandler] >>> Calling onRewindResult with: " + callbackJson);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            LOG.info("[RewindFilesActionHandler] >>> invokeLater executing, calling callJavaScript...");
                            ctx.callJavaScript("onRewindResult", ctx.escapeJs(callbackJson));
                            LOG.info("[RewindFilesActionHandler] >>> callJavaScript completed");
                        });
                    })
                    .exceptionally(ex -> {
                        LOG.error("[RewindFilesActionHandler] Rewind exception: " + ex.getMessage(), ex);

                        // Build error result for frontend callback
                        JsonObject errorResult = new JsonObject();
                        errorResult.addProperty("success", false);
                        errorResult.addProperty("message", "Rewind operation failed: " + ex.getMessage());

                        String errorJson = gson.toJson(errorResult);
                        LOG.info("[RewindFilesActionHandler] >>> Calling onRewindResult (exception) with: " + errorJson);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            LOG.info("[RewindFilesActionHandler] >>> invokeLater (exception) executing...");
                            ctx.callJavaScript("onRewindResult", ctx.escapeJs(errorJson));
                            LOG.info("[RewindFilesActionHandler] >>> callJavaScript (exception) completed");
                        });
                        return null;
                    });

            } catch (Exception e) {
                LOG.error("[RewindFilesActionHandler] Failed to parse rewind request: " + e.getMessage(), e);
                showError(ctx, "Invalid rewind request");
            }
        });
    }

    /**
     * Send error result to the frontend, which will update UI state and show error toast.
     */
    private void showError(HandlerContext ctx, String message) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("success", false);
        errorResult.addProperty("message", message);

        String errorJson = gson.toJson(errorResult);
        LOG.info("[RewindFilesActionHandler] >>> showError calling onRewindResult with: " + errorJson);
        ApplicationManager.getApplication().invokeLater(() -> {
            LOG.info("[RewindFilesActionHandler] >>> showError invokeLater executing...");
            ctx.callJavaScript("onRewindResult", ctx.escapeJs(errorJson));
            LOG.info("[RewindFilesActionHandler] >>> showError callJavaScript completed");
        });
    }
}
