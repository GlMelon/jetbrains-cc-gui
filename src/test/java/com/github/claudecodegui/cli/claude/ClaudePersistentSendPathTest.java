package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliAttachmentHandler;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliPersistentProcess;
import com.github.claudecodegui.provider.common.CliResult;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ClaudePersistentSendPath} pure logic: interrupt protocol line,
 * stream-json user message line construction, and turn handler result semantics.
 *
 * <p>These pin the provider-side protocol contract that {@code CliPersistentProcess} itself
 * must not know: the interrupt control_request shape lives here and only here.
 */
public class ClaudePersistentSendPathTest {

    private static final String TAB_ID = "tab-persistent-path-test";

    private ClaudeCliSession session;
    private ClaudePersistentSendPath path;

    /** 记录式回调:收集全部回调调用供断言。 */
    private static final class RecordingCallback implements CliSessionCallback {
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> completions = new CopyOnWriteArrayList<>();
        final List<String> interrupts = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messages.add(type + ":" + content);
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }

        @Override
        public void onComplete(boolean success, String finalResult, String error) {
            completions.add(success + "|" + finalResult + "|" + error);
        }

        @Override
        public void onInterrupted(String finalResult, String message) {
            interrupts.add(finalResult + "|" + message);
        }
    }

    @Before
    public void setUp() {
        session = new ClaudeCliSession(TAB_ID);
        path = new ClaudePersistentSendPath(session);
    }

    private static JsonObject parse(String line) {
        return GsonHolder.GSON.fromJson(line, JsonObject.class);
    }

    // ── interrupt 协议行(V1 定稿格式) ──────────────────────────────────

    @Test
    public void buildInterruptRequestMatchesControlRequestContract() {
        String line = ClaudePersistentSendPath.buildInterruptRequest();
        JsonObject obj = parse(line);
        assertEquals("control_request", obj.get("type").getAsString());
        // subtype 必须嵌在 request 对象内(顶层平铺会触发 CLI 解析报错)
        JsonObject request = obj.getAsJsonObject("request");
        assertEquals("interrupt", request.get("subtype").getAsString());
        // request_id 为合法 UUID 且每次生成新值(中断回执匹配用)
        assertTrue(obj.get("request_id").getAsString().matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"));
        assertNotEquals(line, ClaudePersistentSendPath.buildInterruptRequest());
    }

    // ── stdin user 消息行 ─────────────────────────────────────────────────────

    @Test
    public void buildUserMessageLineWrapsPromptAsUserMessage() {
        String line = path.buildUserMessageLine("hello world", List.of());
        JsonObject wrapper = parse(line);
        assertEquals("user", wrapper.get("type").getAsString());
        JsonObject message = wrapper.getAsJsonObject("message");
        assertEquals("user", message.get("role").getAsString());
        JsonArray content = message.getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject textBlock = content.get(0).getAsJsonObject();
        assertEquals("text", textBlock.get("type").getAsString());
        assertEquals("hello world", textBlock.get("text").getAsString());
    }

    @Test
    public void buildUserMessageLineNullPromptBecomesEmptyText() {
        JsonObject textBlock = parse(path.buildUserMessageLine(null, List.of()))
                .getAsJsonObject("message").getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("text", textBlock.get("type").getAsString());
        assertEquals("", textBlock.get("text").getAsString());
    }

    @Test
    public void buildUserMessageLineAppendsNativeImageBlock() throws Exception {
        File image = File.createTempFile("aicg-persistent-img", ".png");
        byte[] bytes = {1, 2, 3, 4};
        Files.write(image.toPath(), bytes);
        try {
            CliAttachmentHandler.ContentBlock imageBlock =
                    new CliAttachmentHandler.ContentBlock(CliAttachmentHandler.ContentBlock.Kind.IMAGE,
                            "image/png", image, null);
            CliAttachmentHandler.ContentBlock textBlock =
                    new CliAttachmentHandler.ContentBlock(CliAttachmentHandler.ContentBlock.Kind.TEXT,
                            null, null, "already merged into prompt");

            JsonArray content = parse(path.buildUserMessageLine("see image", List.of(imageBlock, textBlock)))
                    .getAsJsonObject("message").getAsJsonArray("content");

            assertEquals(2, content.size());
            assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
            JsonObject imageJson = content.get(1).getAsJsonObject();
            assertEquals("image", imageJson.get("type").getAsString());
            JsonObject source = imageJson.getAsJsonObject("source");
            assertEquals("base64", source.get("type").getAsString());
            assertEquals("image/png", source.get("media_type").getAsString());
            assertEquals(Base64.getEncoder().encodeToString(bytes), source.get("data").getAsString());
        } finally {
            Files.deleteIfExists(image.toPath());
        }
    }

    // ── 轮事件适配(TurnLineHandler) ────────────────────────────────────────

    /** 未启动的长驻进程句柄:仅承载 session_id 回填元数据,无 I/O。 */
    private CliPersistentProcess detachedProcess() {
        return new CliPersistentProcess("claude", TAB_ID);
    }

    @Test
    public void turnHandlerIgnoresBlankAndNonJsonLines() {
        RecordingCallback callback = new RecordingCallback();
        ClaudePersistentSendPath.TurnContext context =
                path.createTurnContext(callback, detachedProcess());
        assertNull(context.handler.onLine(null, false));
        assertNull(context.handler.onLine("", false));
        assertNull(context.handler.onLine("not-json-noise", false));
    }

    @Test
    public void turnHandlerReturnsNullForNonResultLinesAndCapturesSessionId() {
        RecordingCallback callback = new RecordingCallback();
        CliPersistentProcess process = detachedProcess();
        ClaudePersistentSendPath.TurnContext context = path.createTurnContext(callback, process);

        // system/init 行:轮继续(返回 null),session_id 回填 session 与进程元数据
        assertNull(context.handler.onLine(
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"11111111-2222-3333-4444-555555555555\"}",
                false));
        assertEquals("11111111-2222-3333-4444-555555555555", session.getSessionId());
        assertEquals("11111111-2222-3333-4444-555555555555", process.sessionId());

        // stream_event 三段式(text 块 start→delta→stop):轮继续,文本累积进 assistantContent
        assertNull(context.handler.onLine(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\"}}}",
                false));
        assertNull(context.handler.onLine(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}}",
                false));
        assertNull(context.handler.onLine(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":0}}",
                false));
        assertTrue(context.assistantText().contains("partial"));
    }

    @Test
    public void turnHandlerCompletesTurnOnResultLine() {
        RecordingCallback callback = new RecordingCallback();
        ClaudePersistentSendPath.TurnContext context =
                path.createTurnContext(callback, detachedProcess());

        CliResult result = context.handler.onLine(
                "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"final answer\","
                        + "\"is_error\":false,\"session_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}",
                false);

        assertNotNull(result);
        assertTrue(result.success);
        assertFalse(result.interrupted);
        // onComplete 恰好一次,成功收尾
        assertEquals(1, callback.completions.size());
        assertTrue(callback.completions.get(0).startsWith("true|"));
        assertTrue(callback.interrupts.isEmpty());
    }

    @Test
    public void turnHandlerMapsInterruptedResultToInterruptedSemantics() {
        RecordingCallback callback = new RecordingCallback();
        ClaudePersistentSendPath.TurnContext context =
                path.createTurnContext(callback, detachedProcess());

        // 被中断轮以 result 收尾:interrupted 标记优先于 result 内容语义
        CliResult result = context.handler.onLine(
                "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                        + "\"session_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}",
                true);

        assertNotNull(result);
        assertFalse(result.success);
        assertTrue(result.interrupted);
        assertEquals(1, callback.interrupts.size());
        assertTrue(callback.completions.isEmpty());
    }

    @Test
    public void turnHandlerErrorResultCompletesWithFailure() {
        RecordingCallback callback = new RecordingCallback();
        ClaudePersistentSendPath.TurnContext context =
                path.createTurnContext(callback, detachedProcess());

        CliResult result = context.handler.onLine(
                "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,"
                        + "\"session_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}",
                false);

        assertNotNull(result);
        assertFalse(result.success);
        // 失败收尾:错误经 onComplete(false, null, error) 交付(与 one-shot result 事件口径一致)
        assertEquals(1, callback.completions.size());
        assertTrue(callback.completions.get(0).startsWith("false|"));
    }

    @Test
    public void turnHandlerSurfacesApiRetryAsStatusNotice() {
        RecordingCallback callback = new RecordingCallback();
        ClaudePersistentSendPath.TurnContext context =
                path.createTurnContext(callback, detachedProcess());

        // api_retry(CLI 对 5xx/529 过载的静默指数退避):必须转成 status 非阻塞提示,
        // 否则前端停在"正在理解问题"毫无感知(2026-08-18 BigModel 529 实测)。
        // 2026-08-21:改为 per-attempt 下发,每次重试都刷新顶部状态卡"正在重连 N/M"。
        assertNull(context.handler.onLine(
                "{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":1,\"max_retries\":10,"
                        + "\"retry_delay_ms\":531,\"error_status\":529,\"error\":\"overloaded\","
                        + "\"session_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}",
                false));
        assertTrue(callback.messages.stream().anyMatch(m ->
                m.startsWith("status:") && m.contains("529") && m.contains("1/10")));
        // phase 通道编码 attempt/max(api_retry:1:10),透传给 handler 构造带计数的状态卡
        assertTrue(callback.messages.stream().anyMatch(m ->
                m.equals("response_phase:api_retry:1:10")));

        // attempt 递增:再次重试必须再次下发,使顶部状态卡持续刷新重试计数
        context.handler.onLine(
                "{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":2,\"max_retries\":10,"
                        + "\"error_status\":529,\"error\":\"overloaded\"}",
                false);
        assertEquals(2, callback.messages.stream().filter(m -> m.startsWith("status:")).count());
        assertTrue(callback.messages.stream().anyMatch(m ->
                m.equals("response_phase:api_retry:2:10")));

        // 同一 attempt 连发不重复刷屏(per-attempt 去重防连发)
        context.handler.onLine(
                "{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":2,\"max_retries\":10,"
                        + "\"error_status\":529,\"error\":\"overloaded\"}",
                false);
        assertEquals(2, callback.messages.stream().filter(m -> m.startsWith("status:")).count());
    }

    @Test
    public void producedOutputReflectsAssistantContentAndError() {
        RecordingCallback callback = new RecordingCallback();
        ClaudePersistentSendPath.TurnContext context =
                path.createTurnContext(callback, detachedProcess());

        assertFalse(context.producedOutput());
        context.handler.onLine(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\"}}}",
                false);
        context.handler.onLine(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}}",
                false);
        context.handler.onLine(
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":0}}",
                false);
        assertTrue(context.producedOutput());
    }
}
