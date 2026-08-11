package com.github.claudecodegui.handler.context;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * CLI-only:/context(token 上下文用量)依赖 SDK daemon 查询,CLI 模式无常驻 daemon,
 * 故直接回传不支持错误,给前端明确反馈(前端 /context 命令仍可触发,经
 * {@code onContextUsageError} 回调展示)。
 *
 * <p>历史:SDK 模式下经 {@code getContextUsage} 异步查询 daemon 并弹出
 * 用量对话框;SDK 调用模式移除后该能力不可用。保留 handler 绑定 {@link UpstreamAction#GET_CONTEXT_USAGE}
 * 以接管前端请求,并保留解析契约({@link #parseContextUsageRequest} / {@link #parseLongContextEnabled})
 * 供既有契约测试覆盖。
 */
public final class GetContextUsageActionHandler implements FrontendActionHandler<String> {

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
        String requestId = parsed[3];
        // CLI 模式无 SDK daemon,/context 用量查询不可用。回传错误供前端展示。
        callContextUsageError(
                ctx,
                "Context usage (/context) is unavailable in CLI mode.",
                requestId
        );
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
}
