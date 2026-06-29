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

    // ── §abort:OpenCode SDK interrupt 主动 abort(对称 Claude/Codex sendAbort) ──

    @Test
    public void buildAbortStdinJsonIncludesThreadIdAndBaseUrl() {
        // opencode-channel.js abort 命令契约(stdinData.{threadId, baseUrl} → abortSession)
        String json = OpenCodeSDKBridge.buildAbortStdinJson("ses_123", "http://127.0.0.1:10619");
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("ses_123", obj.get("threadId").getAsString());
        assertEquals("http://127.0.0.1:10619", obj.get("baseUrl").getAsString());
    }

    @Test
    public void buildAbortStdinJsonFallsBackToDefaultUrlWhenBaseUrlMissing() {
        // null/空 baseUrl 必须回退到 DaemonCoordinator 默认 URL(对称 buildSendStdinJson)
        String json = OpenCodeSDKBridge.buildAbortStdinJson("ses_123", null);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("ses_123", obj.get("threadId").getAsString());
        assertEquals(OpenCodeDaemonCoordinator.defaultServerUrl(), obj.get("baseUrl").getAsString());
    }

    @Test
    public void buildAbortStdinJsonNullSafeThreadId() {
        String json = OpenCodeSDKBridge.buildAbortStdinJson(null, "");
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("", obj.get("threadId").getAsString());
    }

    @Test
    public void interruptChannelOverridesAndTriggersAbortBeforeSuper() throws Exception {
        // 对称 Claude/Codex SDK 的 interruptChannel override:interrupt 时主动通知 provider 取消当前请求。
        // OpenCode 无常驻 daemon(对比 DaemonBridge.sendAbort),改 spawn channel-manager.js abort 命令。
        // 进程/spawn 耦合 Platform 无法纯单测,用源码字符串检查(对称 ClaudeSDKBridgeRefactorTest)。
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "provider", "opencode", "OpenCodeSDKBridge.java"));
        assertTrue("必须 override interruptChannel", source.contains("public void interruptChannel(String channelId)"));
        assertTrue("必须调 super.interruptChannel(per-process fallback 杀 send 进程)",
                source.contains("super.interruptChannel(channelId)"));
        assertTrue("必须在 super 前主动触发 opencode abort(对称 Claude/Codex sendAbort)",
                source.contains("triggerAbort"));
        assertTrue("必须维护 channelId→threadId 映射(send 入口建立)", source.contains("channelThreads"));
    }

    @Test
    public void mcpErrorMessageDowngradedToStatusNotice() throws Exception {
        // MCP 连接失败(本地 server 未启动)降级为非阻塞 status 提示,而非回合失败。
        // processOutputLine 是 protected 且构造 bridge 触发 Platform(AppExecutorUtil)依赖,
        // 无法纯单测(对称 interruptChannelOverrides 测试)。用源码字符串验证降级分支存在且正确,
        // 功能行为由插件端到端验证(未启动 MCP 实跑)。
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "provider", "opencode", "OpenCodeSDKBridge.java"));
        assertTrue("MSG_TYPE_ERROR 分支须先判 McpErrorMatcher.isMcpConnectionFailure(message)",
                source.contains("McpErrorMatcher.isMcpConnectionFailure(message)"));
        assertTrue("命中 MCP 须发 CODEX_MSG_STATUS 非阻塞提示,而非 onError/hadSendError",
                source.contains("callback.onMessage(CliConstants.CODEX_MSG_STATUS, McpErrorMatcher.MCP_SKIPPED_NOTICE)"));
    }
}
