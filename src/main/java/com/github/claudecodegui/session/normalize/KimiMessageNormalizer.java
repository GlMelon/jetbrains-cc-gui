package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.provider.common.MessageCallback;

/**
 * Kimi 消息归一化器(透传实现)。
 * <p>
 * Kimi 的事件解析在更底层完成——CLI 模式由 {@code KimiCliSession}
 * 把 {@code kimi run --format json} 的 marker 事件映射到统一 {@code MSG_*}。因此到达本归一化器时,
 * 事件已是统一 {@code MSG_*} 协议,无需再做 provider 专属转换,直接透传给下游 handler。
 * <p>
 * 与 {@link OpenCodeMessageNormalizer} 同构(均继承
 * {@link ForwardingMessageNormalizer} 空壳);CLI 两路复用同一类(注册表按 (provider,runtime)
 * 路由,不区分同类)。
 */
public final class KimiMessageNormalizer extends ForwardingMessageNormalizer {
    public KimiMessageNormalizer(MessageCallback delegate) {
        super(delegate);
    }
}
