package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.MessageCallback;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 消息归一化器注册表入口(总则五·开闭 / E2)。
 * <p>
 * 按 provider 从 {@link MessageNormalizerFactory} 注册表查表创建归一化器,
 * 取代原先二级嵌套 if/else。新增 provider 只需在 {@link #FACTORIES} 加一行
 * entry,forProvider 路由主体不变。
 * <p>
 * SDK 调用模式已移除,runtime 维度已消除——归一化器按 provider 单维路由。
 * 回退语义(保持与原 if/else 完全一致):未知 provider → 回退 {@code claude}
 * (原「非 codex 即 claude」)。
 */
public final class MessageNormalizers {

    /** 默认回退 provider(原 if/else 中「非 codex 即 claude」语义)。 */
    private static final String DEFAULT_PROVIDER = CommonConstants.PROVIDER_CLAUDE;

    /**
     * 归一化器工厂注册表。新增 provider 只需在此加一行 entry。
     * SDK 调用模式已移除——SDK 专属归一化器注册随之删除。
     */
    private static final List<MessageNormalizerFactory> FACTORIES = List.of(
            entry(CommonConstants.PROVIDER_CLAUDE, ClaudeCliMessageNormalizer::new),
            entry(CommonConstants.PROVIDER_CODEX, CodexCliMessageNormalizer::new),
            // B6: OpenCode 事件经 OpenCodeCliSession 已归一为统一 MSG_*,
            // 归一化器仅需透传(纯 ForwardingMessageNormalizer 空壳)。
            entry(CommonConstants.PROVIDER_OPENCODE, OpenCodeMessageNormalizer::new),
            // Grok/Kimi/Pi: CLI-only providers, events normalized by MarkerCliStreamParser in CliSession,
            // normalizer is pure passthrough (same pattern as OpenCode).
            entry(CommonConstants.PROVIDER_GROK, GrokMessageNormalizer::new),
            entry(CommonConstants.PROVIDER_KIMI, KimiMessageNormalizer::new),
            entry(CommonConstants.PROVIDER_PI, PiMessageNormalizer::new)
    );

    private MessageNormalizers() {
    }

    public static MessageCallback forProvider(String provider, MessageCallback delegate) {
        String normalizedProvider = normalize(provider);
        return resolve(normalizedProvider).create(delegate);
    }

    /**
     * 解析归一化器工厂(E2·开闭路由):
     * <ol>
     *   <li>未知 provider 回退 {@link #DEFAULT_PROVIDER};</li>
     *   <li>注册表精确 {@link MessageNormalizerFactory#supports} 匹配。</li>
     * </ol>
     */
    private static MessageNormalizerFactory resolve(String provider) {
        String effectiveProvider = knownProvider(provider) ? provider : DEFAULT_PROVIDER;
        for (MessageNormalizerFactory factory : FACTORIES) {
            if (factory.supports(effectiveProvider)) {
                return factory;
            }
        }
        throw new IllegalStateException(
                "No message normalizer for provider=" + provider);
    }

    private static boolean knownProvider(String provider) {
        for (MessageNormalizerFactory factory : FACTORIES) {
            if (factory.provider().equals(provider)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static MessageNormalizerFactory entry(
            String provider, Function<MessageCallback, MessageCallback> constructor) {
        return new MessageNormalizerFactory() {
            @Override
            public String provider() {
                return provider;
            }

            @Override
            public MessageCallback create(MessageCallback delegate) {
                return constructor.apply(delegate);
            }
        };
    }
}
