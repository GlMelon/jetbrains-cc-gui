package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.service.MarketFetchException;
import com.github.claudecodegui.service.SmitheryMarketService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 市场(Smithery Registry) action handlers 容器。
 *
 * <p>委托类持有 {@link HandlerContext},内部经 {@link SmitheryMarketService} 调 Smithery API。
 * Bearer key 从 {@code CodemossSettingsService.getSmitheryApiKey()} 读取(后端经 PasswordStore 持有凭证,
 * 无 keychain 时降级回 config.json 0600;不从前端传——前端只存掩码 hasKey/masked)。
 *
 * <p>线程模型仿 {@code FetchProviderModelsActionHandler}:
 * {@code CompletableFuture.runAsync(..., AppExecutorUtil)} 后台 HTTP +
 * {@code invokeLater} 派发 {@link DownstreamEvent} + {@code __requestId} 路由回前端 Promise。
 *
 * <p>成功/失败均走对应 list/detail 事件(失败带 {@code error}/{@code errorCode} 字段),
 * 前端据 errorCode 精准引导:MISSING_API_KEY→引导配置入口;INVALID_API_KEY→key 无效;
 * NETWORK_ERROR→网络;HTTP_xxx→服务端错误。
 */
public class McpMarketActionHandlers {

    private static final Logger LOG = Logger.getInstance(McpMarketActionHandlers.class);
    private static final Gson GSON = GsonHolder.GSON;

    private final HandlerContext context;

    public McpMarketActionHandlers(HandlerContext context) {
        this.context = context;
    }

    /**
     * 搜索 MCP 服务器。payload: {query?, page?, pageSize?, __requestId}。
     * 下行 MCP_MARKET_LIST: {servers:[...], pagination:{...}, __requestId} 或 {error, errorCode, __requestId}。
     */
    void handleSearchMcpMarket(String payload) {
        String query = null;
        int page = 1;
        int pageSize = 20;
        String requestId = null;
        try {
            JsonObject p = GSON.fromJson(payload, JsonObject.class);
            if (p != null) {
                query = optStringNullable(p, "query");
                if (p.has("page") && p.get("page").isJsonPrimitive()) {
                    page = p.get("page").getAsInt();
                }
                if (p.has("pageSize") && p.get("pageSize").isJsonPrimitive()) {
                    pageSize = p.get("pageSize").getAsInt();
                }
                if (p.has("__requestId") && !p.get("__requestId").isJsonNull()) {
                    requestId = p.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON → 用默认参数(全量第 1 页)
        }

        final String fQuery = query;
        final int fPage = page;
        final int fPageSize = pageSize;
        final String fRequestId = requestId;

        CompletableFuture.runAsync(() -> {
            JsonObject result;
            try {
                String apiKey = readApiKey();
                result = SmitheryMarketService.searchServers(fQuery, fPage, fPageSize, apiKey);
            } catch (MarketFetchException e) {
                LOG.warn("[McpMarket] search failed: " + e.getErrorCode());
                result = errorObj(e.getErrorCode(), e.getMessage());
            }
            attachRequestId(result, fRequestId);
            final JsonObject fr = result;
            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.MCP_MARKET_LIST.value(), context.escapeJs(GSON.toJson(fr))));
        }, AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 获取单个 server 详情。payload: {namespace, slug, __requestId}。
     * 下行 MCP_MARKET_DETAIL: {namespace,slug,...,connection:{...}, __requestId} 或 {error, errorCode, __requestId}。
     */
    void handleGetMcpMarketDetail(String payload) {
        String namespace = "";
        String slug = "";
        String requestId = null;
        try {
            JsonObject p = GSON.fromJson(payload, JsonObject.class);
            if (p != null) {
                namespace = optString(p, "namespace");
                slug = optString(p, "slug");
                if (p.has("__requestId") && !p.get("__requestId").isJsonNull()) {
                    requestId = p.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
        }

        final String fNamespace = namespace;
        final String fSlug = slug;
        final String fRequestId = requestId;

        CompletableFuture.runAsync(() -> {
            JsonObject result;
            try {
                String apiKey = readApiKey();
                result = SmitheryMarketService.getServerDetail(fNamespace, fSlug, apiKey);
            } catch (MarketFetchException e) {
                LOG.warn("[McpMarket] detail failed: " + e.getErrorCode());
                result = errorObj(e.getErrorCode(), e.getMessage());
            }
            attachRequestId(result, fRequestId);
            final JsonObject fr = result;
            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.MCP_MARKET_DETAIL.value(), context.escapeJs(GSON.toJson(fr))));
        }, AppExecutorUtil.getAppExecutorService());
    }

    /** 读取 Smithery API Key;IO 失败按空处理(→ service 抛 MISSING_API_KEY 引导配置)。 */
    private String readApiKey() {
        try {
            return context.getSettingsService().getSmitheryApiKey();
        } catch (IOException e) {
            LOG.warn("[McpMarket] Failed to read Smithery API key: " + e.getMessage());
            return "";
        }
    }

    private static JsonObject errorObj(String errorCode, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message != null ? message : errorCode);
        o.addProperty("errorCode", errorCode);
        return o;
    }

    private static void attachRequestId(JsonObject o, String requestId) {
        if (requestId != null) {
            o.addProperty("__requestId", requestId);
        }
    }

    private static String optString(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()) {
            return o.get(key).getAsString();
        }
        return "";
    }

    private static String optStringNullable(JsonObject o, String key) {
        if (o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()) {
            String s = o.get(key).getAsString();
            return s.isBlank() ? null : s;
        }
        return null;
    }
}
