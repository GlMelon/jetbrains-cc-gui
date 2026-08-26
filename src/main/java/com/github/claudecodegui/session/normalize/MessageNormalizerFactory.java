package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.provider.common.MessageCallback;

/**
 * 消息归一化器工厂接口(总则五·开闭)。
 * <p>
 * 每个 impl 声明自己的 provider 路由键并提供归一化器创建能力。
 * {@link MessageNormalizers} 遍历注册表按 {@link #supports} 匹配,
 * 取代原先 provider×runtime 二级嵌套 if/else。
 * <p>
 * runtime 维度已消除(SDK 调用模式已移除),工厂按 provider 单维路由。
 * 范式对齐 {@link com.github.claudecodegui.session.runtime.SessionRuntime}(
 * provider() + 注册表查表)。
 */
interface MessageNormalizerFactory {

    /** 此工厂对应的 provider 标识(如 {@code "claude"}/{@code "codex"})。 */
    String provider();

    /**
     * 是否支持给定 provider。
     * 默认精确匹配 provider。
     */
    default boolean supports(String provider) {
        return provider().equals(provider);
    }

    /** 用给定 delegate 创建归一化器实例(每次调用产生新包装器)。 */
    MessageCallback create(MessageCallback delegate);
}
