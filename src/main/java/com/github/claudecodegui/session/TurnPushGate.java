package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.common.CliOutputLimits;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 推送层统一开关,让"流式输出"和"思考区"两个 toggle 对所有 provider 统一生效。
 * 挂在 {@link SessionCallbackAdapter} 与前端之间,不依赖各 provider CLI 的原生能力。
 *
 * <ul>
 *   <li><b>流式 off</b>:content delta 不再增量下发,而是进 per-turn buffer,在 turn 边界
 *       ({@code onStreamEnd}/{@code onBlockReset})一次性 flush 全量给前端。</li>
 *   <li><b>思考区 off</b>:thinking delta 与 thinking-status 变更被丢弃——模型照常思考,
 *       只控推送/显示(思考预算仍由 reasoning effort 决定)。</li>
 * </ul>
 *
 * <p>开关值在每个 turn 开始({@link #onTurnStart()})快照读取一次,整个 turn 使用该值;
 * 中途切换开关从下一个 turn 生效。
 *
 * <p>纯逻辑组件,无 IntelliJ Platform 依赖,可单测。下游 sink 与开关 supplier 均由构造函数注入。
 */
public final class TurnPushGate {

    private final Consumer<String> contentSink;
    private final BooleanSupplier streamingEnabledSupplier;
    private final BooleanSupplier showThinkingEnabledSupplier;

    private final StringBuilder contentBuffer = new StringBuilder();
    private boolean streamingEnabled = true;
    private boolean showThinkingEnabled = true;

    public TurnPushGate(
            Consumer<String> contentSink,
            BooleanSupplier streamingEnabledSupplier,
            BooleanSupplier showThinkingEnabledSupplier) {
        this.contentSink = contentSink;
        this.streamingEnabledSupplier = streamingEnabledSupplier;
        this.showThinkingEnabledSupplier = showThinkingEnabledSupplier;
    }

    /**
     * 快照读取两个开关值供本 turn 使用,并清空上一 turn 残留的 buffer。
     * 应在每个 turn 的 {@code onStreamStart} 调用一次。
     */
    public void onTurnStart() {
        this.streamingEnabled = streamingEnabledSupplier.getAsBoolean();
        this.showThinkingEnabled = showThinkingEnabledSupplier.getAsBoolean();
        contentBuffer.setLength(0);
    }

    /**
     * 处理一条 content delta。
     *
     * @return {@code true} 表示流式开启、应透传给下游 throttler;{@code false} 表示流式关闭、
     *         本 gate 已缓冲该 delta(下游不应再处理)。
     */
    public boolean onContentDelta(String delta) {
        if (streamingEnabled) {
            return true;
        }
        if (delta != null && !delta.isEmpty()) {
            CliOutputLimits.appendBounded(
                    contentBuffer, delta, CliOutputLimits.MAX_ASSISTANT_CHARS);
        }
        return false;
    }

    /**
     * 把本 turn 缓冲的 content 一次性 flush 给前端。流式开启时为 no-op(无缓冲)。
     * 应在 {@code onStreamEnd}(以及段边界 {@code onBlockReset})调用,保证前端在收到
     * stream-end 信号前先拿到完整内容。
     */
    public void flushContent() {
        if (streamingEnabled) {
            return;
        }
        if (contentBuffer.length() == 0) {
            return;
        }
        String full = contentBuffer.toString();
        contentBuffer.setLength(0);
        contentSink.accept(full);
    }

    /**
     * @return {@code true} 表示思考区开启、thinking delta/status 应推送;{@code false} 表示
     *         思考区关闭、应丢弃 thinking 相关推送(模型仍照常思考)。
     */
    public boolean shouldEmitThinking() {
        return showThinkingEnabled;
    }
}
