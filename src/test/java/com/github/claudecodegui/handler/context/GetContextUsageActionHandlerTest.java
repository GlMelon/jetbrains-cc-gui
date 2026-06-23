package com.github.claudecodegui.handler.context;

import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 契约测试:GetContextUsageActionHandler 必须绑定 GET_CONTEXT_USAGE 枚举并以 String 为载荷类型
 * (handler 内部 gson.fromJson 解析请求 JSON,dispatcher 不预解析);并覆盖
 * {@code parseContextUsageRequest} 解析逻辑(逐字搬移自旧 ContextHandlerTest)。
 */
public class GetContextUsageActionHandlerTest {

    private static final Gson GSON = new Gson();

    @Test
    public void bindsGetContextUsageUpstreamActionWithStringPayload() {
        GetContextUsageActionHandler handler = new GetContextUsageActionHandler();
        assertEquals(UpstreamAction.GET_CONTEXT_USAGE, handler.action());
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void parseAllFieldsFromValidRequest() {
        JsonObject body = new JsonObject();
        body.addProperty("sessionId", "sess-123");
        body.addProperty("cwd", "/home/user/project");
        body.addProperty("model", "claude-opus-4-7[1m]");
        body.addProperty("requestId", "req-abc");

        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, body.toString());

        assertEquals("sess-123", result[0]);
        assertEquals("/home/user/project", result[1]);
        assertEquals("claude-opus-4-7[1m]", result[2]);
        assertEquals("req-abc", result[3]);
    }

    @Test
    public void parsePartialRequestWithOnlyModelAndRequestId() {
        JsonObject body = new JsonObject();
        body.addProperty("model", "claude-sonnet-4-6");
        body.addProperty("requestId", "req-xyz");

        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, body.toString());

        assertNull(result[0]); // sessionId
        assertNull(result[1]); // cwd
        assertEquals("claude-sonnet-4-6", result[2]);
        assertEquals("req-xyz", result[3]);
    }

    @Test
    public void parseNullContentReturnsAllNulls() {
        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, null);

        assertNull(result[0]);
        assertNull(result[1]);
        assertNull(result[2]);
        assertNull(result[3]);
    }

    @Test
    public void parseEmptyContentReturnsAllNulls() {
        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, "");

        assertNull(result[0]);
        assertNull(result[1]);
        assertNull(result[2]);
        assertNull(result[3]);
    }

    @Test
    public void parseNullJsonFieldsReturnNull() {
        JsonObject body = new JsonObject();
        body.add("sessionId", null);
        body.addProperty("model", "claude-opus-4-7");
        body.add("requestId", null);

        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, body.toString());

        assertNull(result[0]); // sessionId is null
        assertNull(result[1]); // cwd not present
        assertEquals("claude-opus-4-7", result[2]);
        assertNull(result[3]); // requestId is null
    }

    @Test
    public void parseInvalidJsonReturnsAllNulls() {
        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, "not valid json {{{");

        assertNull(result[0]);
        assertNull(result[1]);
        assertNull(result[2]);
        assertNull(result[3]);
    }

    @Test
    public void parseModelWith1MContextSuffix() {
        // 向后兼容:旧前端可能仍上送带 [1m] 的 model;parseContextUsageRequest 只解析不剥离,
        // [1m] 追加(D5 apply1MSuffix)在 handle 内据 longContextEnabled 意图决定。
        JsonObject body = new JsonObject();
        body.addProperty("model", "claude-opus-4-7[1m]");

        String[] result = GetContextUsageActionHandler.parseContextUsageRequest(GSON, body.toString());

        assertEquals("claude-opus-4-7[1m]", result[2]);
    }

    // D5:longContextEnabled 意图解析(1M 构造下沉后端)。新前端上送 {model, longContextEnabled};
    // 旧前端不发该字段 → false。覆盖 parseLongContextEnabled 契约。
    @Test
    public void parseLongContextEnabledTrue() {
        JsonObject body = new JsonObject();
        body.addProperty("model", "claude-opus-4-7");
        body.addProperty("longContextEnabled", true);

        assertTrue(GetContextUsageActionHandler.parseLongContextEnabled(GSON, body.toString()));
    }

    @Test
    public void parseLongContextEnabledFalse() {
        JsonObject body = new JsonObject();
        body.addProperty("model", "claude-opus-4-7");
        body.addProperty("longContextEnabled", false);

        assertFalse(GetContextUsageActionHandler.parseLongContextEnabled(GSON, body.toString()));
    }

    @Test
    public void parseLongContextEnabledAbsentDefaultsToFalse() {
        JsonObject body = new JsonObject();
        body.addProperty("model", "claude-opus-4-7");

        assertFalse(GetContextUsageActionHandler.parseLongContextEnabled(GSON, body.toString()));
    }

    @Test
    public void parseLongContextEnabledNullAndInvalidContentReturnsFalse() {
        assertFalse(GetContextUsageActionHandler.parseLongContextEnabled(GSON, null));
        assertFalse(GetContextUsageActionHandler.parseLongContextEnabled(GSON, ""));
        assertFalse(GetContextUsageActionHandler.parseLongContextEnabled(GSON, "not valid json {{{"));
    }

    // 旧 ContextHandlerTest.handlerDeclaresGetContextUsageSupport 的等价覆盖:
    // typed handler 以 action() 绑定枚举取代 getSupportedTypes() 字符串声明,
    // 见 bindsGetContextUsageUpstreamActionWithStringPayload。为保留回归对照,显式断言单元素值域。
    @Test
    public void actionValueMatchesLegacyStringType() {
        GetContextUsageActionHandler handler = new GetContextUsageActionHandler();
        assertArrayEquals(new String[]{"get_context_usage"}, new String[]{handler.action().value()});
    }
}
