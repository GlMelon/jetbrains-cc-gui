package com.github.claudecodegui.handler.context;

import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.runtime.EffectiveRuntimeResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * OCP typed handler:取代旧 {@code ContextHandler} 对 {@code get_context_usage} 的字符串派发
 * (AGENTS.md §2 开闭原则)。
 *
 * <p>逐字搬移 {@code ContextHandler.handleGetContextUsage} + {@code callContextUsageError}:
 * 解析请求字段 → 缺省回填 session 的 sessionId/cwd → {@code ClaudeSDKBridge.getContextUsage}
 * 异步查询 → EDT 经 {@code showContextUsageDialog} / {@code onContextUsageError} 回传结果/错误,
 * 与旧实现逐字等价。
 *
 * <p>payload 为请求 JSON 字符串(sessionId/cwd/model/requestId),{@code payloadType=String},
 * handler 内部 {@code gson.fromJson} 解析(dispatcher 不预解析,与旧 {@code handle(content)} 等价)。
 *
 * <p>{@code parseContextUsageRequest} 作为 package-private 静态方法保留,供契约测试覆盖解析逻辑。
 */
public final class GetContextUsageActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(GetContextUsageActionHandler.class);
    private static final Gson gson = GsonHolder.GSON;

    @Override
    public UpstreamAction action() {
        return UpstreamAction.GET_CONTEXT_USAGE;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        HandlerContext ctx = context.handlerContext();
        String[] parsed = parseContextUsageRequest(gson, payload);
        String sessionId = parsed[0];
        String cwd = parsed[1];
        String model = parsed[2];
        String requestId = parsed[3];
        // D5:longContextEnabled 意图由后端权威解析并据此追加 [1m] 后缀(取代前端 apply1MContextSuffix)。
        boolean longContextEnabled = parseLongContextEnabled(gson, payload);

        if (isContextUsageUnavailableInCliMode(ctx)) {
            callContextUsageError(
                    ctx,
                    "Context usage is unavailable in Claude CLI mode. Switch invocation mode to SDK to use /context.",
                    requestId
            );
            return;
        }

        // Fall back to session state if not provided
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = ctx.getSession().getSessionId();
        }
        if (cwd == null || cwd.isEmpty()) {
            cwd = ctx.getSession().getCwd();
        }

        final String finalSessionId = sessionId;
        final String finalCwd = cwd;
        // D5:按 longContextEnabled 意图构造最终 model([1m] 后缀下沉到后端)。
        final String finalModel = ModelRegistryConfig.apply1MSuffix(model, longContextEnabled);
        final String finalRequestId = requestId;

        try {
            ctx.getClaudeSDKBridge()
                    .getContextUsage(finalSessionId, finalCwd, finalModel)
                    .thenAccept(result -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                // If the result indicates failure, route through the error callback
                                // to ensure the dialog is closed properly on the frontend.
                                if (result.has("success") && !result.get("success").getAsBoolean()) {
                                    String errorMsg = "Failed to get context usage";
                                    if (result.has("error") && !result.get("error").isJsonNull()) {
                                        String sdkError = result.get("error").getAsString();
                                        if (!sdkError.isEmpty()) {
                                            errorMsg = sdkError;
                                        }
                                    }
                                    LOG.warn("[GetContextUsageActionHandler] Context usage query failed: " + errorMsg);
                                    callContextUsageError(ctx, errorMsg, finalRequestId);
                                    return;
                                }
                                JsonObject response = result.deepCopy();
                                if (finalRequestId != null && !finalRequestId.isEmpty()) {
                                    response.addProperty("requestId", finalRequestId);
                                }
                                String json = gson.toJson(response);
                                ctx.callJavaScript("showContextUsageDialog", ctx.escapeJs(json));
                            } catch (Exception e) {
                                LOG.error("[GetContextUsageActionHandler] Failed to send result to frontend", e);
                                callContextUsageError(ctx, "Failed to process context usage data", finalRequestId);
                            }
                        });
                    })
                    .exceptionally(ex -> {
                        LOG.error("[GetContextUsageActionHandler] getContextUsage failed", ex);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            callContextUsageError(ctx, "Failed to get context usage: " + ex.getMessage(), finalRequestId);
                        });
                        return null;
                    });
        } catch (Exception e) {
            LOG.error("[GetContextUsageActionHandler] Unexpected error", e);
            ApplicationManager.getApplication().invokeLater(() -> {
                callContextUsageError(ctx, "Unexpected error: " + e.getMessage(), finalRequestId);
            });
        }
    }

    /**
     * Send an error result to the frontend via the {@code onContextUsageError} callback.
     * When a requestId is present it is forwarded so the frontend can correlate the failure.
     */
    private static void callContextUsageError(HandlerContext ctx, String message, String requestId) {
        if (requestId != null && !requestId.isEmpty()) {
            ctx.callJavaScript("onContextUsageError", ctx.escapeJs(message), ctx.escapeJs(requestId));
            return;
        }
        ctx.callJavaScript("onContextUsageError", ctx.escapeJs(message));
    }

    /**
     * Parse context usage request JSON into its component fields.
     * Returns a 4-element String array: [sessionId, cwd, model, requestId].
     * Any field not present in the JSON will be null.
     */
    static String[] parseContextUsageRequest(Gson gson, String content) {
        String sessionId = null;
        String cwd = null;
        String model = null;
        String requestId = null;

        try {
            if (content != null && !content.isEmpty()) {
                JsonObject request = gson.fromJson(content, JsonObject.class);
                if (request != null) {
                    sessionId = request.has("sessionId") && !request.get("sessionId").isJsonNull()
                            ? request.get("sessionId").getAsString() : null;
                    cwd = request.has("cwd") && !request.get("cwd").isJsonNull()
                            ? request.get("cwd").getAsString() : null;
                    model = request.has("model") && !request.get("model").isJsonNull()
                            ? request.get("model").getAsString() : null;
                    requestId = request.has("requestId") && !request.get("requestId").isJsonNull()
                            ? request.get("requestId").getAsString() : null;
                }
            }
        } catch (Exception e) {
            // Return partial results on parse failure
        }

        return new String[]{sessionId, cwd, model, requestId};
    }

    /**
     * 解析 get_context_usage 请求中的 longContextEnabled 意图布尔(D5:1M 构造下沉)。
     * 新前端上送 {model, longContextEnabled}(已与 supports1M 取并集);
     * 旧前端不发该字段 → 返回 false(向后兼容)。
     */
    static boolean parseLongContextEnabled(Gson gson, String content) {
        try {
            if (content == null || content.isEmpty()) {
                return false;
            }
            JsonObject request = gson.fromJson(content, JsonObject.class);
            if (request != null && request.has("longContextEnabled") && !request.get("longContextEnabled").isJsonNull()) {
                return request.get("longContextEnabled").getAsBoolean();
            }
        } catch (Exception e) {
            // Return false on parse failure
        }
        return false;
    }

    private static boolean isContextUsageUnavailableInCliMode(HandlerContext ctx) {
        try {
            return EffectiveRuntimeResolver.isCliMode(
                    ProviderType.CLAUDE.value(),
                    ctx.getSettingsService().getRuntimePolicy()
            );
        } catch (Exception e) {
            LOG.warn("[GetContextUsageActionHandler] Failed to resolve Claude runtime", e);
            return false;
        }
    }
}
