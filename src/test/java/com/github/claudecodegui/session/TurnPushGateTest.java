package com.github.claudecodegui.session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 纯逻辑测试:TurnPushGate 统一推送层开关。
 * - 流式 off → content delta 进 per-turn buffer,turn 边界一次性 flush
 * - 思考区 off → 丢弃 thinking(模型照常思考,只控推送/显示)
 * - 开关值在每个 turn 开始({@code onTurnStart})快照读取,中途切换从下个 turn 生效
 */
public class TurnPushGateTest {

    /** 可变 BooleanSupplier,测试中按需翻转开关值。 */
    private static final class Toggle implements BooleanSupplier {
        boolean value = true;

        @Override
        public boolean getAsBoolean() {
            return value;
        }
    }

    @Test
    public void streamingOn_passesContentDeltaThrough() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = true;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        assertTrue(gate.onContentDelta("Hel"));
        assertTrue(gate.onContentDelta("lo"));
        assertTrue(sink.isEmpty());
    }

    @Test
    public void streamingOff_buffersContentDeltaWithoutFlushing() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = false;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        assertFalse(gate.onContentDelta("Hel"));
        assertFalse(gate.onContentDelta("lo"));
        assertTrue(sink.isEmpty());
    }

    @Test
    public void streamingOff_flushesBufferedInOneShot() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = false;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        gate.onContentDelta("Hel");
        gate.onContentDelta("lo");
        gate.flushContent();

        assertEquals(List.of("Hello"), sink);
    }

    @Test
    public void flushContent_isNoOpWhenStreamingOn() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = true;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        gate.onContentDelta("Hel");
        gate.flushContent();

        // streaming on → gate 不缓冲,flush 无事可做(delta 已透传给下游 throttler)
        assertTrue(sink.isEmpty());
    }

    @Test
    public void flushContent_idempotentWhenBufferEmpty() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = false;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        gate.onContentDelta("data");
        gate.flushContent();
        gate.flushContent(); // 第二次:buffer 已清空,不应再推

        assertEquals(List.of("data"), sink);
    }

    @Test
    public void onTurnStart_clearsResidualBufferFromPreviousTurn() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = false;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        gate.onContentDelta("leftover"); // 上一轮残留(streaming off 缓冲,未 flush)

        gate.onTurnStart(); // 新 turn 清空残留 buffer
        gate.flushContent();

        assertTrue(sink.isEmpty());
    }

    @Test
    public void onTurnStart_reReadsStreamingToggleEachTurn() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        streaming.value = false;
        gate.onTurnStart();
        assertFalse(gate.onContentDelta("a")); // turn 1:缓冲

        streaming.value = true;
        gate.onTurnStart();
        assertTrue(gate.onContentDelta("b")); // turn 2:透传(开关已切换)
    }

    @Test
    public void showThinkingOn_emitsThinking() {
        Toggle showThinking = new Toggle();
        showThinking.value = true;
        TurnPushGate gate = newGate(new ArrayList<>(), new Toggle(), showThinking);

        assertTrue(gate.shouldEmitThinking());
    }

    @Test
    public void showThinkingOff_suppressesThinking() {
        Toggle showThinking = new Toggle();
        showThinking.value = false;
        TurnPushGate gate = newGate(new ArrayList<>(), new Toggle(), showThinking);

        assertFalse(gate.shouldEmitThinking());
    }

    @Test
    public void onTurnStart_reReadsShowThinkingToggleEachTurn() {
        Toggle showThinking = new Toggle();
        TurnPushGate gate = newGate(new ArrayList<>(), new Toggle(), showThinking);

        showThinking.value = false;
        gate.onTurnStart();
        assertFalse(gate.shouldEmitThinking());

        showThinking.value = true;
        gate.onTurnStart();
        assertTrue(gate.shouldEmitThinking());
    }

    @Test
    public void onContentDelta_nullOrEmptyIsBufferedAsNoOp() {
        List<String> sink = new ArrayList<>();
        Toggle streaming = new Toggle();
        streaming.value = false;
        TurnPushGate gate = newGate(sink, streaming, new Toggle());

        gate.onContentDelta(null);
        gate.onContentDelta("");
        gate.flushContent();

        assertTrue(sink.isEmpty());
    }

    private static TurnPushGate newGate(List<String> sink, Toggle streaming, Toggle showThinking) {
        TurnPushGate gate = new TurnPushGate(sink::add, streaming, showThinking);
        gate.onTurnStart();
        return gate;
    }
}
