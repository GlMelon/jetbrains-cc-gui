package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.provider.common.MessageCallback;

/**
 * OpenCode 消息归一化器(透传实现)。
 * <p>
 * OpenCode 的事件解析在更底层完成——由 {@code OpenCodeCliSession}
 * 把 {@code opencode run --format json} 的事件映射到统一 {@code MSG_*}。因此到达本归一化器时,
 * 事件已是统一 {@code MSG_*} 协议,无需再做 provider 专属转换,直接透传给下游 handler。
 * <p>
 * 继承 {@link ForwardingMessageNormalizer} 空壳(SDK 调用模式已移除,仅剩 CLI 单路)。
 */
public final class OpenCodeMessageNormalizer extends ForwardingMessageNormalizer {
    public OpenCodeMessageNormalizer(MessageCallback delegate) {
        super(delegate);
    }
}
