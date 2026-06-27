package com.github.claudecodegui.provider.opencode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * §15.7 B11:OpenCodeSDKBridge.buildSendStdinJson 字段完整性。
 * stdin 须含 7+ 字段:message/threadId/cwd/permissionMode/model/reasoningEffort/attachments/baseUrl,
 * 与 channel.js → message-service.js 的 sendMessage(params) 契约对齐。
 *
 * 注:buildSendStdinJson 为 static 纯函数,无需构造 bridge(避免 BaseSDKBridge 的 Platform 依赖)。
 * buildSendCommand 依赖 nodeDetector(Platform),由集成测试覆盖,不在此单测。
 */
public class OpenCodeSDKBridgeTest {

    @Test
    public void buildSendStdinJsonIncludesAllEightFields() {
        String json = OpenCodeSDKBridge.buildSendStdinJson(
                "hello", "ses_123", "/tmp/proj", "bypassPermissions",
                "opencode/mimo-v2.5-free", "high", null, "http://127.0.0.1:4096");
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("hello", obj.get("message").getAsString());
        assertEquals("ses_123", obj.get("threadId").getAsString());
        assertEquals("/tmp/proj", obj.get("cwd").getAsString());
        assertEquals("bypassPermissions", obj.get("permissionMode").getAsString());
        assertEquals("opencode/mimo-v2.5-free", obj.get("model").getAsString());
        assertEquals("high", obj.get("reasoningEffort").getAsString());
        assertEquals("http://127.0.0.1:4096", obj.get("baseUrl").getAsString());
        assertTrue("attachments must be present as array", obj.has("attachments") && obj.get("attachments").isJsonArray());
    }

    @Test
    public void buildSendStdinJsonNullSafeDefaults() {
        // null 参数不得抛异常,字段以安全默认值(空串/空数组)填充
        String json = OpenCodeSDKBridge.buildSendStdinJson(null, null, null, null, null, null, null, null);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("", obj.get("message").getAsString());
        assertEquals("", obj.get("threadId").getAsString());
        assertEquals("", obj.get("model").getAsString());
        assertEquals("null baseUrl 必须回退到 DaemonCoordinator 默认 URL",
                "http://127.0.0.1:4096", obj.get("baseUrl").getAsString());
        assertNotNull(obj.get("attachments"));
        assertTrue(obj.get("attachments").isJsonArray());
    }

    @Test
    public void buildListModelsStdinJsonPassesBaseUrl() {
        // §15.8 §11:listModels stdin 仅 baseUrl 字段,显式值原样透传
        String json = OpenCodeSDKBridge.buildListModelsStdinJson("http://127.0.0.1:14096");
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("http://127.0.0.1:14096", obj.get("baseUrl").getAsString());
        assertEquals("listModels stdin 仅含 baseUrl 一个字段", 1, obj.size());
    }

    @Test
    public void buildListModelsStdinJsonNullSafeDefaultsToDaemonUrl() {
        // null/空 baseUrl 回退 DaemonCoordinator 默认 URL(测试与 serve 不可用场景)
        String jsonNull = OpenCodeSDKBridge.buildListModelsStdinJson(null);
        assertEquals("http://127.0.0.1:4096",
                JsonParser.parseString(jsonNull).getAsJsonObject().get("baseUrl").getAsString());
        String jsonBlank = OpenCodeSDKBridge.buildListModelsStdinJson("   ");
        assertEquals("空/空白 baseUrl 也回退默认 URL", "http://127.0.0.1:4096",
                JsonParser.parseString(jsonBlank).getAsJsonObject().get("baseUrl").getAsString());
    }
}
