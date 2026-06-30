package com.github.claudecodegui.service;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 根据 baseUrl + apiKey 拉取第三方/代理 OpenAI 兼容模型列表。
 *
 * <p>业务逻辑下沉后端(架构一致性:前端只做调用入口)。移植自 cc-switch 的
 * {@code model_fetch.rs}:候选 URL 构造 + GET /v1/models + Bearer 认证 +
 * 404/405 回退 + {data:[{id,owned_by}]} 解析。
 *
 * <p>纯函数 {@link #buildModelsUrlCandidates} 可单测;{@link #fetchModels} 为 HTTP 集成。
 */
public class ModelFetchService {

    private static final Logger LOG = Logger.getInstance(ModelFetchService.class);

    /** 兼容后缀(移植 cc-switch {@code KNOWN_COMPAT_SUFFIXES}),最长前缀优先剥离。 */
    private static final List<String> KNOWN_COMPAT_SUFFIXES = List.of(
        "/api/claudecode",
        "/api/anthropic",
        "/apps/anthropic",
        "/api/coding",
        "/claudecode",
        "/anthropic",
        "/step_plan",
        "/coding",
        "/claude"
    );

    /** 按长度降序预排序,保证最长前缀优先剥离。 */
    private static final List<String> COMPAT_SUFFIXES_BY_LENGTH;
    static {
        List<String> sorted = new ArrayList<>(KNOWN_COMPAT_SUFFIXES);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        COMPAT_SUFFIXES_BY_LENGTH = List.copyOf(sorted);
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "jetbrains-melon-cc-gui-model-fetch";
    private static final int ERROR_BODY_LIMIT = 300;

    /**
     * 构造 models 端点候选 URL 列表(移植 cc-switch {@code build_models_url_candidates})。
     *
     * <p>决策顺序:
     * <ol>
     *   <li>{@code modelsUrlOverride} 非空(trim 后)→ 单例 {@code [override]}</li>
     *   <li>{@code baseUrl} 空白 → {@link IllegalArgumentException}</li>
     *   <li>{@code isFullUrl}:从完整 URL 推导(含 {@code /v1/} → 截到 {@code /v1} + {@code /models};
     *       否则抛 {@link IllegalArgumentException})</li>
     *   <li>去尾斜杠后:
     *     <ul>
     *       <li>命中兼容后缀:原样 {@code /v1/models} + 剥离后 {@code /v1/models} + 剥离后 {@code /models}</li>
     *       <li>{@code /v1} 结尾:单条 {@code /models}(避免 {@code /v1/v1/models} 重复)</li>
     *       <li>其他版本段结尾(如 {@code /v4}):{@code /models} 在前,{@code /v1/models} 兜底在后</li>
     *       <li>普通根域名:{@code /v1/models}</li>
     *     </ul>
     *   </li>
     *   <li>去重保序</li>
     * </ol>
     */
    public static List<String> buildModelsUrlCandidates(String baseUrl, boolean isFullUrl, String modelsUrlOverride) {
        // 1. override 优先(trim 后非空 → 单例)
        if (modelsUrlOverride != null) {
            String trimmed = modelsUrlOverride.trim();
            if (!trimmed.isEmpty()) {
                return List.of(trimmed);
            }
        }

        // 2. baseUrl 空白校验
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String base = baseUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }

        // 3. isFullUrl:从完整 chat 端点 URL 推导 models 端点
        if (isFullUrl) {
            int idx = base.indexOf("/v1/");
            if (idx >= 0) {
                return List.of(base.substring(0, idx + 3) + "/models");
            }
            if (base.endsWith("/v1")) {
                return List.of(base + "/models");
            }
            throw new IllegalArgumentException("Cannot derive models URL from full URL: " + base);
        }

        // 4. 去尾斜杠(规范化)
        base = stripTrailingSlash(base);

        // 5. 构造候选
        List<String> candidates = new ArrayList<>();
        String stripped = stripCompatSuffix(base);
        if (stripped != null) {
            // 命中兼容后缀:原样 /v1/models + 剥离后 /v1/models + 剥离后 /models
            candidates.add(base + "/v1/models");
            candidates.add(stripped + "/v1/models");
            candidates.add(stripped + "/models");
        } else if (base.endsWith("/v1")) {
            // /v1 结尾:只追加 /models(避免 /v1/v1/models 重复)
            candidates.add(base + "/models");
        } else if (endsWithVersionSegment(base)) {
            // 其他版本段结尾(如 /v4):/models 在前,/v1/models 兜底在后
            candidates.add(base + "/models");
            candidates.add(base + "/v1/models");
        } else {
            // 普通根域名:追加 /v1/models
            candidates.add(base + "/v1/models");
        }

        // 6. 去重保序
        return dedupePreservingOrder(candidates);
    }

    /**
     * 按候选顺序 GET models 端点,首个 2xx 非空响应解析为模型 id 列表。
     *
     * <p>回退策略(移植 cc-switch):
     * <ul>
     *   <li>HTTP 404/405 → 路径不对,回退下一个候选</li>
     *   <li>HTTP 其他非 2xx(401/403/5xx)→ 立即抛出(认证/服务端错误换路径不解决)</li>
     *   <li>网络异常/超时 → 回退下一个候选(某候选域名可能临时不可达)</li>
     *   <li>2xx 但解析为空 → 回退下一个候选(某些代理对错误路径返 200 空壳)</li>
     * </ul>
     *
     * @param candidates 候选 models URL 列表(由 {@link #buildModelsUrlCandidates} 构造)
     * @param apiKey     Bearer token(可为空——某些本地代理无需认证)
     * @return 模型 id 列表(已去重保序)
     * @throws ModelFetchException 候选为空,或首个非 404/405 HTTP 错误,或全部候选均不可用
     */
    public static List<String> fetchModels(List<String> candidates, String apiKey) throws ModelFetchException {
        if (candidates == null || candidates.isEmpty()) {
            throw new ModelFetchException("No model URL candidates provided");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        String lastError = null;
        String lastTriedUrl = null;
        for (String url : candidates) {
            lastTriedUrl = url;
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET();
                if (apiKey != null && !apiKey.isBlank()) {
                    rb.header("Authorization", "Bearer " + apiKey.trim());
                }
                HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 404 || code == 405) {
                    lastError = "HTTP " + code;
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new ModelFetchException("HTTP " + code + " for " + url + ": " + truncate(resp.body()));
                }
                List<String> ids = parseModelIds(resp.body());
                if (ids.isEmpty()) {
                    lastError = "empty model list";
                    continue;
                }
                return ids;
            } catch (ModelFetchException e) {
                throw e;
            } catch (Exception e) {
                // 网络/超时/解析异常 → 回退下一个候选
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                LOG.debug("[ModelFetchService] candidate " + url + " failed: " + lastError);
            }
        }
        throw new ModelFetchException("All candidates failed (last tried: " + lastTriedUrl + "): " + lastError);
    }

    /**
     * 解析 OpenAI 兼容 models 响应。兼容三种结构:
     * {@code {"data":[{"id":"..."}]}}, {@code {"models":[{"id":"..."}]}}, {@code [{"id":"..."}]}。
     */
    static List<String> parseModelIds(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Exception e) {
            return List.of();
        }
        JsonArray arr = null;
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                arr = obj.getAsJsonArray("data");
            } else if (obj.has("models") && obj.get("models").isJsonArray()) {
                arr = obj.getAsJsonArray("models");
            }
        } else if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        }
        if (arr == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e.isJsonObject()) {
                JsonObject m = e.getAsJsonObject();
                if (m.has("id") && !m.get("id").isJsonNull()) {
                    ids.add(m.get("id").getAsString());
                }
            }
        }
        return dedupePreservingOrder(ids);
    }

    /**
     * 剥离已知兼容后缀(最长前缀优先)。返回剥离后的 base;无匹配返回 {@code null}。
     *
     * <p>最长前缀优先确保 {@code https://x/api/anthropic} 剥离 {@code /api/anthropic}
     * 得到 {@code https://x}(而非剥离 {@code /anthropic} 得到 {@code https://x/api})。
     */
    private static String stripCompatSuffix(String base) {
        for (String suffix : COMPAT_SUFFIXES_BY_LENGTH) {
            if (base.endsWith(suffix)) {
                return base.substring(0, base.length() - suffix.length());
            }
        }
        return null;
    }

    /**
     * 最后一段是否为版本段({@code v} + 数字,如 {@code v1}/{@code v2}/{@code v4})。
     * 移植 cc-switch {@code ends_with_version_segment}。
     */
    private static boolean endsWithVersionSegment(String base) {
        int slash = base.lastIndexOf('/');
        if (slash < 0 || slash >= base.length() - 1) {
            return false;
        }
        String seg = base.substring(slash + 1);
        return seg.length() >= 2 && seg.charAt(0) == 'v' && Character.isDigit(seg.charAt(1));
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static List<String> dedupePreservingOrder(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= ERROR_BODY_LIMIT ? s : s.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    /** 兼容 GsonHolder(保留 import 用于未来 body 解析统一;当前 parseModelIds 用 JsonParser 直解)。 */
    @SuppressWarnings("unused")
    private static final com.google.gson.Gson GSON = GsonHolder.GSON;
}
