package com.github.claudecodegui.cli.kimi;

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
 * KimiCliStreamParser:快照式 stream-json 行(assistant/tool/meta)。
 * 官方限制:thinking 不写入 JSONL → 思考区暂不支持(解析器无 thinking 分支即契约)。
 */
public class KimiCliStreamParserTest {

    private static final class RecordingCallback implements CliSessionCallback {
        final List<String[]> messages = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            messages.add(new String[]{type, content});
        }

        @Override
        public void onError(String error) {
            // 未覆盖
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
            // 未覆盖
        }

        @Override
        public void onInterrupted(String content, String reason) {
            // 未覆盖
        }
    }

    @Test
    public void growingSnapshotsEmitOnlyPrefixDeltas() {
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        parser.parseLine("{\"role\":\"assistant\",\"content\":\"Hello\"}");
        parser.parseLine("{\"role\":\"assistant\",\"content\":\"Hello world\"}");
        parser.parseLine("{\"role\":\"assistant\",\"content\":\"Hello world!\"}");

        long deltas = cb.messages.stream()
                .filter(m -> CliConstants.MSG_CONTENT_DELTA.equals(m[0])).count();
        // 快照序列 Hello → Hello world → Hello world!:首条为全量,后两条各取前缀差
        assertEquals(3L, deltas);
        List<String[]> deltaMsgs = cb.messages.stream()
                .filter(m -> CliConstants.MSG_CONTENT_DELTA.equals(m[0])).toList();
        assertEquals("Hello", deltaMsgs.get(0)[1]);
        assertEquals(" world", deltaMsgs.get(1)[1]);
        assertEquals("!", deltaMsgs.get(2)[1]);
        assertEquals("Hello world!", parser.accumulatedText());
    }

    @Test
    public void identicalSnapshotEmitsNothingAndNonPrefixReplacementIsSeparated() {
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        parser.parseLine("{\"role\":\"assistant\",\"content\":\"abc\"}");
        parser.parseLine("{\"role\":\"assistant\",\"content\":\"abc\"}");
        parser.parseLine("{\"role\":\"assistant\",\"content\":\"new block\"}");

        List<String[]> deltas = cb.messages.stream()
                .filter(m -> CliConstants.MSG_CONTENT_DELTA.equals(m[0])).toList();
        assertEquals(2L, deltas.size());
        assertEquals("\nnew block", deltas.get(1)[1]);
    }

    @Test
    public void toolCallsDedupedAcrossSnapshotReplays() {
        // kimi 快照会重复携带已发过的 tool_calls(stable key=id|args)
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        String snapshotA = "{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"c1\",\"function\":{\"name\":\"bash\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]}";
        parser.parseLine(snapshotA);
        parser.parseLine(snapshotA); // 重放

        long toolUses = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_TOOL_USE.equals(m[0])).count();
        assertEquals("重放只发一次 tool_use", 1L, toolUses);
        assertTrue(cb.messages.get(0)[1].contains("\"ls\""));
    }

    @Test
    public void missingIdFallsBackToNameBasedStableKey() {
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        parser.parseLine("{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}");
        parser.parseLine("{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}");

        long toolUses = cb.messages.stream()
                .filter(m -> CommonConstants.MSG_TYPE_TOOL_USE.equals(m[0])).count();
        assertEquals("无 id 时以 name|args 去重", 1L, toolUses);
    }

    @Test
    public void toolResultMappedWithToolUseId() {
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        parser.parseLine("{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"out-a\"}");

        assertTrue(parser.receivedAnyEvent());
        assertTrue(cb.messages.stream().anyMatch(m ->
                CommonConstants.MSG_TYPE_TOOL_RESULT.equals(m[0])
                        && m[1].contains("\"c1\"") && m[1].contains("out-a")));
    }

    @Test
    public void resumeHintCapturesSessionIdOnce() {
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        parser.parseLine("{\"role\":\"meta\",\"type\":\"session.resume_hint\","
                + "\"session_id\":\"session_01HZXYZ\"}");
        parser.parseLine("{\"role\":\"meta\",\"type\":\"session.resume_hint\","
                + "\"session_id\":\"session_01HZXYZ\"}");

        assertEquals("session_01HZXYZ", parser.capturedSessionId());
        assertEquals("只下发一次 SESSION_ID", 1L,
                cb.messages.stream().filter(m -> CliConstants.MSG_SESSION_ID.equals(m[0])).count());
    }

    @Test
    public void arrayContentPartsAreConcatenated() {
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);

        parser.parseLine("{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"part-\"},{\"text\":\"two\"}]}");

        assertEquals("part-two", parser.accumulatedText());
    }

    @Test
    public void reasoningContentFieldRemainsIgnoredOnLegacyChannel() {
        // legacy stream-json 通道契约:即便 assistant 行带 reasoning_content 字段,
        // KimiCliStreamParser 也不解析 thinking(stream-json 官方不写 thinking,见类注释)。
        // 思考区已由 ACP 通道(KimiAcpStreamParser 的 agent_thought_chunk)提供;
        // 此测试固定 legacy 通道不越权产出 thinking,避免两条通道行为混淆。
        RecordingCallback cb = new RecordingCallback();
        KimiCliStreamParser parser = new KimiCliStreamParser(cb);
        parser.parseLine("{\"role\":\"assistant\",\"content\":\"plain answer\","
                + "\"reasoning_content\":\"should be ignored on legacy channel\"}");
        assertFalse(cb.messages.stream()
                .anyMatch(m -> CommonConstants.MSG_TYPE_THINKING.equals(m[0])));
        assertFalse(cb.messages.stream()
                .anyMatch(m -> CliConstants.MSG_THINKING_DELTA.equals(m[0])));
    }
}
