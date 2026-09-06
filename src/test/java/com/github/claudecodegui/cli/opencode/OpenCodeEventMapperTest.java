package com.github.claudecodegui.cli.opencode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * OpenCodeEventMapper 纯函数单测:one-shot 解析器与 serve SSE 通道共用的映射
 * (增量去重 / usage 归一 / tool 块构造 / 错误消息提取)行为锚定,防两通道漂移。
 */
public class OpenCodeEventMapperTest {

    // ── deltaOf(累积式文本增量去重) ───────────────────────────────────────────

    @Test
    public void deltaOfTakesSuffixForCumulativeText() {
        assertEquals("llo", OpenCodeEventMapper.deltaOf("he", "hello"));
        assertEquals("hello", OpenCodeEventMapper.deltaOf("", "hello"));
        assertEquals("hello", OpenCodeEventMapper.deltaOf(null, "hello"));
    }

    @Test
    public void deltaOfReturnsNullWhenNothingNew() {
        assertNull(OpenCodeEventMapper.deltaOf("same", "same"));
        assertNull(OpenCodeEventMapper.deltaOf("same", ""));
        assertNull(OpenCodeEventMapper.deltaOf("same", null));
    }

    @Test
    public void deltaOfEmitsWholeTextWhenNotPrefix() {
        // 非累积/重置:整体下发
        assertEquals("fresh", OpenCodeEventMapper.deltaOf("stale", "fresh"));
    }

    // ── buildUsage(tokens 归一) ───────────────────────────────────────────────

    @Test
    public void buildUsageNormalizesTokenSchema() {
        JsonObject tokens = JsonParser.parseString(
                "{\"input\":1200,\"output\":340,\"reasoning\":55,"
                        + "\"cache\":{\"read\":100,\"write\":20}}").getAsJsonObject();
        JsonObject usage = OpenCodeEventMapper.buildUsage(tokens);
        assertEquals(1200, usage.get("input_tokens").getAsInt());
        assertEquals(340, usage.get("output_tokens").getAsInt());
        assertEquals(100, usage.get("cache_read_input_tokens").getAsInt());
        assertEquals(20, usage.get("cache_creation_input_tokens").getAsInt());
    }

    @Test
    public void buildUsageToleratesMissingCache() {
        JsonObject tokens = JsonParser.parseString("{\"input\":5,\"output\":7}").getAsJsonObject();
        JsonObject usage = OpenCodeEventMapper.buildUsage(tokens);
        assertEquals(5, usage.get("input_tokens").getAsInt());
        assertEquals(7, usage.get("output_tokens").getAsInt());
        assertEquals(0, usage.get("cache_read_input_tokens").getAsInt());
    }

    // ── tool 块构造(tool part 同构,one-shot 与 serve 共用) ────────────────────

    @Test
    public void toolBlocksPairByCallId() {
        JsonObject part = JsonParser.parseString(
                "{\"id\":\"prt_1\",\"callID\":\"call_42\",\"tool\":\"bash\","
                        + "\"state\":{\"status\":\"completed\",\"input\":{\"cmd\":\"ls\"},"
                        + "\"output\":\"file.txt\"}}").getAsJsonObject();
        JsonObject toolUse = OpenCodeEventMapper.buildToolUseBlock(part);
        JsonObject toolResult = OpenCodeEventMapper.buildToolResultBlock(part);
        assertEquals("tool_use", toolUse.get("type").getAsString());
        assertEquals("call_42", toolUse.get("id").getAsString());
        assertEquals("bash", toolUse.get("name").getAsString());
        assertEquals("ls", toolUse.getAsJsonObject("input").get("cmd").getAsString());
        assertEquals("tool_result", toolResult.get("type").getAsString());
        assertEquals("call_42", toolResult.get("tool_use_id").getAsString());
        assertFalse(toolResult.get("is_error").getAsBoolean());
        assertEquals("file.txt", toolResult.get("content").getAsString());
    }

    @Test
    public void toolResultFallsBackToPartIdAndMarksErrorState() {
        JsonObject part = JsonParser.parseString(
                "{\"id\":\"prt_9\",\"tool\":\"read\","
                        + "\"state\":{\"status\":\"error\",\"input\":{}}}").getAsJsonObject();
        JsonObject toolUse = OpenCodeEventMapper.buildToolUseBlock(part);
        JsonObject toolResult = OpenCodeEventMapper.buildToolResultBlock(part);
        assertEquals("prt_9", toolUse.get("id").getAsString());
        assertEquals("prt_9", toolResult.get("tool_use_id").getAsString());
        assertTrue(toolResult.get("is_error").getAsBoolean());
        assertEquals("(running)", toolResult.get("content").getAsString());
    }

    // ── extractErrorMessage ──────────────────────────────────────────────────

    @Test
    public void extractErrorMessagePrefersNestedDataMessage() {
        JsonObject event = JsonParser.parseString(
                "{\"error\":{\"name\":\"ProviderError\",\"data\":{\"message\":\"boom\"}}}")
                .getAsJsonObject();
        assertEquals("boom", OpenCodeEventMapper.extractErrorMessage(event));
    }

    @Test
    public void extractErrorMessageFallsBackToRawJson() {
        JsonObject event = JsonParser.parseString("{\"unexpected\":true}").getAsJsonObject();
        assertTrue(OpenCodeEventMapper.extractErrorMessage(event).contains("unexpected"));
    }
}
