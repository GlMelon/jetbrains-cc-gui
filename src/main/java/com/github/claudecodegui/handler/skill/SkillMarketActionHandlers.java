package com.github.claudecodegui.handler.skill;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.service.MarketFetchException;
import com.github.claudecodegui.service.SkillMarketService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * Skills 市场 action handlers 容器。
 *
 * <p>委托类持有 {@link HandlerContext},内部经 {@link SkillMarketService} 调 GitHub API。
 * <b>provider 从 {@link HandlerContext#getCurrentProvider()} 读(不从前端传,防伪造)</b>;
 * scope 按 provider 归一(Codex=user/repo,其余 global/local)。
 *
 * <p>线程模型仿 {@code McpMarketActionHandlers}/{@code FetchProviderModelsActionHandler}:
 * {@code CompletableFuture.runAsync(..., AppExecutorUtil)} 后台 HTTP +
 * {@code invokeLater} 派发 {@link DownstreamEvent} + {@code __requestId} 路由回前端 Promise。
 *
 * <p>list 下行 SKILL_MARKET_LIST:{sources,source,skills} 或 {error,errorCode};
 * install 下行 SKILL_MARKET_INSTALL_RESULT:{success,skillName,source,hash,importResult}
 * 或 {success:false,error,errorCode}。前端据 errorCode 精准引导:
 * UNKNOWN_SOURCE/INVALID_SKILL_NAME/HASH_MISMATCH/HTTP_404/HTTP_403(rate limit)/NETWORK_ERROR/PARSE_ERROR/INSTALL_FAILED。
 */
public class SkillMarketActionHandlers {

    private static final Logger LOG = Logger.getInstance(SkillMarketActionHandlers.class);
    private static final Gson GSON = GsonHolder.GSON;

    private final HandlerContext context;

    public SkillMarketActionHandlers(HandlerContext context) {
        this.context = context;
    }

    /**
     * 列出某源的 skills。payload: {source?, __requestId}。默认源 anthropics。
     * 下行 SKILL_MARKET_LIST。
     */
    void handleListSkillMarket(String payload) {
        String sourceId = "anthropics";
        String requestId = null;
        try {
            JsonObject p = GSON.fromJson(payload, JsonObject.class);
            if (p != null) {
                sourceId = optString(p, "source", "anthropics");
                if (p.has("__requestId") && !p.get("__requestId").isJsonNull()) {
                    requestId = p.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON → 默认源
        }

        final String fSourceId = sourceId;
        final String fRequestId = requestId;

        CompletableFuture.runAsync(() -> {
            JsonObject result;
            try {
                result = SkillMarketService.listMarketSkills(fSourceId);
            } catch (MarketFetchException e) {
                LOG.warn("[SkillMarket] list failed: " + e.getErrorCode());
                result = errorObj(e.getErrorCode(), e.getMessage());
            }
            attachRequestId(result, fRequestId);
            final JsonObject fr = result;
            ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.SKILL_MARKET_LIST.value(), context.escapeJs(GSON.toJson(fr))));
        }, AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 安装 skill。payload: {source?, skillPath, scope?, __requestId}。
     * provider 从 context 读;scope 按 provider 归一。
     * 下行 SKILL_MARKET_INSTALL_RESULT。
     */
    void handleInstallSkillFromMarket(String payload) {
        String sourceId = "anthropics";
        String skillPath = "";
        String scope = "global";
        String requestId = null;
        try {
            JsonObject p = GSON.fromJson(payload, JsonObject.class);
            if (p != null) {
                sourceId = optString(p, "source", "anthropics");
                skillPath = optString(p, "skillPath", "");
                scope = optString(p, "scope", "global");
                if (p.has("__requestId") && !p.get("__requestId").isJsonNull()) {
                    requestId = p.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
        }

        final String fProvider = context.getCurrentProvider();
        final String fCwd = context.getProject().getBasePath();
        final String fScope = normalizeScope(fProvider, scope);
        final String fSourceId = sourceId;
        final String fSkillPath = skillPath;
        final String fRequestId = requestId;

        CompletableFuture.runAsync(() -> {
            JsonObject result;
            try {
                if (fSkillPath.isEmpty()) {
                    throw new MarketFetchException("INVALID_SKILL_NAME");
                }
                result = SkillMarketService.installSkill(fSourceId, fSkillPath, fScope, fProvider, fCwd);
            } catch (MarketFetchException e) {
                LOG.warn("[SkillMarket] install failed: " + e.getErrorCode());
                result = errorObj(e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[SkillMarket] install error: " + e.getMessage(), e);
                result = errorObj("INSTALL_FAILED", e.getMessage());
            }
            attachRequestId(result, fRequestId);
            final JsonObject fr = result;
            ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.SKILL_MARKET_INSTALL_RESULT.value(), context.escapeJs(GSON.toJson(fr))));
        }, AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 获取单个 skill 详情。payload: {source?, skillPath, __requestId}。
     * raw 下载单个 SKILL.md 解析 frontmatter(name/description/license/compatibility/allowedTools/userInvocable/paths)。
     * 下行 SKILL_MARKET_DETAIL: {name,description,...,path,source,sourceLabel,__requestId}
     * 或 {success:false,error,errorCode,__requestId}。
     */
    void handleGetSkillMarketDetail(String payload) {
        String sourceId = "anthropics";
        String skillPath = "";
        String requestId = null;
        try {
            JsonObject p = GSON.fromJson(payload, JsonObject.class);
            if (p != null) {
                sourceId = optString(p, "source", "anthropics");
                skillPath = optString(p, "skillPath", "");
                if (p.has("__requestId") && !p.get("__requestId").isJsonNull()) {
                    requestId = p.get("__requestId").getAsString();
                }
            }
        } catch (Exception ignored) {
        }

        final String fSourceId = sourceId;
        final String fSkillPath = skillPath;
        final String fRequestId = requestId;

        CompletableFuture.runAsync(() -> {
            JsonObject result;
            try {
                if (fSkillPath.isEmpty()) {
                    throw new MarketFetchException("INVALID_SKILL_NAME");
                }
                result = SkillMarketService.getSkillMarketDetail(fSourceId, fSkillPath);
            } catch (MarketFetchException e) {
                LOG.warn("[SkillMarket] detail failed: " + e.getErrorCode());
                result = errorObj(e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[SkillMarket] detail error: " + e.getMessage(), e);
                result = errorObj("PARSE_ERROR", e.getMessage());
            }
            attachRequestId(result, fRequestId);
            final JsonObject fr = result;
            ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.SKILL_MARKET_DETAIL.value(), context.escapeJs(GSON.toJson(fr))));
        }, AppExecutorUtil.getAppExecutorService());
    }

    /** scope 词汇按 provider 归一:Codex=user/repo,其余 global/local。 */
    private static String normalizeScope(String provider, String scope) {
        boolean isCodex = CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(provider);
        if (isCodex) {
            return "local".equalsIgnoreCase(scope) ? "repo" : "user";
        }
        return "local".equalsIgnoreCase(scope) ? "local" : "global";
    }

    private static JsonObject errorObj(String errorCode, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("success", false);
        o.addProperty("error", message != null ? message : errorCode);
        o.addProperty("errorCode", errorCode);
        return o;
    }

    private static void attachRequestId(JsonObject o, String requestId) {
        if (requestId != null) {
            o.addProperty("__requestId", requestId);
        }
    }

    private static String optString(JsonObject o, String key, String def) {
        if (o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()) {
            String s = o.get(key).getAsString();
            return (s == null || s.isBlank()) ? def : s;
        }
        return def;
    }
}
