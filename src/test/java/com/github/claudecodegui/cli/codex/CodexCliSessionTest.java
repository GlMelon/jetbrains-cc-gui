package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.cli.CliSessionCallback;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexCliSessionTest {

    @Test
    public void reasoningItemCompletedEmitsThinkingDelta() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-1");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"reasoning\",\"text\":\"Searching\"}}",
                callback,
                new StringBuilder()
        );

        assertTrue(callback.events.stream().anyMatch(event -> "thinking_delta".equals(event.type) && "Searching".equals(event.content)));
    }

    @Test
    public void reasoningItemSummaryEmitsThinkingDelta() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-summary");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"reasoning\",\"summary\":\"Searching\"}}",
                callback,
                new StringBuilder()
        );

        assertTrue(callback.events.stream().anyMatch(event -> "thinking_delta".equals(event.type) && "Searching".equals(event.content)));
    }

    @Test
    public void reasoningItemEmitsThinkingStartBeforeDelta() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-thinking");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\",\"text\":\"first\"}}",
                callback,
                new StringBuilder()
        );

        // First reasoning event should emit both "thinking" start and "thinking_delta"
        int thinkingStartIndex = -1;
        int thinkingDeltaIndex = -1;
        for (int i = 0; i < callback.events.size(); i++) {
            Event e = callback.events.get(i);
            if ("thinking".equals(e.type) && thinkingStartIndex == -1) thinkingStartIndex = i;
            if ("thinking_delta".equals(e.type) && thinkingDeltaIndex == -1) thinkingDeltaIndex = i;
        }
        assertTrue("Should emit 'thinking' start signal", thinkingStartIndex >= 0);
        assertTrue("Should emit 'thinking_delta'", thinkingDeltaIndex >= 0);
        assertTrue("'thinking' should come before 'thinking_delta'", thinkingStartIndex < thinkingDeltaIndex);
    }

    @Test
    public void reasoningItemEmitsThinkingStartOnlyOnce() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-thinking-once");
        RecordingCallback callback = new RecordingCallback();

        // First update
        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"r2\",\"type\":\"reasoning\",\"text\":\"hello\"}}",
                callback,
                new StringBuilder()
        );
        // Second update (same ID)
        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"r2\",\"type\":\"reasoning\",\"text\":\"hello world\"}}",
                callback,
                new StringBuilder()
        );

        long thinkingStartCount = callback.events.stream().filter(e -> "thinking".equals(e.type)).count();
        assertEquals("'thinking' start signal should be emitted exactly once", 1, thinkingStartCount);
    }

    @Test
    public void turnCompletedWithReasoningTokensButNoReasoningItemEmitsThinkingPlaceholder() throws Exception {
        // 复现:gpt-5.5 经第三方代理 API 不返回可读 reasoning(codex CLI --json 不发 reasoning item),
        // 但 turn.completed.usage.reasoning_output_tokens > 0 证实模型确实思考过。
        // 期望:发一条占位思考(thinkingStart + thinkingDelta 含 token 数),让思考区不致空白。
        CodexCliSession session = new CodexCliSession("tab-placeholder");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(session, "{\"type\":\"turn.started\"}", callback, new StringBuilder());
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"391\"}}",
                callback,
                new StringBuilder()
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"reasoning_output_tokens\":115}}",
                callback,
                new StringBuilder()
        );

        assertTrue("reasoning_output_tokens>0 且无 reasoning item 时应发 thinking_start",
                callback.events.stream().anyMatch(event -> "thinking".equals(event.type)));
        assertTrue("应发占位 thinking_delta 并含 token 数",
                callback.events.stream().anyMatch(event -> "thinking_delta".equals(event.type) && event.content.contains("115")));
    }

    @Test
    public void turnCompletedWithRealReasoningItemSuppressesPlaceholder() throws Exception {
        // 有真实 reasoning item 时,turn 结束不重复发占位(避免双份思考块)。
        CodexCliSession session = new CodexCliSession("tab-no-placeholder");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(session, "{\"type\":\"turn.started\"}", callback, new StringBuilder());
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\",\"text\":\"真实推理内容\"}}",
                callback,
                new StringBuilder()
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"reasoning_output_tokens\":50}}",
                callback,
                new StringBuilder()
        );

        assertEquals("有真实 reasoning 时只发 1 条 thinking_delta(真实内容),不叠加占位",
                1, callback.events.stream().filter(event -> "thinking_delta".equals(event.type)).count());
        assertTrue("真实推理内容应被推送",
                callback.contentsOfType("thinking_delta").contains("真实推理内容"));
    }

    @Test
    public void turnCompletedWithoutReasoningTokensDoesNotEmitPlaceholder() throws Exception {
        // reasoning_output_tokens=0(模型未思考)不发占位,思考区保持空。
        CodexCliSession session = new CodexCliSession("tab-no-reasoning-tokens");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(session, "{\"type\":\"turn.started\"}", callback, new StringBuilder());
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"嗨\"}}",
                callback,
                new StringBuilder()
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"reasoning_output_tokens\":0}}",
                callback,
                new StringBuilder()
        );

        assertFalse("reasoning_output_tokens=0 不发 thinking",
                callback.events.stream().anyMatch(event -> "thinking".equals(event.type)));
        assertFalse("reasoning_output_tokens=0 不发 thinking_delta",
                callback.events.stream().anyMatch(event -> "thinking_delta".equals(event.type)));
    }

    @Test
    public void agentMessageUpdatesStreamAsContentDeltaImmediately() throws Exception {
        // 官方 codex exec --json 契约:agent_message 通过 item.updated 多次累积发送,
        // 必须立即流式推送增量(content_delta),而非缓冲到 turn.completed 一次性输出。
        // 复现用户报告:codex 无流式,回答完后由后端一次性推送。
        CodexCliSession session = new CodexCliSession("tab-agent");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"item_msg\",\"type\":\"agent_message\",\"text\":\"hello\"}}",
                callback,
                assistantContent
        );
        // 第一个增量必须立即流式,不等 turn.completed
        // WARNING:若改为 assertEquals("", assistantContent) + assertEquals(List.of(), content_delta)
        //   即在验证"缓冲到 turn 结束",那是 2951c5f2 的旧行为=断流式 bug。
        assertEquals("hello", assistantContent.toString());
        assertEquals(List.of("hello"), callback.contentsOfType("content_delta"));

        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_msg\",\"type\":\"agent_message\",\"text\":\"hello world\"}}",
                callback,
                assistantContent
        );
        // 第二次只推尾部增量,不重复推送整段
        assertEquals("hello world", assistantContent.toString());
        assertEquals(List.of("hello", " world"), callback.contentsOfType("content_delta"));

        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                callback,
                assistantContent
        );

        // turn.completed 不再额外 flush(已流式),assistantContent 保持不变
        // WARNING:若改回 assertEquals(List.of("hello world"), content_delta)即在验证
        //   turn.completed 才一次性推送,那是缓冲设计的特征,将间接允许回滚流式。
        assertEquals("hello world", assistantContent.toString());
        assertEquals(List.of("hello", " world"), callback.contentsOfType("content_delta"));
    }

    @Test
    public void agentMessageBeforeToolStreamsAsAssistantContentNotThinking() throws Exception {
        // agent_message 语义即 assistant 正文,即使后续紧跟工具调用,也作为正文流式输出,
        // 不因"工具前协调文本"降级为 thinking(2951c5f2 的过度设计已废弃,行为对齐 Claude CLI)。
        //
        // WARNING:若改回 assertEquals("", assistantContent) + assertEquals(List.of(), content_delta)
        //   + assertEquals(List.of(text), thinking_delta) 即在验证"agent_message 降级 thinking"
        //   旧行为,将间接允许回滚流式(2951c5f2 的设计,断流式回归)。
        CodexCliSession session = new CodexCliSession("tab-agent-different-id");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"item_stream\",\"type\":\"agent_message\",\"text\":\"Wall time: 3.6 seconds\\nvitest failed\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"status\":\"in_progress\"}}",
                callback,
                assistantContent
        );

        assertEquals("Wall time: 3.6 seconds\nvitest failed", assistantContent.toString());
        assertEquals(List.of("Wall time: 3.6 seconds\nvitest failed"), callback.contentsOfType("content_delta"));
        assertEquals(List.of(), callback.contentsOfType("thinking_delta"));
        assertTrue(callback.events.stream().anyMatch(event -> "assistant".equals(event.type)
                && event.content.contains("\"type\":\"tool_use\"")
                && event.content.contains("git status")));
    }

    @Test
    public void agentMessageAfterReasoningStreamsContentImmediately() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-agent-after-reasoning");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"reason_1\",\"type\":\"reasoning\",\"text\":\"分析问题\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg_1\",\"type\":\"agent_message\",\"text\":\"正文第一段\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg_1\",\"type\":\"agent_message\",\"text\":\"正文第一段继续\"}}",
                callback,
                assistantContent
        );

        assertEquals("正文第一段继续", assistantContent.toString());
        assertEquals(List.of("分析问题"), callback.contentsOfType("thinking_delta"));
        assertEquals(List.of("正文第一段", "继续"), callback.contentsOfType("content_delta"));
    }

    @Test
    public void agentMessageAfterToolStillStreamsAsAssistantContentOnTurnCompletion() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-agent-tool-transcript");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();
        String transcript = "Wall time: 3.6 seconds\n"
                + "vitest run tests/views/plant-center/plantCenterLayout.test.js\n"
                + "RUN v4.0.18 D:/project/zh-newpark-webui\n"
                + "FAIL tests/views/plant-center/plantCenterLayout.test.js > plant-center layout";

        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"vitest run tests/views/plant-center/plantCenterLayout.test.js\",\"status\":\"in_progress\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"vitest run tests/views/plant-center/plantCenterLayout.test.js\",\"exit_code\":1,\"output\":\"failed\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"msg_1\",\"type\":\"agent_message\",\"text\":\""
                        + transcript.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        + "\"}}",
                callback,
                assistantContent
        );

        assertEquals(transcript, assistantContent.toString());
        assertEquals(List.of(transcript), callback.contentsOfType("content_delta"));

        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                callback,
                assistantContent
        );

        assertEquals(transcript, assistantContent.toString());
        assertEquals(List.of(transcript), callback.contentsOfType("content_delta"));
        assertEquals(List.of(), callback.contentsOfType("thinking_delta"));
    }

    @Test
    public void agentMessageSummaryAfterToolRemainsVisibleContentOnTurnCompletion() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-agent-tool-summary");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"status\":\"in_progress\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"exit_code\":0,\"output\":\"clean\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"msg_1\",\"type\":\"agent_message\",\"text\":\"测试已经通过，可以继续。\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                callback,
                assistantContent
        );

        assertEquals("测试已经通过，可以继续。", assistantContent.toString());
        assertEquals(List.of("测试已经通过，可以继续。"), callback.contentsOfType("content_delta"));
        assertEquals(List.of(), callback.contentsOfType("thinking_delta"));
    }

    @Test
    public void commandExecutionStartedAndCompletedEmitToolUseAndResult() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-command");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"status\":\"in_progress\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"exit_code\":0,\"output\":\"clean\"}}",
                callback,
                assistantContent
        );

        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type) && event.content.contains("正在执行命令")));
        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type) && event.content.contains("命令执行完成")));
        assertTrue(callback.events.stream().anyMatch(event -> "assistant".equals(event.type)
                && event.content.contains("\"type\":\"tool_use\"")
                && event.content.contains("\"name\":\"Bash\"")
                && event.content.contains("git status")));
        assertTrue(callback.events.stream().anyMatch(event -> "user".equals(event.type)
                && event.content.contains("\"type\":\"tool_result\"")
                && event.content.contains("clean")));
    }

    @Test
    public void commandExecutionBoundariesResetStreamingBlocksWithoutStatusCards() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-command-boundaries");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg_1\",\"type\":\"agent_message\",\"text\":\"before tool\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"status\":\"in_progress\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"cmd_1\",\"type\":\"command_execution\",\"command\":\"git status\",\"exit_code\":0,\"output\":\"clean\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"reason_2\",\"type\":\"reasoning\",\"text\":\"after tool thinking\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.updated\",\"item\":{\"id\":\"msg_2\",\"type\":\"agent_message\",\"text\":\"after tool text\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                callback,
                assistantContent
        );

        // agent_message 始终作为正文流式输出,不进 thinking
        // WARNING:若改回 assertEquals(List.of("after tool text"), content_delta)
        //   + assertEquals(List.of("before tool", ...), thinking_delta) 即在验证"工具前
        //   agent_message 降级 thinking"旧行为,将间接允许回滚流式(2951c5f2 的设计)。
        assertEquals(List.of("before tool", "after tool text"), callback.contentsOfType("content_delta"));
        assertEquals(List.of("after tool thinking"), callback.contentsOfType("thinking_delta"));
        assertTrue(callback.events.stream().anyMatch(event -> "assistant".equals(event.type)
                && event.content.contains("\"type\":\"tool_use\"")
                && event.content.contains("git status")));
        assertTrue(callback.events.stream().anyMatch(event -> "user".equals(event.type)
                && event.content.contains("\"type\":\"tool_result\"")
                && event.content.contains("clean")));
        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type)));
        assertEquals(
                List.of("block_reset", "block_reset"),
                callback.events.stream()
                        .filter(event -> "block_reset".equals(event.type))
                        .map(Event::type)
                        .toList()
        );
    }

    @Test
    public void mcpToolCallStartedAndCompletedEmitToolUseAndResult() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-mcp");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.started\",\"item\":{\"id\":\"mcp_1\",\"type\":\"mcp_tool_call\",\"server\":\"context7\",\"tool\":\"resolve-library-id\",\"arguments\":{\"libraryName\":\"react\"}}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"mcp_1\",\"type\":\"mcp_tool_call\",\"server\":\"context7\",\"tool\":\"resolve-library-id\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"/facebook/react\"}]}}}",
                callback,
                assistantContent
        );

        assertTrue(callback.events.stream().anyMatch(event -> "assistant".equals(event.type)
                && event.content.contains("\"type\":\"tool_use\"")
                && event.content.contains("mcp__context7__resolve-library-id")));
        assertTrue(callback.events.stream().anyMatch(event -> "user".equals(event.type)
                && event.content.contains("\"type\":\"tool_result\"")
                && event.content.contains("/facebook/react")));
    }

    @Test
    public void responseItemFunctionCallAndOutputEmitToolUseAndResult() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-response-item-tool");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"shell_command\",\"arguments\":\"{\\\"command\\\":\\\"rtk git status\\\"}\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"call-1\",\"output\":\"clean\"}}",
                callback,
                assistantContent
        );

        assertTrue(callback.events.stream().anyMatch(event -> "assistant".equals(event.type)
                && event.content.contains("\"type\":\"tool_use\"")
                && event.content.contains("\"id\":\"call-1\"")
                && event.content.contains("\"name\":\"shell_command\"")
                && event.content.contains("rtk git status")));
        assertTrue(callback.events.stream().anyMatch(event -> "user".equals(event.type)
                && event.content.contains("\"type\":\"tool_result\"")
                && event.content.contains("\"tool_use_id\":\"call-1\"")
                && event.content.contains("clean")));
    }

    @Test
    public void responseItemCustomToolCallEmitsToolUse() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-response-item-custom-tool");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"call_id\":\"patch-1\",\"name\":\"apply_patch\",\"input\":\"*** Update File: README.md\\n-old\\n+new\"}}",
                callback,
                assistantContent
        );

        assertTrue(callback.events.stream().anyMatch(event -> "assistant".equals(event.type)
                && event.content.contains("\"type\":\"tool_use\"")
                && event.content.contains("\"id\":\"patch-1\"")
                && event.content.contains("\"name\":\"apply_patch\"")
                && event.content.contains("README.md")));
    }

    @Test
    public void nonToolItemsDoNotEmitProgressStatus() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-status");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(session, "{\"type\":\"turn.started\"}", callback, assistantContent);
        invokeParseEvent(session, "{\"type\":\"item.started\",\"item\":{\"id\":\"search_1\",\"type\":\"web_search\",\"query\":\"codex cli\"}}", callback, assistantContent);
        invokeParseEvent(session, "{\"type\":\"item.completed\",\"item\":{\"id\":\"file_1\",\"type\":\"file_change\",\"path\":\"README.md\",\"status\":\"completed\"}}", callback, assistantContent);
        invokeParseEvent(session, "{\"type\":\"item.completed\",\"item\":{\"id\":\"plan_1\",\"type\":\"plan_update\",\"status\":\"completed\"}}", callback, assistantContent);

        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type) && event.content.contains("Codex 正在处理")));
        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type) && event.content.contains("正在搜索")));
        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type) && event.content.contains("文件变更")));
        assertFalse(callback.events.stream().anyMatch(event -> "status".equals(event.type) && event.content.contains("计划")));
    }

    @Test
    public void turnCompletedUsageEmitsResultMessageForCodexHandler() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-usage");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":10,\"cached_input_tokens\":3,\"output_tokens\":5}}",
                callback,
                new StringBuilder()
        );

        assertTrue(callback.events.stream().anyMatch(event -> "result".equals(event.type)
                && event.content.contains("\"input_tokens\":10")
                && event.content.contains("\"cache_read_input_tokens\":3")
                && event.content.contains("\"output_tokens\":5")));
    }

    @Test
    public void turnFailedIsReportedAsFormattedError() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-failed");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unexpected status 504 Gateway Timeout\"}}",
                callback,
                new StringBuilder()
        );

        assertTrue(callback.errors.stream().anyMatch(error -> error.contains("网关或上游服务超时 (504)")));
    }

    @Test
    public void fatalReconnectingErrorIsReportedImmediatelyInsteadOfBuffered() throws Exception {
        // 复现:codex 对不支持的 model 返回 502,触发 "Reconnecting... N/5" 死循环,子进程永不退出。
        // parseEvent 收到 error 事件时 cliError 恒非空(生产 send 第 126 行 new StringBuilder()),
        // 旧行为静默 appendDiagnosticLine → 永不 onError → 前端无限 "Generating response"。
        // 注意必须用 5 参数版 invokeParseEvent(cliError 非空)模拟生产;4 参数版 cliError=null 会走 else 分支假绿。
        CodexCliSession session = new CodexCliSession("tab-fatal");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"error\",\"message\":\"Reconnecting... 1/5 (unexpected status 502 Bad Gateway: error code: 502, url: https://gpt.eacase.de5.net/v1/responses)\"}",
                callback,
                new StringBuilder(),
                new StringBuilder()
        );

        assertFalse("致命 Reconnecting/502 错误必须立即上报,而非静默缓冲导致前端无限转圈",
                callback.errors.isEmpty());
    }

    @Test
    public void isFatalCodexErrorDetectsReconnectingAndGatewayStatuses() {
        assertTrue(CodexCliSession.isFatalCodexError(
                "Reconnecting... 1/5 (unexpected status 502 Bad Gateway)"));
        assertTrue(CodexCliSession.isFatalCodexError("HTTP 502: "));
        assertTrue(CodexCliSession.isFatalCodexError("unexpected status 503 Service Unavailable"));
        assertFalse(CodexCliSession.isFatalCodexError("running command ls"));
        assertFalse(CodexCliSession.isFatalCodexError(null));
    }

    @Test
    public void shouldReportCliErrorOnExit0GatesOnTurnCompleted() {
        // 深层隐患修复:exit0 时 codex 已发 turn.completed = turn 成功,任何缓冲诊断都是
        // 非致命噪声(工具调用失败/警告/turn 后收尾行),不得翻转成功 turn 为失败。
        // 仅"未 turn.completed 却 exit0 且有诊断"才是真正的静默失败(中途 bail 出错)。
        // 该判定是严格放松(失败集 ⊆ 旧失败集),只能把旧误报转成功,零回归。
        assertFalse("无诊断必不报", CodexCliSession.shouldReportCliErrorOnExit0("", false));
        assertFalse("无诊断必不报(即使 turnCompleted)", CodexCliSession.shouldReportCliErrorOnExit0("", true));
        assertFalse("turn 已完成 + 非致命诊断噪声 → 不报(核心修复点,根治思考区消失/误报失败)",
                CodexCliSession.shouldReportCliErrorOnExit0("some non-fatal diagnostic noise", true));
        assertTrue("未 turn.completed 却有诊断 → 真静默失败,保留检测",
                CodexCliSession.shouldReportCliErrorOnExit0("some error that bailed mid-turn", false));
    }

    @Test
    public void mcpRmcpDiagnosticLineDowngradedToStatusNotice() throws Exception {
        // 复现用户截图:本地 MCP 未启动,Codex 成功回答后仍弹 rmcp 错误。
        // 根因:rmcp 非 JSON 日志行命中 CLI_ERROR_KEYWORD_PATTERN → 缓冲进 cliError → exit0 onError。
        // 期望:rmcp 行降级为非阻塞 status 提示,不缓冲为回合错误(cliError 保持空)。
        CodexCliSession session = new CodexCliSession("tab-mcp");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder cliError = new StringBuilder();

        invokeParseEvent(
                session,
                "ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed, "
                        + "when Client(HttpRequest(HttpRequest(\"http/request failed: error sending "
                        + "request for url (http://127.0.0.1:64343/stream)\")))",
                callback,
                new StringBuilder(),
                cliError
        );

        assertTrue("cliError 必须保持空(rmcp 行不缓冲为回合错误,不触发 exit0 onError)",
                cliError.toString().isEmpty());
        assertTrue("rmcp 行不得报错(callback.errors 必须为空)", callback.errors.isEmpty());
        List<String> statusMessages = callback.contentsOfType("status");
        assertEquals("应发一条非阻塞 status 提示", 1, statusMessages.size());
    }

    @Test
    public void mcpStructuredErrorEventDowngradedToStatusNotice() throws Exception {
        // 防御:codex 结构化 error 事件(message 含 MCP 失败)同样降级,不缓冲为回合错误。
        CodexCliSession session = new CodexCliSession("tab-mcp-evt");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder cliError = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"error\",\"message\":\"mcp server 'weather' failed to connect\"}",
                callback,
                new StringBuilder(),
                cliError
        );

        assertTrue("MCP error 事件不缓冲为回合错误", cliError.toString().isEmpty());
        assertTrue("MCP error 事件不得 onError", callback.errors.isEmpty());
        assertEquals("应发一条非阻塞 status 提示", 1, callback.contentsOfType("status").size());
    }

    @Test
    public void mcpNoticeEmittedAtMostOncePerTurn() throws Exception {
        // rmcp worker 可能重试多次刷屏:每回合只发一次 toast,但每次匹配都抑制(不缓冲)。
        CodexCliSession session = new CodexCliSession("tab-mcp-dedupe");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder cliError = new StringBuilder();
        String rmcp = "ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed";

        invokeParseEvent(session, rmcp, callback, new StringBuilder(), cliError);
        invokeParseEvent(session, rmcp, callback, new StringBuilder(), cliError);
        invokeParseEvent(session, rmcp, callback, new StringBuilder(), cliError);

        assertEquals("多次 rmcp 行只发一次 status toast", 1, callback.contentsOfType("status").size());
        assertTrue("所有 rmcp 行都不缓冲", cliError.toString().isEmpty());
    }

    @Test
    public void codexToolRouterErrorNotBufferedAsTurnFailure() throws Exception {
        // 复现用户报错:codex 用 shell 工具(PowerShell)列目录失败,turn 本身正常完成(exit0),
        // 但 "ERROR codex_core::tools::router: error=`'...pwsh.exe' -Command '...'" tracing 日志
        // 命中 CLI_ERROR_KEYWORD_PATTERN → 缓冲进 cliError → exit0 时(line 267-271)误报
        // "Codex CLI 请求失败 / This response stopped",且 turn-fail 快照丢失 thinking 块(思考区消失)。
        // 根因:codex_core::tools::router 是单次工具调用失败的内部 tracing,codex 会把错误回灌
        // 给模型继续 turn,非回合致命。期望:不缓冲为回合错误(cliError 空),不 onError。
        CodexCliSession session = new CodexCliSession("tab-tool-router");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder cliError = new StringBuilder();

        invokeParseEvent(
                session,
                "2026-07-02T22:58:57.266176Z ERROR codex_core::tools::router: error=`\"C:\\\\Program Files\\\\PowerShell\\\\7\\\\pwsh.exe\" -Command \"Get-ChildItem -Force | Select-Object Mode,Length,Name\"`",
                callback,
                new StringBuilder(),
                cliError
        );

        assertTrue("codex_core::tools::router 工具执行日志不得缓冲为回合错误(否则 exit0 误报失败)",
                cliError.toString().isEmpty());
        assertTrue("工具执行日志不得 onError", callback.errors.isEmpty());
    }

    @Test
    public void fatalCodexErrorSetsFatalAbortFlagToBreakStdoutLoop() throws Exception {
        // parseEvent 对致命 error 不仅 onError,还必须置 fatalAbort 标志:它是 stdout 读取循环
        // 提前 break + destroyForcibly 的前提。仅 onError 而不置标志,stdout 仍阻塞等子进程 EOF,
        // 而 502 重连死循环下子进程永不退出 → 前端无限 "Generating response"(019f0fbd 复现:
        // codex rollout duration_ms=580505 ≈ 9.7 分钟才 task_complete)。
        CodexCliSession session = new CodexCliSession("tab-fatal-flag");
        RecordingCallback callback = new RecordingCallback();

        invokeParseEvent(
                session,
                "{\"type\":\"error\",\"message\":\"unexpected status 502 Bad Gateway\"}",
                callback,
                new StringBuilder(),
                new StringBuilder()
        );

        Field fatalAbortField = CodexCliSession.class.getDeclaredField("fatalAbort");
        fatalAbortField.setAccessible(true);
        assertTrue("致命 502 error 必须置 fatalAbort 标志,驱动 stdout break+destroyForcibly 终止卡死进程",
                fatalAbortField.getBoolean(session));
    }

    @Test
    public void imageUnsupportedTurnFailureUsesLocalizedVisionMessage() throws Exception {
        Method method = CodexCliSession.class.getDeclaredMethod(
                "formatCodexError",
                String.class,
                boolean.class
        );
        method.setAccessible(true);

        String error = (String) method.invoke(
                null,
                "This model does not support image input. Please use a vision-capable model.",
                true
        );

        assertTrue(error.startsWith("__I18N__:aiBridge.unsupportedImageVision"));
        assertTrue(error.contains("Details:"));
        assertTrue(error.contains("does not support image input"));
    }

    @Test
    public void imageRequestRateLimitKeepsFormattedRateLimitError() throws Exception {
        Method method = CodexCliSession.class.getDeclaredMethod(
                "formatCodexError",
                String.class,
                boolean.class
        );
        method.setAccessible(true);

        String error = (String) method.invoke(
                null,
                "API Error: Request rejected (429) · [1308][已达到 5 小时的使用上限。"
                        + "您的限额将在 2026-06-01 19:08:32 重置。]",
                true
        );

        assertFalse(error.startsWith("__I18N__:aiBridge.unsupportedImageVision"));
        assertTrue(error.contains("请求过于频繁 (429)"));
        assertTrue(error.contains("已达到 5 小时的使用上限"));
    }

    @Test
    public void imageRequestExitRateLimitWithImageContextKeepsFormattedRateLimitError() throws Exception {
        Method method = CodexCliSession.class.getDeclaredMethod(
                "buildExitError",
                int.class,
                StringBuilder.class,
                StringBuilder.class,
                boolean.class
        );
        method.setAccessible(true);

        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("sending local_image attachment failed: ")
                .append("API Error: Request rejected (429) · [1308][已达到 5 小时的使用上限。]")
                .append(" model requires vision request retry");

        String error = (String) method.invoke(null, 1, diagnostic, null, true);

        assertFalse(error.startsWith("__I18N__:aiBridge.unsupportedImageVision"));
        assertTrue(error.contains("Codex CLI 请求失败"));
        assertTrue(error.contains("请求过于频繁 (429)"));
        assertTrue(error.contains("local_image"));
    }

    @Test
    public void interruptedExitDoesNotReportExitCodeError() {
        CodexCliSession session = new CodexCliSession("tab-codex");

        session.interrupt();

        assertTrue(session.wasInterrupted());
        assertFalse(session.shouldReportExitError(1));
    }

    @Test
    public void sendPreparationClearsOnlyPreviousCodexInterrupts() {
        CodexCliSession session = new CodexCliSession("tab-codex");

        session.interrupt();
        session.prepareForSend();
        assertFalse(session.wasInterrupted());

        session.interrupt();
        assertTrue(session.wasInterrupted());
    }

    @Test
    public void readingAdditionalInputFromStdinIsIgnored() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-2");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(session, "Reading additional input from stdin...", callback, assistantContent);

        assertEquals("", assistantContent.toString());
        assertFalse(callback.events.stream().anyMatch(event -> "content_delta".equals(event.type)));
    }

    @Test
    public void longPromptIsNotPlacedOnWindowsCommandLine() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-long-prompt");
        String longPrompt = "x".repeat(40_000);
        var request = new com.github.claudecodegui.cli.CliSendRequest(
                "tab-long-prompt",
                "codex",
                longPrompt,
                null,
                "D:\\project\\jetbrains-melon-cc-gui",
                List.of(),
                null,
                List.of(),
                null,
                "acceptEdits",
                "gpt-5.3-codex",
                null,
                null,
                null,
                java.util.Map.of()
        );

        Method buildCommand = CodexCliSession.class.getDeclaredMethod(
                "buildCommand",
                com.github.claudecodegui.cli.CliSendRequest.class,
                List.class,
                List.class
        );
        buildCommand.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) buildCommand.invoke(session, request, List.of(), List.of());

        assertFalse("Prompt must be sent via stdin instead of as a command-line argument", command.contains(longPrompt));

        Method buildPromptInput = CodexCliSession.class.getDeclaredMethod(
                "buildPromptInput",
                com.github.claudecodegui.cli.CliSendRequest.class
        );
        buildPromptInput.setAccessible(true);
        byte[] stdin = (byte[]) buildPromptInput.invoke(session, request);
        assertEquals(longPrompt, new String(stdin, StandardCharsets.UTF_8));
    }

    @Test
    public void gbkEncodedWindowsDiagnosticFallsBackToChineseText() throws Exception {
        Method decodeLine = CodexCliSession.class.getDeclaredMethod(
                "decodeLine",
                byte[].class,
                int.class
        );
        decodeLine.setAccessible(true);

        byte[] bytes = "命令行太长。".getBytes(Charset.forName("GBK"));
        String decoded = (String) decodeLine.invoke(null, bytes, bytes.length);

        assertEquals("命令行太长。", decoded);
    }

    @Test
    public void rawPowerShellDiagnosticsAreRoutedToCliErrorBuffer() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-ps");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();
        StringBuilder cliError = new StringBuilder();

        invokeParseEvent(
                session,
                ". : File C:\\Users\\32979\\Documents\\WindowsPowerShell\\Microsoft.PowerShell_profile.ps1 cannot be loaded because running scripts is disabled on this system.",
                callback,
                assistantContent,
                cliError
        );

        assertEquals("", assistantContent.toString());
        assertFalse(callback.events.stream().anyMatch(event -> "content_delta".equals(event.type)));
        assertTrue(cliError.toString().contains("Microsoft.PowerShell_profile.ps1"));
    }

    @Test
    public void rawPowerShellCmdletErrorIsIgnoredFromStreamingContent() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-ps-cmdlet");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();
        StringBuilder cliError = new StringBuilder();

        invokeParseEvent(
                session,
                "thinking : The term 'thinking' is not recognized as the name of a cmdlet, function, script file, or operable program.",
                callback,
                assistantContent,
                cliError
        );

        assertEquals("", assistantContent.toString());
        assertFalse(callback.events.stream().anyMatch(event -> "content_delta".equals(event.type)));
        assertTrue(cliError.toString().contains("thinking"));
    }

    @Test
    public void multiLinePowerShellGetContentErrorIsRoutedToCliErrorBuffer() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-ps-get-content");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();
        StringBuilder cliError = new StringBuilder();

        invokeParseEvent(session, "Get-Content:", callback, assistantContent, cliError);
        invokeParseEvent(session, "Line |", callback, assistantContent, cliError);
        invokeParseEvent(session, "2 | Get-Content build.gradle.kts", callback, assistantContent, cliError);
        invokeParseEvent(session, "| ~~~~~~~~~~~~~~~~~~~~~~~~~~~~", callback, assistantContent, cliError);
        invokeParseEvent(
                session,
                "| Cannot find path 'D:\\\\project\\\\jetbrains-melon-cc-gui\\\\build.gradle.kts' because it does not exist.",
                callback,
                assistantContent,
                cliError
        );

        assertEquals("", assistantContent.toString());
        assertFalse(callback.events.stream().anyMatch(event -> "content_delta".equals(event.type)));
        assertTrue(cliError.toString().contains("Get-Content:"));
        assertTrue(cliError.toString().contains("Cannot find path"));
    }

    @Test
    public void plainAssistantMessageMentioningWallTimeIsNotTreatedAsToolTranscript() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-wall-time-text");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_msg\",\"type\":\"agent_message\",\"text\":\"如图，错误信息里包含 Wall time: 0:40，这里不应该被识别成工具转录。\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"cached_input_tokens\":0,\"output_tokens\":1}}",
                callback,
                assistantContent
        );

        assertTrue(callback.events.stream().anyMatch(event -> "content_delta".equals(event.type)
                && event.content.contains("Wall time: 0:40")));
        assertFalse(callback.events.stream().anyMatch(event -> "thinking_delta".equals(event.type)
                && event.content.contains("Wall time: 0:40")));
    }

    @Test
    public void agentMessageAlwaysStreamsAsAssistantContentPerOfficialJsonContract() throws Exception {
        CodexCliSession session = new CodexCliSession("tab-agent-message-contract");
        RecordingCallback callback = new RecordingCallback();
        StringBuilder assistantContent = new StringBuilder();

        invokeParseEvent(
                session,
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_msg\",\"type\":\"agent_message\",\"text\":\"RUN v3.2.4 D:/project/webview\\n✓ src/utils/messageUtils.test.ts (96 tests) 9ms\\nDuration 2.52s\"}}",
                callback,
                assistantContent
        );
        invokeParseEvent(
                session,
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"cached_input_tokens\":0,\"output_tokens\":1}}",
                callback,
                assistantContent
        );

        assertTrue(callback.events.stream().anyMatch(event -> "content_delta".equals(event.type)
                && event.content.contains("RUN v3.2.4")));
        assertFalse(callback.events.stream().anyMatch(event -> "thinking_delta".equals(event.type)
                && event.content.contains("RUN v3.2.4")));
    }

    @Test
    public void exitErrorIncludesFormattedDiagnosticOutput() throws Exception {
        Method method = CodexCliSession.class.getDeclaredMethod(
                "buildExitError",
                int.class,
                StringBuilder.class,
                StringBuilder.class,
                boolean.class
        );
        method.setAccessible(true);

        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("unexpected status 503 Service Unavailable: Service temporarily unavailable, ")
                .append("url: https://gongyiapi.mossx.ai/responses, ")
                .append("request id: req-503");

        String error = (String) method.invoke(null, 1, diagnostic, null, false);

        assertTrue(error.contains("Codex CLI 请求失败"));
        assertTrue(error.contains("服务暂时不可用 (503)"));
        assertTrue(error.contains("https://gongyiapi.mossx.ai/responses"));
        assertTrue(error.contains("req-503"));
    }

    @Test(expected = NoSuchMethodException.class)
    public void legacyThreeArgumentParseEventOverloadIsRemoved() throws Exception {
        CodexCliSession.class.getDeclaredMethod(
                "parseEvent",
                String.class,
                CliSessionCallback.class,
                StringBuilder.class
        );
    }

    private static void invokeParseEvent(
            CodexCliSession session,
            String line,
            CliSessionCallback callback,
            StringBuilder assistantContent
    ) throws Exception {
        invokeParseEvent(session, line, callback, assistantContent, null);
    }

    private static void invokeParseEvent(
            CodexCliSession session,
            String line,
            CliSessionCallback callback,
            StringBuilder assistantContent,
            StringBuilder cliError
    ) throws Exception {
        Method method = CodexCliSession.class.getDeclaredMethod(
                "parseEvent",
                String.class,
                CliSessionCallback.class,
                StringBuilder.class,
                StringBuilder.class
        );
        method.setAccessible(true);
        method.invoke(session, line, callback, assistantContent, cliError);
    }

    private static final class RecordingCallback implements CliSessionCallback {
        private final List<Event> events = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            events.add(new Event(type, content));
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }

        @Override
        public void onComplete(boolean success, String finalResult, String error) {
        }

        private List<String> contentsOfType(String type) {
            List<String> values = new ArrayList<>();
            for (Event event : events) {
                if (type.equals(event.type)) {
                    values.add(event.content);
                }
            }
            return values;
        }
    }

    private record Event(String type, String content) {
    }
}
