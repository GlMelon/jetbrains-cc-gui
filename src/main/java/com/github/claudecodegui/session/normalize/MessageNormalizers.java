package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.common.MessageCallback;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 消息归一化器注册表入口(总则五·开闭 / E2)。
 * <p>
 * 按 (provider, runtime) 从 {@link MessageNormalizerFactory} 注册表查表创建归一化器,
 * 取代原先二级嵌套 if/else。新增 provider×runtime 组合只需在 {@link #FACTORIES} 加一行
 * entry,forRuntime 路由主体不变。
 * <p>
 * 回退语义(保持与原 if/else 完全一致):
 * <ul>
 *   <li>未知 provider → 回退 {@code claude}(原「非 codex 即 claude」);</li>
 *   <li>未知 runtime → 回退 {@code sdk}(原「非 cli 即 sdk」)。</li>
 * </ul>
 * 两维度独立归一(provider 与 runtime 各自判定已知),确保 {@code codex+未知runtime}
 * 仍路由到 CodexSdk(provider 优先保留),不被错误回退到 Claude。
 */
public final class MessageNormalizers {

    /** 默认回退 provider(原 if/else 中「非 codex 即 claude」语义)。 */
    private static final String DEFAULT_PROVIDER = CommonConstants.PROVIDER_CLAUDE;
    /** 默认回退 runtime(原 if/else 中「非 cli 即 sdk」语义)。 */
    private static final String DEFAULT_RUNTIME = CommonConstants.INVOCATION_MODE_SDK;

    /**
     * 归一化器工厂注册表。新增 provider×runtime 组合只需在此加一行 entry。
     */
    private static final List<MessageNormalizerFactory> FACTORIES = List.of(
            entry(CommonConstants.PROVIDER_CLAUDE, CommonConstants.INVOCATION_MODE_CLI, ClaudeCliMessageNormalizer::new),
            entry(CommonConstants.PROVIDER_CLAUDE, CommonConstants.INVOCATION_MODE_SDK, ClaudeSdkMessageNormalizer::new),
            entry(CommonConstants.PROVIDER_CODEX, CommonConstants.INVOCATION_MODE_CLI, CodexCliMessageNormalizer::new),
            entry(CommonConstants.PROVIDER_CODEX, CommonConstants.INVOCATION_MODE_SDK, CodexSdkMessageNormalizer::new)
    );

    private MessageNormalizers() {
    }

    public static MessageCallback forRuntime(String provider, String runtime, MessageCallback delegate) {
        String normalizedProvider = normalize(provider);
        String normalizedRuntime = normalize(runtime);
        return resolve(normalizedProvider, normalizedRuntime).create(delegate);
    }

    /**
     * 解析归一化器工厂(E2·开闭路由):
     * <ol>
     *   <li>未知 provider 回退 {@link #DEFAULT_PROVIDER};</li>
     *   <li>未知 runtime 回退 {@link #DEFAULT_RUNTIME}(两维度独立);</li>
     *   <li>注册表精确 {@link MessageNormalizerFactory#supports} 匹配。</li>
     * </ol>
     */
    private static MessageNormalizerFactory resolve(String provider, String runtime) {
        String effectiveProvider = knownProvider(provider) ? provider : DEFAULT_PROVIDER;
        String effectiveRuntime = knownRuntime(runtime) ? runtime : DEFAULT_RUNTIME;
        for (MessageNormalizerFactory factory : FACTORIES) {
            if (factory.supports(effectiveProvider, effectiveRuntime)) {
                return factory;
            }
        }
        throw new IllegalStateException(
                "No message normalizer for provider=" + provider + ", runtime=" + runtime);
    }

    private static boolean knownProvider(String provider) {
        for (MessageNormalizerFactory factory : FACTORIES) {
            if (factory.provider().equals(provider)) {
                return true;
            }
        }
        return false;
    }

    private static boolean knownRuntime(String runtime) {
        for (MessageNormalizerFactory factory : FACTORIES) {
            if (factory.runtime().equals(runtime)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static MessageNormalizerFactory entry(
            String provider, String runtime, Function<MessageCallback, MessageCallback> constructor) {
        return new MessageNormalizerFactory() {
            @Override
            public String provider() {
                return provider;
            }

            @Override
            public String runtime() {
                return runtime;
            }

            @Override
            public MessageCallback create(MessageCallback delegate) {
                return constructor.apply(delegate);
            }
        };
    }
}
