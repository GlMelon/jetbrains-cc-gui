package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * §15.4 / §7.3:OpenCodeCliStreamParser 必须按真实 opencode run --format json 事件 schema 解析
 * (样本实捕自 opencode v1.17.11,禁止臆造)。覆盖 step_start/text/tool_use/step_finish/error 五类事件。
 */
public class OpenCodeCliStreamParserTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>(); // {type, content}
        String error;
        String interruptedContent;
        boolean completed;
        boolean completeOk;

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
            this.error = error;
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
            completed = true;
            completeOk = success;
        }

        @Override
        public void onInterrupted(String content, String reason) {
            interruptedContent = content;
        }
    }

    private static String msg(List<String[]> msgs, int idx) {
        return msgs.get(idx)[0];
    }

    @Test
    public void stepStartEmitsStreamStartAndExtractsSessionId() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"step_start\",\"timestamp\":1782500111829,"
                + "\"sessionID\":\"ses_0fab6db33ffeDqvHdwBzN05Rw0\","
                + "\"part\":{\"id\":\"prt_x\",\"messageID\":\"msg_x\",\"sessionID\":\"ses_0fab6db33ffeDqvHdwBzN05Rw0\",\"type\":\"step-start\"}}");

        // stream_start 仅在首轮 step_start 触发一次
        assertEquals(CliConstants.MSG_STREAM_START, msg(cb.messages, 0));
        // session id 从顶层 sessionID 提取
        assertEquals("ses_0fab6db33ffeDqvHdwBzN05Rw0", parser.capturedSessionId());
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_SESSION_ID.equals(m[0])
                && "ses_0fab6db33ffeDqvHdwBzN05Rw0".equals(m[1])));
    }

    @Test
    public void textEventEmitsContentDelta() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"text\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"part\":{\"id\":\"prt_t\",\"messageID\":\"msg_t\",\"type\":\"text\","
                + "\"text\":\"你好！有什么可以帮你的吗？\"}}");

        assertEquals(CliConstants.MSG_CONTENT_DELTA, msg(cb.messages, 0));
        assertEquals("你好！有什么可以帮你的吗？", cb.messages.get(0)[1]);
    }

    @Test
    public void toolUseEventEmitsToolUseAndToolResultBlocks() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"tool_use\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"part\":{\"type\":\"tool\",\"tool\":\"bash\",\"callID\":\"call_abc\","
                + "\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"echo hello\"},"
                + "\"output\":\"hello\\n\",\"metadata\":{\"exit\":0}},"
                + "\"id\":\"prt_tool\",\"sessionID\":\"ses_1\",\"messageID\":\"msg_tool\"}}");

        // 工具调用块(tool_use) + 工具结果块(tool_result)
        assertTrue(cb.messages.stream().anyMatch(m -> CommonConstants.MSG_TYPE_TOOL_USE.equals(m[0])
                && m[1].contains("bash") && m[1].contains("call_abc")));
        assertTrue(cb.messages.stream().anyMatch(m -> CommonConstants.MSG_TYPE_TOOL_RESULT.equals(m[0])
                && m[1].contains("hello")));
    }

    @Test
    public void stepFinishWithStopEmitsUsageResultAndStreamEnd() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"step_finish\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"part\":{\"reason\":\"stop\",\"messageID\":\"msg_f\",\"type\":\"step-finish\","
                + "\"tokens\":{\"total\":25501,\"input\":24449,\"output\":11,\"reasoning\":17,"
                + "\"cache\":{\"write\":0,\"read\":1024}},\"cost\":0}}");

        // usage 经 MSG_RESULT 下发(handleResultMessage 解析统一 usage schema)
        String resultContent = cb.messages.stream()
                .filter(m -> CliConstants.MSG_RESULT.equals(m[0])).findFirst()
                .map(m -> m[1]).orElse(null);
        assertTrue("usage must carry input_tokens", resultContent != null && resultContent.contains("\"input_tokens\":24449"));
        assertTrue("usage must carry output_tokens", resultContent.contains("\"output_tokens\":11"));
        assertTrue("usage must carry cache_read_input_tokens", resultContent.contains("\"cache_read_input_tokens\":1024"));
        assertTrue("usage must carry cache_creation_input_tokens", resultContent.contains("\"cache_creation_input_tokens\":0"));
        // reason=stop → 流结束
        assertTrue(cb.messages.stream().anyMatch(m -> CliConstants.MSG_STREAM_END.equals(m[0])));
    }

    @Test
    public void stepFinishWithToolCallsDoesNotEmitStreamEnd() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"step_finish\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"part\":{\"reason\":\"tool-calls\",\"messageID\":\"msg_f\",\"type\":\"step-finish\","
                + "\"tokens\":{\"total\":1,\"input\":1,\"output\":1,\"reasoning\":0,"
                + "\"cache\":{\"write\":0,\"read\":0}},\"cost\":0}}");

        // reason=tool-calls → 后续还有 step,不应结束流
        assertFalse(cb.messages.stream().anyMatch(m -> CliConstants.MSG_STREAM_END.equals(m[0])));
    }

    @Test
    public void errorEventIsCollectedForReporting() {
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"error\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"error\":{\"name\":\"UnknownError\",\"data\":{\"message\":\"Unexpected server error.\",\"ref\":\"err_x\"}}}");

        assertTrue("error must be captured", parser.hasError());
        assertTrue("error text should include the message", parser.errorDiagnostic().contains("Unexpected server error."));
    }

    @Test
    public void mcpErrorEventDowngradedToStatusNotice() {
        // MCP 连接失败(本地 server 未启动):降级为非阻塞 status 提示,不标记 hasError/缓冲为回合错误。
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"error\",\"timestamp\":1,\"sessionID\":\"ses_1\","
                + "\"error\":{\"data\":{\"message\":\"mcp server 'weather' failed to connect\"}}}");

        assertFalse("MCP 失败不标记 hasError", parser.hasError());
        assertTrue("MCP 失败不缓冲错误诊断", parser.errorDiagnostic().isEmpty());
        assertEquals("应发一条非阻塞 status 提示", 1,
                cb.messages.stream().filter(m -> CliConstants.CODEX_MSG_STATUS.equals(m[0])).count());
    }

    @Test
    public void mcpNoticeEmittedAtMostOncePerRun() {
        // MCP 错误可能多次出现:每轮只发一次 toast,但每次都抑制(不标记 hasError)。
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"error\",\"error\":{\"data\":{\"message\":\"rmcp transport channel closed\"}}}");
        parser.parseLine("{\"type\":\"error\",\"error\":{\"data\":{\"message\":\"rmcp transport channel closed\"}}}");

        assertEquals("多次 MCP 错误只发一次 status toast", 1,
                cb.messages.stream().filter(m -> CliConstants.CODEX_MSG_STATUS.equals(m[0])).count());
        assertFalse("所有 MCP 错误都不标记 hasError", parser.hasError());
    }

    @Test
    public void reasoningEventEmitsThinkingActivationAndDelta() {
        // opencode run --format json --thinking 产出 reasoning 文本事件(实捕自 v1.17.13,
        // 推翻旧注释"--thinking 不改 json schema")。首个 reasoning → MSG_TYPE_THINKING 激活
        // (对称 SDK event-mapper thinkingStart + CodexMessageHandler 点亮"思考中"指示灯)
        // + MSG_THINKING_DELTA(text)。
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"reasoning\",\"timestamp\":1783132415530,\"sessionID\":\"ses_r\","
                + "\"part\":{\"id\":\"prt_r1\",\"messageID\":\"msg_r\",\"sessionID\":\"ses_r\","
                + "\"type\":\"reasoning\",\"text\":\"The user said hi. I should greet back.\","
                + "\"time\":{\"start\":1,\"end\":2}}}");

        assertTrue("首个 reasoning 必须发思考激活 MSG_TYPE_THINKING",
                cb.messages.stream().anyMatch(m -> CommonConstants.MSG_TYPE_THINKING.equals(m[0])));
        assertTrue("reasoning 文本必须作为 thinking_delta 下发",
                cb.messages.stream().anyMatch(m -> CliConstants.MSG_THINKING_DELTA.equals(m[0])
                        && m[1].contains("I should greet back")));
    }

    @Test
    public void subsequentReasoningEmitsOnlyDeltaIncrement() {
        // reasoning text 为累积式(同 part.id,text 增长)→ 后续只发增量 delta,激活态不重复。
        // 对称 ai-bridge event-mapper.js delta() 去重。
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"reasoning\",\"sessionID\":\"ses_r\","
                + "\"part\":{\"id\":\"prt_r1\",\"messageID\":\"msg_r\",\"type\":\"reasoning\",\"text\":\"abc\"}}");
        parser.parseLine("{\"type\":\"reasoning\",\"sessionID\":\"ses_r\","
                + "\"part\":{\"id\":\"prt_r1\",\"messageID\":\"msg_r\",\"type\":\"reasoning\",\"text\":\"abcdef\"}}");

        long activations = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_THINKING.equals(m[0])).count();
        assertEquals("激活态只发一次", 1L, activations);
        assertTrue("第二条只发增量 delta \"def\"", cb.messages.stream()
                .anyMatch(m -> CliConstants.MSG_THINKING_DELTA.equals(m[0]) && "def".equals(m[1])));
    }

    @Test
    public void emptyFirstReasoningTextStillActivates() {
        // 首个 reasoning text 可能为空(占位事件)→ 仍发激活态点亮指示灯,但不发空 delta。
        // 对称 SDK event-mapper:首条 reasoning(即使 text 空)合成 thinking 激活。
        RecordingCallback cb = new RecordingCallback();
        OpenCodeCliStreamParser parser = new OpenCodeCliStreamParser(cb);

        parser.parseLine("{\"type\":\"reasoning\",\"sessionID\":\"ses_r\","
                + "\"part\":{\"id\":\"prt_r1\",\"messageID\":\"msg_r\",\"type\":\"reasoning\",\"text\":\"\"}}");

        assertTrue("空 text 仍激活思考态", cb.messages.stream()
                .anyMatch(m -> CommonConstants.MSG_TYPE_THINKING.equals(m[0])));
        assertFalse("空 text 不发 thinking_delta", cb.messages.stream()
                .anyMatch(m -> CliConstants.MSG_THINKING_DELTA.equals(m[0])));
    }
}
