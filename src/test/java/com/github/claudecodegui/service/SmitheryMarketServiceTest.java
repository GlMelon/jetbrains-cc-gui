package com.github.claudecodegui.service;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SmitheryMarketService} 纯函数单测(URL 构造 + 响应解析容错)。
 * <p>不测 HTTP 集成(需网络+真实 key,留端到端);覆盖 buildSearchUrl/buildDetailUrl/
 * parseSearchResponse/parseDetailResponse 的边界与容错。
 */
public class SmitheryMarketServiceTest {

    // ── buildSearchUrl ──

    @Test
    public void buildSearchUrlEmptyQueryOmitsQ() {
        assertEquals("https://api.smithery.ai/servers?page=1&pageSize=20",
            SmitheryMarketService.buildSearchUrl("", 1, 20));
    }

    @Test
    public void buildSearchUrlNullQueryOmitsQ() {
        assertEquals("https://api.smithery.ai/servers?page=2&pageSize=10",
            SmitheryMarketService.buildSearchUrl(null, 2, 10));
    }

    @Test
    public void buildSearchUrlEncodesQuery() {
        // 空格 → + (URLEncoder.encode 规范)
        assertEquals("https://api.smithery.ai/servers?q=exa+search&page=1&pageSize=20",
            SmitheryMarketService.buildSearchUrl("exa search", 1, 20));
    }

    @Test
    public void buildSearchUrlClampsPageBelowOne() {
        assertEquals("https://api.smithery.ai/servers?page=1&pageSize=20",
            SmitheryMarketService.buildSearchUrl("", 0, 20));
        assertEquals("https://api.smithery.ai/servers?page=1&pageSize=20",
            SmitheryMarketService.buildSearchUrl("", -5, 20));
    }

    @Test
    public void buildSearchUrlClampsPageSize() {
        // pageSize > 60 → 60;pageSize < 1 → 1
        assertEquals("https://api.smithery.ai/servers?page=1&pageSize=60",
            SmitheryMarketService.buildSearchUrl("", 1, 100));
        assertEquals("https://api.smithery.ai/servers?page=1&pageSize=1",
            SmitheryMarketService.buildSearchUrl("", 1, 0));
    }

    // ── buildDetailUrl ──

    @Test
    public void buildDetailUrlPlain() {
        assertEquals("https://api.smithery.ai/servers/org/fs",
            SmitheryMarketService.buildDetailUrl("org", "fs"));
    }

    @Test
    public void buildDetailUrlEncodesSpecialChars() {
        // @ → %40,空格 → +
        assertEquals("https://api.smithery.ai/servers/%40org/fs",
            SmitheryMarketService.buildDetailUrl("@org", "fs"));
        assertEquals("https://api.smithery.ai/servers/org/some+slug",
            SmitheryMarketService.buildDetailUrl("org", "some slug"));
    }

    // ── parseSearchResponse ──

    @Test
    public void parseSearchResponseFull() {
        String body = "{\"servers\":["
            + "{\"qualifiedName\":\"@org/fs\",\"displayName\":\"Filesystem\","
            + "\"description\":\"FS access\",\"verified\":true,\"remote\":false,"
            + "\"useCount\":123,\"extra\":\"noise\"}"
            + "],\"pagination\":{\"page\":1,\"pageSize\":20,\"total\":100,\"nextCursor\":\"abc\"}}";
        JsonObject r = SmitheryMarketService.parseSearchResponse(body);
        assertEquals(1, r.getAsJsonArray("servers").size());
        JsonObject s0 = r.getAsJsonArray("servers").get(0).getAsJsonObject();
        assertEquals("@org/fs", s0.get("qualifiedName").getAsString());
        assertEquals("Filesystem", s0.get("displayName").getAsString());
        assertEquals("FS access", s0.get("description").getAsString());
        assertTrue(s0.get("verified").getAsBoolean());
        assertFalse(s0.get("remote").getAsBoolean());
        assertEquals(123, s0.get("useCount").getAsInt());
        // 噪音字段屏蔽
        assertFalse(s0.has("extra"));
        // pagination
        JsonObject pg = r.getAsJsonObject("pagination");
        assertEquals(1, pg.get("page").getAsInt());
        assertEquals(100, pg.get("total").getAsInt());
        assertEquals("abc", pg.get("nextCursor").getAsString());
    }

    @Test
    public void parseSearchResponseMultipleServers() {
        String body = "{\"servers\":["
            + "{\"qualifiedName\":\"@a/x\"},{\"qualifiedName\":\"@b/y\"}"
            + "]}";
        JsonObject r = SmitheryMarketService.parseSearchResponse(body);
        assertEquals(2, r.getAsJsonArray("servers").size());
    }

    @Test
    public void parseSearchResponseServersMissing() {
        JsonObject r = SmitheryMarketService.parseSearchResponse("{\"pagination\":{\"page\":1}}");
        assertEquals(0, r.getAsJsonArray("servers").size());
        assertTrue(r.has("pagination"));
    }

    @Test
    public void parseSearchResponseServersNotArray() {
        JsonObject r = SmitheryMarketService.parseSearchResponse("{\"servers\":\"notarray\"}");
        assertEquals(0, r.getAsJsonArray("servers").size());
    }

    @Test
    public void parseSearchResponseEmptyBody() {
        JsonObject r = SmitheryMarketService.parseSearchResponse("");
        assertEquals(0, r.getAsJsonArray("servers").size());
        assertTrue(r.getAsJsonObject("pagination").entrySet().isEmpty());
    }

    @Test
    public void parseSearchResponseIllegalJson() {
        // 非法 JSON → 容错空结构(对称 parseModelIds)
        JsonObject r = SmitheryMarketService.parseSearchResponse("not json at all");
        assertEquals(0, r.getAsJsonArray("servers").size());
    }

    @Test
    public void parseSearchResponseArrayRoot() {
        // 根是数组(非对象)→ 容错空结构
        JsonObject r = SmitheryMarketService.parseSearchResponse("[1,2,3]");
        assertEquals(0, r.getAsJsonArray("servers").size());
    }

    // ── parseDetailResponse ──

    @Test
    public void parseDetailResponseWithTopLevelConnection() {
        String body = "{\"qualifiedName\":\"@org/fs\",\"displayName\":\"Filesystem\","
            + "\"remote\":false,\"command\":\"npx\",\"args\":[\"-y\",\"mcp-server\"],"
            + "\"env\":{\"API_KEY\":\"xxx\"}}";
        JsonObject r = SmitheryMarketService.parseDetailResponse(body, "org", "fs");
        assertEquals("org", r.get("namespace").getAsString());
        assertEquals("fs", r.get("slug").getAsString());
        assertEquals("Filesystem", r.get("displayName").getAsString());
        assertFalse(r.get("remote").getAsBoolean());
        JsonObject conn = r.getAsJsonObject("connection");
        assertEquals("npx", conn.get("command").getAsString());
        assertEquals(2, conn.getAsJsonArray("args").size());
        assertEquals("xxx", conn.getAsJsonObject("env").get("API_KEY").getAsString());
    }

    @Test
    public void parseDetailResponseRemoteWithMcpUrl() {
        String body = "{\"remote\":true,\"mcpUrl\":\"https://x.com/sse\"}";
        JsonObject r = SmitheryMarketService.parseDetailResponse(body, "org", "fs");
        assertTrue(r.get("remote").getAsBoolean());
        JsonObject conn = r.getAsJsonObject("connection");
        assertEquals("https://x.com/sse", conn.get("mcpUrl").getAsString());
    }

    @Test
    public void parseDetailResponseNestedConnectionFillsMissing() {
        // 顶层无 command,嵌套 connection 有 → 取嵌套
        String body = "{\"remote\":true,\"connection\":{\"mcpUrl\":\"https://x.com/sse\",\"command\":\"fallback\"}}";
        JsonObject conn = SmitheryMarketService.parseDetailResponse(body, "org", "fs")
            .getAsJsonObject("connection");
        assertEquals("https://x.com/sse", conn.get("mcpUrl").getAsString());
        assertEquals("fallback", conn.get("command").getAsString());
    }

    @Test
    public void parseDetailResponseTopLevelWinsOverNested() {
        // 顶层和嵌套都有 mcpUrl → 顶层优先
        String body = "{\"mcpUrl\":\"https://top.com/sse\","
            + "\"connection\":{\"mcpUrl\":\"https://nested.com/sse\"}}";
        JsonObject conn = SmitheryMarketService.parseDetailResponse(body, "org", "fs")
            .getAsJsonObject("connection");
        assertEquals("https://top.com/sse", conn.get("mcpUrl").getAsString());
    }

    @Test
    public void parseDetailResponseNoConnectionFields() {
        // 无任何连接字段 → connection 空对象
        String body = "{\"qualifiedName\":\"@org/fs\",\"displayName\":\"FS\",\"description\":\"d\"}";
        JsonObject r = SmitheryMarketService.parseDetailResponse(body, "org", "fs");
        JsonObject conn = r.getAsJsonObject("connection");
        assertTrue("connection should be empty when no connection fields present",
            conn.entrySet().isEmpty());
    }

    @Test
    public void parseDetailResponseEmptyBody() {
        JsonObject r = SmitheryMarketService.parseDetailResponse("", "org", "fs");
        assertEquals("org", r.get("namespace").getAsString());
        assertEquals("fs", r.get("slug").getAsString());
        assertTrue(r.getAsJsonObject("connection").entrySet().isEmpty());
    }

    @Test
    public void parseDetailResponseIllegalJson() {
        JsonObject r = SmitheryMarketService.parseDetailResponse("not json", "org", "fs");
        assertEquals("org", r.get("namespace").getAsString());
        assertTrue(r.getAsJsonObject("connection").entrySet().isEmpty());
    }

    // ── isTransientError ──

    @Test
    public void isTransientErrorTrueForTimeoutAndNetwork() {
        assertTrue(SmitheryMarketService.isTransientError(MarketFetchException.TIMEOUT));
        assertTrue(SmitheryMarketService.isTransientError(MarketFetchException.NETWORK_ERROR));
    }

    @Test
    public void isTransientErrorFalseForAuthParseHttpAndNull() {
        // 认证/解析/HTTP 状态码错误重试无意义,不重试
        assertFalse(SmitheryMarketService.isTransientError(MarketFetchException.INVALID_API_KEY));
        assertFalse(SmitheryMarketService.isTransientError(MarketFetchException.MISSING_API_KEY));
        assertFalse(SmitheryMarketService.isTransientError(MarketFetchException.PARSE_ERROR));
        assertFalse(SmitheryMarketService.isTransientError("HTTP_500"));
        assertFalse(SmitheryMarketService.isTransientError(null));
    }
}
