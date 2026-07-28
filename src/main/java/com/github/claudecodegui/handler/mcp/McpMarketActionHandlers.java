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

    /**
     * 从市场一键安装 MCP 服务器。payload: {server, __requestId}。
     * <p>server 是前端已组装的完整 McpServer(id/name/server{type,command,args,url,env,headers,riskLevel}/apps/enabled)。
     * <p>后端做 riskLevel 安全校验:{@code unverified-command}(marketplace 不可信 runner 或危险 flag)拒绝安装,
     * 不给前端绕过;再 {@code upsertMcpServer} 落盘。
     * <p>下行 MCP_MARKET_INSTALL_RESULT:{success:true, serverId, name, __requestId}
     * 或 {success:false, error, errorCode, __requestId}。前端据 errorCode 精准引导:
     * INVALID_INSTALL_OPTION(payload 缺 server)/INSTALL_REJECTED_RISK(高风险拒绝)/INSTALL_FAILED(落盘失败)。
     * <p>安全:日志只记 serverId + riskLevel + 结果,不记 command/args/env 值(与 §S2 日志安全一致)。
     */
    void handleInstallMcpFromMarket(String payload) {
        JsonObject serverObj = null;
        String requestId = null;
        try {
            JsonObject p = GSON.fromJson(payload, JsonObject.class);
            if (p != null) {
                if (p.has("server") && !p.get("server").isJsonNull()) {
                    serverObj = p.getAsJsonObject("server");
                }
                if (p.has("__requestId") && !p.get("__requestId").isJsonNull()) {
                    requestId = p.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
        }

        final JsonObject fServer = serverObj;
        final String fRequestId = requestId;
        final String fProjectPath = context.getProject() != null ? context.getProject().getBasePath() : null;

        CompletableFuture.runAsync(() -> {
            JsonObject result;
            try {
                String rejection = evaluateInstallRisk(fServer);
                if (rejection != null) {
                    LOG.warn("[McpMarket] install rejected: " + rejection + " (" + optString(fServer, "id") + ")");
                    throw new MarketFetchException(rejection);
                }
                JsonObject serverSpec = fServer.getAsJsonObject("server");
                String riskLevel = optStringNullable(serverSpec, "riskLevel");
                String serverId = optString(fServer, "id");
                context.getSettingsService().upsertMcpServer(fServer, fProjectPath);
                result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("serverId", serverId);
                result.addProperty("name", optString(fServer, "name"));
                LOG.info("[McpMarket] installed: " + serverId + " (risk=" + riskLevel + ")");
            } catch (MarketFetchException e) {
                LOG.warn("[McpMarket] install rejected/failed: " + e.getErrorCode());
                result = installErrorObj(e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[McpMarket] install error: " + e.getMessage(), e);
                result = installErrorObj("INSTALL_FAILED", e.getMessage());
            }
            attachRequestId(result, fRequestId);
            final JsonObject fr = result;
            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.MCP_MARKET_INSTALL_RESULT.value(), context.escapeJs(GSON.toJson(fr))));
        }, AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 评估一键安装风险(纯函数,便于单测)。返回 null 表示允许安装;非 null 表示拒绝的 errorCode。
     * <ul>
     *   <li>server 缺失或无 server spec → {@code INVALID_INSTALL_OPTION}</li>
     *   <li>riskLevel={@code unverified-command}(marketplace 不可信 runner / 危险 flag)→ {@code INSTALL_REJECTED_RISK}</li>
     * </ul>
     * 此为后端安全闸门:前端虽有风险警告,但不可绕过此校验。
     */
    static String evaluateInstallRisk(JsonObject serverObj) {
        if (serverObj == null || !serverObj.has("server") || !serverObj.get("server").isJsonObject()) {
            return "INVALID_INSTALL_OPTION";
        }
        String riskLevel = optStringNullable(serverObj.getAsJsonObject("server"), "riskLevel");
        if ("unverified-command".equals(riskLevel)) {
            return "INSTALL_REJECTED_RISK";
        }
        return null;
    }

    /** install 专用错误对象(带 success:false,与 SkillMarketActionHandlers 一致;区别于 search/detail 的 errorObj)。 */
    private static JsonObject installErrorObj(String errorCode, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("success", false);
        o.addProperty("error", message != null ? message : errorCode);
        o.addProperty("errorCode", errorCode);
        return o;
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
