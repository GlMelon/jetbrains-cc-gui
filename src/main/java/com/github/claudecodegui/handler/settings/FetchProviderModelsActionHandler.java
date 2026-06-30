package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.service.ModelFetchException;
import com.github.claudecodegui.service.ModelFetchService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 拉取第三方/代理 OpenAI 兼容 models 列表(RPC)。
 *
 * <p>业务逻辑下沉后端(架构一致性):前端只发 {@code baseUrl/apiKey/isFullUrl/modelsUrlOverride}
 * 入口参数,本 handler 调 {@link ModelFetchService} 构造候选 + HTTP 拉取 + 解析,结果经
 * {@link DownstreamEvent#PROVIDER_MODELS_FETCHED} 派发回前端,携带 {@code __requestId}
 * 供 bridgeHub 路由到对应 Promise(对称 {@code FileActionHandlers#handleResolveFilePath})。
 *
 * <p>成功响应:{@code {"models":["id1","id2"], "__requestId":"..."}}。
 * 失败响应:{@code {"error":"...", "__requestId":"..."}}。
 */
public final class FetchProviderModelsActionHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(FetchProviderModelsActionHandler.class);
    private static final Gson GSON = GsonHolder.GSON;

    @Override
    public UpstreamAction action() {
        return UpstreamAction.FETCH_PROVIDER_MODELS;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        // 解析请求 {baseUrl, apiKey, isFullUrl, modelsUrlOverride, __requestId}
        String baseUrl = "";
        String apiKey = null;
        boolean isFullUrl = false;
        String modelsUrlOverride = null;
        String requestId = null;
        try {
            JsonObject parsed = GSON.fromJson(payload, JsonObject.class);
            if (parsed != null) {
                baseUrl = optString(parsed, "baseUrl");
                apiKey = optStringNullable(parsed, "apiKey");
                isFullUrl = parsed.has("isFullUrl")
                    && parsed.get("isFullUrl").isJsonPrimitive()
                    && parsed.get("isFullUrl").getAsBoolean();
                modelsUrlOverride = optStringNullable(parsed, "modelsUrlOverride");
                if (parsed.has("__requestId") && !parsed.get("__requestId").isJsonNull()) {
                    requestId = parsed.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON payload —— 用默认空 baseUrl,后续构造候选会抛 IllegalArgumentException 转错误响应
        }

        final String finalBaseUrl = baseUrl;
        final String finalApiKey = apiKey;
        final boolean finalIsFullUrl = isFullUrl;
        final String finalOverride = modelsUrlOverride;
        final String finalRequestId = requestId;

        // HTTP 阻塞调用 → 后台线程;完成后 EDT 派发(对称 handleResolveFilePath)
        CompletableFuture.runAsync(() -> {
            String errorMsg = null;
            List<String> models = null;
            try {
                List<String> candidates = ModelFetchService.buildModelsUrlCandidates(
                    finalBaseUrl, finalIsFullUrl, finalOverride);
                models = ModelFetchService.fetchModels(candidates, finalApiKey);
            } catch (IllegalArgumentException | ModelFetchException e) {
                LOG.warn("[FetchProviderModels] Failed for baseUrl=" + finalBaseUrl + ": " + e.getMessage());
                errorMsg = e.getMessage();
            }

            final List<String> finalModels = models;
            final String finalError = errorMsg;
            ApplicationManager.getApplication().invokeLater(() -> {
                HandlerContext ctx = context.handlerContext();
                if (finalError != null) {
                    JsonObject err = new JsonObject();
                    err.addProperty("error", finalError != null ? finalError : "model fetch failed");
                    attachRequestId(err, finalRequestId);
                    ctx.dispatchEvent(DownstreamEvent.PROVIDER_MODELS_FETCHED.value(), GSON.toJson(err));
                } else {
                    JsonObject result = new JsonObject();
                    JsonArray arr = new JsonArray();
                    finalModels.forEach(arr::add);
                    result.add("models", arr);
                    attachRequestId(result, finalRequestId);
                    ctx.dispatchEvent(DownstreamEvent.PROVIDER_MODELS_FETCHED.value(), GSON.toJson(result));
                }
            });
        }, AppExecutorUtil.getAppExecutorService());
    }

    private static void attachRequestId(JsonObject o, String requestId) {
        if (requestId != null) {
            o.addProperty("__requestId", requestId);
        }
    }

    private static String optString(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            return o.get(key).getAsString();
        }
        return "";
    }

    private static String optStringNullable(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull()) {
            String s = o.get(key).getAsString();
            return s.isBlank() ? null : s;
        }
        return null;
    }
}
