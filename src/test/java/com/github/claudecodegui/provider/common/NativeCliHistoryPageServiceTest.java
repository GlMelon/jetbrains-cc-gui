package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.protocol.CodexHistoryPageMode;
import com.github.claudecodegui.protocol.payload.CodexHistoryPageInfoPayloadField;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 纯 CLI provider(grok/kimi/pi)共用分页服务测试。
 *
 * <p>slice 数值语义(边界/clamp/退化场景)已由 {@code ClaudeHistoryPageServiceTest} /
 * {@code CodexHistoryPageServiceTest} 覆盖(实现单点委托 {@link SessionHistoryPageSlicer});
 * 本测试聚焦三家接入特有的关注点:reader 注入传参、真实消息形状的轮边界判定
 * (human user = 非空 content;tool 承载消息 raw.content 纯 tool_result 块,不算轮)、
 * pageInfo 关键字段。
 */
public class NativeCliHistoryPageServiceTest {

    @Test
    public void readerReceivesSessionIdAndCwd() {
        AtomicReference<String> seenSessionId = new AtomicReference<>();
        AtomicReference<String> seenCwd = new AtomicReference<>();
        NativeCliHistoryPageService service = new NativeCliHistoryPageService((sessionId, cwd) -> {
            seenSessionId.set(sessionId);
            seenCwd.set(cwd);
            return new ArrayList<>();
        });

        service.loadInitialPage("session-1", "/tmp/proj");

        assertEquals("session-1", seenSessionId.get());
        assertEquals("/tmp/proj", seenCwd.get());
    }

    @Test
    public void initialPageReturnsLastTurnsWithPageInfoForNativeCliShapes() {
        NativeCliHistoryPageService service = serviceWithPageSize(2);
        // 4 轮(u0..u3),每轮 user + assistant + tool 承载消息 → 最后 2 轮 = u2 起。
        List<JsonObject> messages = fourTurns();

        SessionHistoryLoadResult result = service.slice(messages, "session-1", null, CodexHistoryPageMode.REPLACE);

        assertEquals(6, result.messages().size());
        assertEquals("u2", result.messages().get(0).get("content").getAsString());
        assertEquals("r3", result.messages().get(5).get("raw").getAsJsonObject().get("content")
                .getAsJsonArray().get(0).getAsJsonObject().get("content").getAsString());

        JsonObject info = result.pageInfo();
        assertEquals(4, info.get(CodexHistoryPageInfoPayloadField.TOTAL_TURNS.wireKey()).getAsInt());
        assertEquals(2, info.get(CodexHistoryPageInfoPayloadField.FROM_TURN.wireKey()).getAsInt());
        assertTrue(info.get(CodexHistoryPageInfoPayloadField.HAS_MORE.wireKey()).getAsBoolean());
        assertEquals("session-1", info.get(CodexHistoryPageInfoPayloadField.SESSION_ID.wireKey()).getAsString());
        assertEquals(CodexHistoryPageMode.REPLACE.value(),
                info.get(CodexHistoryPageInfoPayloadField.MODE.wireKey()).getAsString());
    }

    @Test
    public void earlierPageStopsAtRequestedTurn() {
        NativeCliHistoryPageService service = serviceWithPageSize(2);
        List<JsonObject> messages = fourTurns();

        // beforeTurn=2 → 只取 u0/a0/r0、u1/a1/r1 两轮,fromTurn=0 → hasMore=false。
        SessionHistoryLoadResult result = service.slice(messages, "session-1", 2, CodexHistoryPageMode.PREPEND);

        assertEquals(6, result.messages().size());
        assertEquals("u0", result.messages().get(0).get("content").getAsString());
        assertEquals("r1", result.messages().get(5).get("raw").getAsJsonObject().get("content")
                .getAsJsonArray().get(0).getAsJsonObject().get("content").getAsString());
        JsonObject info = result.pageInfo();
        assertEquals(2, info.get(CodexHistoryPageInfoPayloadField.TO_TURN.wireKey()).getAsInt());
        assertFalse(info.get(CodexHistoryPageInfoPayloadField.HAS_MORE.wireKey()).getAsBoolean());
    }

    @Test
    public void pageSizeIsClampedToAtLeastOne() {
        NativeCliHistoryPageService service = new NativeCliHistoryPageService(
                (sessionId, cwd) -> {
                    List<JsonObject> messages = new ArrayList<>();
                    messages.add(nativeUser("u0"));
                    messages.add(nativeUser("u1"));
                    return messages;
                }, 0);

        SessionHistoryLoadResult result = service.loadInitialPage("session-1", "/tmp/proj");

        assertEquals(1, result.messages().size());
        assertEquals("u1", result.messages().get(0).get("content").getAsString());
    }

    private static NativeCliHistoryPageService serviceWithPageSize(int pageSize) {
        return new NativeCliHistoryPageService((sessionId, cwd) -> fourTurns(), pageSize);
    }

    private static List<JsonObject> fourTurns() {
        List<JsonObject> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            messages.add(nativeUser("u" + i));
            messages.add(assistant("a" + i));
            messages.add(toolResultCarrier("r" + i));
        }
        return messages;
    }

    /** grok/kimi/pi 的 human user 形状:type=user + 非空 content 字符串(无 raw)。 */
    private static JsonObject nativeUser(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "user");
        message.addProperty("content", text);
        return message;
    }

    private static JsonObject assistant(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "assistant");
        message.addProperty("content", text);
        return message;
    }

    /** tool 承载消息形状:type=user + 空 content + raw.content=[{type:"tool_result"}](不算轮边界)。 */
    private static JsonObject toolResultCarrier(String text) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "user");
        message.addProperty("content", "");
        JsonObject raw = new JsonObject();
        JsonArray blocks = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_result");
        block.addProperty("content", text);
        blocks.add(block);
        raw.add("content", blocks);
        message.add("raw", raw);
        return message;
    }
}
