package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.provider.common.MessageCallback;

/**
 * OpenCode 消息归一化器(透传实现)。
 * <p>
 * OpenCode 的事件解析在更底层完成——SDK 模式由 {@code OpenCodeSDKBridge.processOutputLine}
 * 把 channel.js 产出的 NDJSON 映射到统一 {@code MSG_*};CLI 模式由 {@code OpenCodeCliSession}
 * 把 {@code opencode run --format json} 的事件映射到 {@code MSG_*}。因此到达本归一化器时,
 * 事件已是统一 {@code MSG_*} 协议,无需再做 provider 专属转换,直接透传给下游 handler。
 * <p>
 * 与 {@link ClaudeSdkMessageNormalizer} 同构(均继承 {@link ForwardingMessageNormalizer} 空壳);
 * CLI/SDK 两路复用同一类(注册表按 (provider,runtime) 路由,不区分同类)。
 */
public final class OpenCodeMessageNormalizer extends ForwardingMessageNormalizer {
    public OpenCodeMessageNormalizer(MessageCallback delegate) {
        super(delegate);
    }
}
