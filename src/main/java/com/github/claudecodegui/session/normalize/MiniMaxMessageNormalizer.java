package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.provider.common.MessageCallback;

/**
 * MiniMax 消息归一化器(透传实现)。
 * <p>
 * MiniMax 的事件解析在更底层完成——CLI 模式由 {@code MiniMaxCliSession}
 * 把 {@code mcode exec --output-format stream-json} 的 NDJSON 事件映射到统一 {@code MSG_*}。
 * 因此到达本归一化器时,事件已是统一协议,无需 provider 专属转换,直接透传给下游 handler。
 * <p>
 * 与 {@link PiMessageNormalizer} 同构(均继承 {@link ForwardingMessageNormalizer} 空壳)。
 */
public final class MiniMaxMessageNormalizer extends ForwardingMessageNormalizer {
    public MiniMaxMessageNormalizer(MessageCallback delegate) {
        super(delegate);
    }
}
