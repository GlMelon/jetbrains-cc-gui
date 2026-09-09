package com.github.claudecodegui.cli.codex.appserver;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * CodexAppServerTurn 事件映射单测:app-server 通知(契约经 codex-cli 0.153.4
 * generate-json-schema 校验 + 实测)→ 统一 MSG_* 协议的逐类覆盖,
 * 与 one-shot CodexCliSessionTest / OpenCodeServeTurnTest 对齐。
 * <p>
 * 核心断言:agent 正文与推理摘要均为 token 级增量(item/agentMessage/delta、
 * item/reasoning/*Delta)——这是 app-server 通道存在的目的(exec --json 无 delta)。
 */
public class CodexAppServerTurnTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>(); // {type, content}

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
        }
    }

    private static CodexAppServerTurn.TurnResult await(CodexAppServerTurn turn) throws Exception {
        return turn.completion().get(5, TimeUnit.SECONDS);
    }

    private static long countType(RecordingCallback cb, String type) {
        return cb.messages.stream().filter(m -> m[0].equals(type)).count();
    }

    private static void notify(CodexAppServerTurn turn, String json) {
        turn.onNotification(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    public void agentMessageDeltaEmitsStreamStartOnceAndTokenDeltas() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"i1\",\"delta\":\"Hello\"}}");
        notify(turn, "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"i1\",\"delta\":\" world\"}}");
        notify(turn, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"t1\","
                + "\"turn\":{\"id\":\"u1\",\"status\":\"completed\"}}}");

        CodexAppServerTurn.TurnResult result = await(turn);
        assertEquals(CodexAppServerTurn.OutcomeKind.COMPLETED, result.kind());
        assertEquals("Hello world", turn.accumulatedText());
        assertEquals(1, countType(cb, CliConstants.MSG_STREAM_START));
        assertEquals(2, countType(cb, CliConstants.MSG_CONTENT_DELTA));
        assertEquals("Hello", cb.messages.get(1)[1]);
        assertEquals(" world", cb.messages.get(2)[1]);
        assertEquals(1, countType(cb, CliConstants.MSG_STREAM_END));
        assertEquals(1, countType(cb, CliConstants.MSG_MESSAGE_END));
    }

    @Test
    public void reasoningDeltaEmitsThinkingStartOnceAndThinkingDeltas() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"item/reasoning/summaryTextDelta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"r1\",\"summaryIndex\":0,\"delta\":\"think\"}}");
        notify(turn, "{\"method\":\"item/reasoning/summaryTextDelta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"r1\",\"summaryIndex\":0,\"delta\":\"ing\"}}");
        notify(turn, "{\"method\":\"item/reasoning/textDelta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"r1\",\"contentIndex\":0,\"delta\":\"raw\"}}");

        assertEquals(1, countType(cb, CommonConstants.MSG_TYPE_THINKING));
        assertEquals(3, countType(cb, CliConstants.MSG_THINKING_DELTA));
        // thinking 启动先于首个 delta
        assertTrue(cb.messages.get(0)[0].equals(CommonConstants.MSG_TYPE_THINKING));
    }

    @Test
    public void reasoningCompletedBlockEmitsThinkingWithDedup() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        // 块到达的部署(无 delta 流):item/completed 带 summary 数组 → thinking 输出
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"completedAtMs\":1,\"item\":{\"id\":\"r1\",\"type\":\"reasoning\","
                + "\"summary\":[\"planning the task\"]}}}");
        // 同一 item 再次 completed(重复通知)→ 去重,不重复下发
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"completedAtMs\":2,\"item\":{\"id\":\"r1\",\"type\":\"reasoning\","
                + "\"summary\":[\"planning the task\"]}}}");
        // 第二个独立 reasoning item(新段)→ 全文追加
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"completedAtMs\":3,\"item\":{\"id\":\"r2\",\"type\":\"reasoning\","
                + "\"summary\":[\"choosing tools\"]}}}");
        // summary 为空时回退 content 数组
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"completedAtMs\":4,\"item\":{\"id\":\"r3\",\"type\":\"reasoning\","
                + "\"content\":[\"raw reasoning\"]}}}");

        assertEquals(1, countType(cb, CommonConstants.MSG_TYPE_THINKING));
        List<String[]> thinkingDeltas = cb.messages.stream()
                .filter(m -> m[0].equals(CliConstants.MSG_THINKING_DELTA)).toList();
        assertEquals(3, thinkingDeltas.size());
        assertEquals("planning the task", thinkingDeltas.get(0)[1]);
        assertEquals("choosing tools", thinkingDeltas.get(1)[1]);
        assertEquals("raw reasoning", thinkingDeltas.get(2)[1]);
    }

    @Test
    public void reasoningCompletedBlockSkippedWhenItemAlreadyStreamed() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        // delta 先流式到全文,同 item 的 completed 块直接跳过(块拼接分隔符与 delta 流
        // 换行不一致,前缀去重必然失效,实测会造成整段重复下发)
        notify(turn, "{\"method\":\"item/reasoning/summaryTextDelta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"r1\",\"summaryIndex\":0,\"delta\":\"full text\"}}");
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"completedAtMs\":1,\"item\":{\"id\":\"r1\",\"type\":\"reasoning\","
                + "\"summary\":[\"full text\"]}}}");

        assertEquals(1, countType(cb, CliConstants.MSG_THINKING_DELTA));
        // 序列:thinking 启动 → stream_start → thinking delta
        assertEquals("full text", cb.messages.get(2)[1]);
    }

    @Test
    public void completedAgentMessageReconcilesOnlyTheTail() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"i1\",\"delta\":\"Hello\"}}");
        // completed 全文含 delta 之外的尾巴 → 只补差;全文已被 delta 覆盖 → 不重复
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"item\":{\"id\":\"i1\",\"type\":\"agentMessage\",\"text\":\"Hello world\"}}}");
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"item\":{\"id\":\"i1\",\"type\":\"agentMessage\",\"text\":\"Hello world\"}}}");

        assertEquals(2, countType(cb, CliConstants.MSG_CONTENT_DELTA));
        assertEquals("Hello world", turn.accumulatedText());
    }

    @Test
    public void commandExecutionEmitsToolUseOnceAndToolResultOnCompleted() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"item/started\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\",\"startedAtMs\":1,"
                + "\"item\":{\"id\":\"c1\",\"type\":\"commandExecution\",\"command\":\"ls\"}}}");
        notify(turn, "{\"method\":\"item/started\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\",\"startedAtMs\":1,"
                + "\"item\":{\"id\":\"c1\",\"type\":\"commandExecution\",\"command\":\"ls\"}}}");
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\",\"completedAtMs\":2,"
                + "\"item\":{\"id\":\"c1\",\"type\":\"commandExecution\",\"command\":\"ls\",\"aggregatedOutput\":\"a.txt\",\"exitCode\":0}}}");

        // 工具块对称 one-shot CodexCliSession:tool_use 经 assistantRaw、tool_result 经 userRaw
        List<String[]> uses = cb.messages.stream()
                .filter(m -> m[0].equals(CommonConstants.MSG_TYPE_ASSISTANT)).toList();
        List<String[]> results = cb.messages.stream()
                .filter(m -> m[0].equals(CommonConstants.MSG_TYPE_USER)).toList();
        assertEquals(1, uses.size());
        assertEquals(1, results.size());
        JsonObject use = JsonParser.parseString(uses.get(0)[1]).getAsJsonObject();
        JsonObject block = use.getAsJsonObject("message").getAsJsonArray("content").get(0)
                .getAsJsonObject();
        assertEquals("tool_use", block.get("type").getAsString());
        assertEquals("Bash", block.get("name").getAsString());
        assertEquals("ls", block.getAsJsonObject("input").get("command").getAsString());
        JsonObject result = JsonParser.parseString(results.get(0)[1]).getAsJsonObject();
        JsonObject resultBlock = result.getAsJsonObject("message").getAsJsonArray("content").get(0)
                .getAsJsonObject();
        assertEquals("tool_result", resultBlock.get("type").getAsString());
        assertEquals("c1", resultBlock.get("tool_use_id").getAsString());
        assertEquals("a.txt", resultBlock.get("content").getAsString());
    }

    @Test
    public void mcpToolCallEmitsToolUseOnceAndToolResultOnCompleted() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"item/started\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\",\"startedAtMs\":1,"
                + "\"item\":{\"id\":\"m1\",\"type\":\"mcpToolCall\",\"server\":\"melon_gateway\",\"tool\":\"search\"}}}");
        notify(turn, "{\"method\":\"item/completed\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\",\"completedAtMs\":2,"
                + "\"item\":{\"id\":\"m1\",\"type\":\"mcpToolCall\",\"server\":\"melon_gateway\",\"tool\":\"search\","
                + "\"result\":{\"ok\":true}}}}");

        List<String[]> uses = cb.messages.stream()
                .filter(m -> m[0].equals(CommonConstants.MSG_TYPE_ASSISTANT)).toList();
        List<String[]> results = cb.messages.stream()
                .filter(m -> m[0].equals(CommonConstants.MSG_TYPE_USER)).toList();
        assertEquals(1, uses.size());
        assertEquals(1, results.size());
        JsonObject use = JsonParser.parseString(uses.get(0)[1]).getAsJsonObject();
        JsonObject block = use.getAsJsonObject("message").getAsJsonArray("content").get(0)
                .getAsJsonObject();
        assertEquals("tool_use", block.get("type").getAsString());
        assertEquals("mcp__melon_gateway__search", block.get("name").getAsString());
        JsonObject result = JsonParser.parseString(results.get(0)[1]).getAsJsonObject();
        JsonObject resultBlock = result.getAsJsonObject("message").getAsJsonArray("content").get(0)
                .getAsJsonObject();
        assertEquals("tool_result", resultBlock.get("type").getAsString());
        assertEquals("{\"ok\":true}", resultBlock.get("content").getAsString());
    }

    @Test
    public void tokenUsageEmitsUsageAndResultOncePerTurn() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        String usage = "{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"tokenUsage\":{\"last\":{\"inputTokens\":10,\"outputTokens\":5,\"cachedInputTokens\":2,"
                + "\"cacheWriteInputTokens\":1},\"total\":{\"inputTokens\":10,\"outputTokens\":5,"
                + "\"cachedInputTokens\":2}}}}";
        notify(turn, usage);
        notify(turn, usage);

        assertEquals(1, countType(cb, CliConstants.MSG_USAGE));
        assertEquals(1, countType(cb, CliConstants.MSG_RESULT));
        JsonObject result = JsonParser.parseString(
                cb.messages.stream().filter(m -> m[0].equals(CliConstants.MSG_RESULT))
                        .findFirst().orElseThrow(() -> new AssertionError("result missing"))[1])
                .getAsJsonObject();
        JsonObject mapped = result.getAsJsonObject("usage");
        assertEquals(10, mapped.get("input_tokens").getAsInt());
        assertEquals(5, mapped.get("output_tokens").getAsInt());
        assertEquals(2, mapped.get("cache_read_input_tokens").getAsInt());
        assertEquals(1, mapped.get("cache_creation_input_tokens").getAsInt());
    }

    @Test
    public void turnCompletedInterruptedMapsToInterrupted() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"i1\",\"delta\":\"partial\"}}");
        notify(turn, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"t1\","
                + "\"turn\":{\"id\":\"u1\",\"status\":\"interrupted\"}}}");

        CodexAppServerTurn.TurnResult result = await(turn);
        assertEquals(CodexAppServerTurn.OutcomeKind.INTERRUPTED, result.kind());
        assertTrue(turn.wasInterrupted());
        assertNull(result.error());
    }

    @Test
    public void turnCompletedFailedCarriesErrorMessage() throws Exception {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"t1\","
                + "\"turn\":{\"id\":\"u1\",\"status\":\"failed\",\"error\":{\"message\":\"boom\"}}}}");

        CodexAppServerTurn.TurnResult result = await(turn);
        assertEquals(CodexAppServerTurn.OutcomeKind.FAILED, result.kind());
        assertEquals("boom", result.error());
    }

    @Test
    public void processDiedFailsTurnUnlessInterrupted() throws Exception {
        CodexAppServerTurn plain = new CodexAppServerTurn(new RecordingCallback());
        plain.onProcessDied("eof");
        assertEquals(CodexAppServerTurn.OutcomeKind.FAILED, await(plain).kind());

        CodexAppServerTurn interrupted = new CodexAppServerTurn(new RecordingCallback());
        interrupted.markInterrupted();
        interrupted.onProcessDied("eof");
        assertEquals(CodexAppServerTurn.OutcomeKind.INTERRUPTED, await(interrupted).kind());
    }

    @Test
    public void noiseNotificationsAreIgnored() {
        RecordingCallback cb = new RecordingCallback();
        CodexAppServerTurn turn = new CodexAppServerTurn(cb);

        notify(turn, "{\"method\":\"thread/started\",\"params\":{\"threadId\":\"t1\",\"thread\":{\"id\":\"t1\"}}}");
        notify(turn, "{\"method\":\"turn/started\",\"params\":{\"threadId\":\"t1\",\"turn\":{\"id\":\"u1\"}}}");
        notify(turn, "{\"method\":\"mcpServer/startupStatus/updated\",\"params\":{\"threadId\":\"t1\"}}");
        notify(turn, "{\"method\":\"account/rateLimits/updated\",\"params\":{}}");
        notify(turn, "{\"method\":\"item/plan/delta\",\"params\":{\"threadId\":\"t1\",\"turnId\":\"u1\","
                + "\"itemId\":\"p1\",\"delta\":\"plan\"}}");

        assertTrue(cb.messages.isEmpty());
        assertFalse(turn.completion().isDone());
    }
}
