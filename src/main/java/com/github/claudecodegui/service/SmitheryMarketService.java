package com.github.claudecodegui.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 市场:Smithery Registry API 客户端(只读目录)。
 *
 * <p>搜索 {@code GET /servers?q=&page=&pageSize=} 与详情 {@code GET /servers/{ns}/{slug}},
 * Bearer 认证。响应规范化为前端友好的 JsonObject(只保留必要字段,屏蔽 API 字段噪音)。
 * 仿 {@link ModelFetchService} 的 HttpClient/超时/错误分级模式。
 *
 * <p>纯函数 {@link #buildSearchUrl}/{@link #buildDetailUrl}/{@link #parseSearchResponse}/
 * {@link #parseDetailResponse} 可单测(无 HTTP);{@link #searchServers}/{@link #getServerDetail}
 * 为 HTTP 集成(端到端实测)。
 *
 * <p><b>注意</b>:Smithery 详情端点是否含连接配置(mcpUrl/command)字段官方文档未完整列出,
 * {@link #parseDetailResponse} 做防御性提取(有则预填 connection,无则字段缺省),
 * 端到端实测后调整。前端选中 server 后若 connection 为空,引导用户在 McpServerDialog
 * 手动填写(从 Smithery 页面复制连接配置)。
 */
public class SmitheryMarketService {

    private static final Logger LOG = Logger.getInstance(SmitheryMarketService.class);

    static final String BASE_URL = "https://api.smithery.ai";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_AGENT = "jetbrains-melon-cc-gui-smithery";
    /** Smithery 单页上限(防滥用,实际 API 通常 ≤60) */
    private static final int MAX_PAGE_SIZE = 60;

    /**
     * 构造搜索 URL(纯函数)。空 query → 全量分页;page&lt;1→1;pageSize 钳到 [1,MAX]。
     */
    static String buildSearchUrl(String query, int page, int pageSize) {
        int p = Math.max(1, page);
        int ps = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        List<String> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            params.add("q=" + encode(query.trim()));
        }
        params.add("page=" + p);
        params.add("pageSize=" + ps);
        return BASE_URL + "/servers?" + String.join("&", params);
    }

    /**
     * 构造详情 URL(纯函数)。namespace/slug 做 URL 编码防路径注入。
     */
    static String buildDetailUrl(String namespace, String slug) {
        return BASE_URL + "/servers/" + encode(namespace) + "/" + encode(slug);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 搜索 MCP 服务器。
     *
     * @param query    关键字(可空 → 全量分页)
     * @param page     页码(从 1 起,&lt;1 归 1)
     * @param pageSize 每页条数(钳到 [1,60])
     * @param apiKey   Smithery API Key(Bearer header)
     * @return 规范化 {servers:[...], pagination:{...}}
     * @throws MarketFetchException MISSING_API_KEY/INVALID_API_KEY/NETWORK_ERROR/TIMEOUT/HTTP_xxx
     */
    public static JsonObject searchServers(String query, int page, int pageSize, String apiKey) throws MarketFetchException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new MarketFetchException(MarketFetchException.MISSING_API_KEY);
        }
        String body = httpGet(buildSearchUrl(query, page, pageSize), apiKey);
        return parseSearchResponse(body);
    }

    /**
     * 获取单个 server 详情(含防御性提取的 connection 配置)。
     *
     * @param namespace 仓库命名空间
     * @param slug      server slug
     * @param apiKey    Smithery API Key
     * @return 规范化详情 {namespace,slug,qualifiedName,...,remote, connection:{...}}
     * @throws MarketFetchException MISSING_API_KEY/INVALID_API_KEY/NETWORK_ERROR/TIMEOUT/HTTP_xxx
     */
    public static JsonObject getServerDetail(String namespace, String slug, String apiKey) throws MarketFetchException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new MarketFetchException(MarketFetchException.MISSING_API_KEY);
        }
        if (namespace == null || namespace.isBlank() || slug == null || slug.isBlank()) {
            throw new MarketFetchException(MarketFetchException.PARSE_ERROR);
        }
        String body = httpGet(buildDetailUrl(namespace, slug), apiKey);
        return parseDetailResponse(body, namespace, slug);
    }

    private static String httpGet(String url, String apiKey) throws MarketFetchException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer " + apiKey.trim())
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 401 || code == 403) {
                throw new MarketFetchException(MarketFetchException.INVALID_API_KEY);
            }
            if (code < 200 || code >= 300) {
                throw new MarketFetchException("HTTP_" + code);
            }
            return resp.body();
        } catch (MarketFetchException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            // connect/request 超时单独分级:与"无网络"区分,前端提示超时并可重试
            LOG.debug("[SmitheryMarketService] GET " + url + " timed out: " + e);
            throw new MarketFetchException(MarketFetchException.TIMEOUT, e);
        } catch (Exception e) {
            LOG.debug("[SmitheryMarketService] GET " + url + " failed: " + e);
            throw new MarketFetchException(MarketFetchException.NETWORK_ERROR, e);
        }
    }

    /**
     * 解析搜索响应 → 规范化 {servers:[{...精简字段}], pagination:{...}}。
     * 容错(对称 ModelFetchService.parseModelIds):缺字段跳过,servers 非数组→空,
     * pagination 缺→空对象。纯函数无 Logger 调用,便于单测。
     */
    static JsonObject parseSearchResponse(String body) {
        JsonObject result = new JsonObject();
        JsonArray servers = new JsonArray();
        JsonObject pagination = new JsonObject();

        if (body != null && !body.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(body);
                if (root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    JsonElement sv = obj.get("servers");
                    if (sv != null && sv.isJsonArray()) {
                        for (JsonElement e : sv.getAsJsonArray()) {
                            if (e.isJsonObject()) {
                                servers.add(normalizeServerSummary(e.getAsJsonObject()));
                            }
                        }
                    }
                    JsonElement pg = obj.get("pagination");
                    if (pg != null && pg.isJsonObject()) {
                        pagination = copyFields(pg.getAsJsonObject(),
                            "page", "pageSize", "total", "totalPages", "nextCursor");
                    }
                }
            } catch (Exception ignored) {
                // 非法 JSON → 返回空结构(容错,对称 parseModelIds)
            }
        }

        result.add("servers", servers);
        result.add("pagination", pagination);
        return result;
    }

    /** 提取 server 摘要(列表卡片用)字段,屏蔽 API 噪音。 */
    private static JsonObject normalizeServerSummary(JsonObject s) {
        JsonObject o = new JsonObject();
        copyString(o, s, "id");
        copyString(o, s, "qualifiedName");
        copyString(o, s, "namespace");
        copyString(o, s, "slug");
        copyString(o, s, "displayName");
        copyString(o, s, "description");
        copyString(o, s, "iconUrl");
        copyString(o, s, "homepage");
        copyBool(o, s, "verified");
        copyBool(o, s, "remote");
        copyBool(o, s, "isDeployed");
        copyNumber(o, s, "useCount");
        return o;
    }

    /**
     * 解析详情响应 → 规范化 {namespace,slug,qualifiedName,...,remote, connection:{...}}。
     * connection 做防御性提取:顶层 mcpUrl/url/command/args/env + 嵌套 connection 子对象,
     * 有则预填,无则字段缺省。纯函数无 Logger 调用,便于单测。
     */
    static JsonObject parseDetailResponse(String body, String namespace, String slug) {
        JsonObject o = new JsonObject();
        o.addProperty("namespace", namespace);
        o.addProperty("slug", slug);
        JsonObject connection = new JsonObject();

        if (body != null && !body.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(body);
                if (root.isJsonObject()) {
                    JsonObject s = root.getAsJsonObject();
                    copyString(o, s, "id");
                    copyString(o, s, "qualifiedName");
                    copyString(o, s, "displayName");
                    copyString(o, s, "description");
                    copyString(o, s, "iconUrl");
                    copyString(o, s, "homepage");
                    copyString(o, s, "readme");
                    copyBool(o, s, "verified");
                    copyBool(o, s, "remote");
                    copyNumber(o, s, "useCount");

                    // 顶层连接字段(防御性:端点字段未文档化)
                    copyString(connection, s, "mcpUrl");
                    copyString(connection, s, "url");
                    copyString(connection, s, "command");
                    if (hasArrayOrPrimitive(s, "args")) {
                        connection.add("args", s.get("args"));
                    }
                    if (s.has("env") && s.get("env").isJsonObject()) {
                        connection.add("env", s.get("env"));
                    }
                    // 嵌套 connection 子对象(部分 API 版本)
                    JsonElement connEl = s.get("connection");
                    if (connEl != null && connEl.isJsonObject()) {
                        JsonObject c = connEl.getAsJsonObject();
                        if (!connection.has("mcpUrl")) copyString(connection, c, "mcpUrl");
                        if (!connection.has("url")) copyString(connection, c, "url");
                        if (!connection.has("command")) copyString(connection, c, "command");
                        if (!connection.has("args") && hasArrayOrPrimitive(c, "args")) {
                            connection.add("args", c.get("args"));
                        }
                        if (!connection.has("env") && c.has("env") && c.get("env").isJsonObject()) {
                            connection.add("env", c.get("env"));
                        }
                    }
                }
            } catch (Exception ignored) {
                // 非法 JSON → 返回基础结构(容错)
            }
        }

        o.add("connection", connection);
        return o;
    }

    // ── Gson 字段拷贝辅助(只拷贝存在的非空原始字段) ──

    private static boolean hasArrayOrPrimitive(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull()
            && (o.get(key).isJsonArray() || o.get(key).isJsonPrimitive());
    }

    private static void copyString(JsonObject dst, JsonObject src, String key) {
        if (src.has(key) && !src.get(key).isJsonNull() && src.get(key).isJsonPrimitive()) {
            dst.addProperty(key, src.get(key).getAsString());
        }
    }

    private static void copyBool(JsonObject dst, JsonObject src, String key) {
        if (src.has(key) && !src.get(key).isJsonNull() && src.get(key).isJsonPrimitive()) {
            dst.addProperty(key, src.get(key).getAsBoolean());
        }
    }

    private static void copyNumber(JsonObject dst, JsonObject src, String key) {
        if (src.has(key) && !src.get(key).isJsonNull() && src.get(key).isJsonPrimitive()) {
            dst.addProperty(key, src.get(key).getAsNumber());
        }
    }

    private static JsonObject copyFields(JsonObject src, String... keys) {
        JsonObject o = new JsonObject();
        for (String k : keys) {
            if (src.has(k) && !src.get(k).isJsonNull()) {
                o.add(k, src.get(k));
            }
        }
        return o;
    }
}
