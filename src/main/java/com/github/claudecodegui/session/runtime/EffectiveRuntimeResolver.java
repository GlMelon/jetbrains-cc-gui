package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import com.github.claudecodegui.session.SessionSendService;

/**
 * 收口三处分散的 "是否 CLI" 计算逻辑。
 * <p>
 * 原先在 SessionSendService、SessionHandler.isCliModeActive、SessionRuntimeRouter
 * 三处各自计算 effective runtime，现统一到此 resolver。
 * <p>
 * 初始实现保持与当前硬编码行为完全一致（零行为变化）：
 * - Claude: 三级优先级（sessionMode > requestedMode > settings）→ CLI 或 SDK
 * - Codex: 由 runtime policy 决定，默认 policy 仍保持 CLI
 */
public final class EffectiveRuntimeResolver {

    private EffectiveRuntimeResolver() {}

    /**
     * 解析结果。
     */
    public record Runtime(ProviderType provider, RuntimeType runtimeType) {}

    /**
     * 解析 effective runtime。
     *
     * @param provider        当前 provider（"claude" / "codex"）
     * @param sessionMode     会话级 invocationMode（可能为 null）
     * @param requestedMode   请求级 invocationMode（可能为 null）
     * @return 解析后的 (ProviderType, RuntimeType)
     */
    public static Runtime resolve(
            String provider,
            String sessionMode,
            String requestedMode,
            RuntimePolicyConfig policy
    ) {
        ProviderType pt = ProviderType.fromString(provider);
        RuntimePolicyConfig effectivePolicy = policy != null ? policy : RuntimePolicyConfig.getDefault();
        ProviderRuntimePolicy providerPolicy = effectivePolicy.of(pt);
        if (providerPolicy == null || !providerPolicy.enabled()) {
            throw new IllegalStateException("Provider disabled/unknown: " + pt.toLowerCase());
        }

        if (pt == ProviderType.CODEX) {
            RuntimeType requestedRuntime = RuntimeType.fromInvocationMode(requestedMode);
            RuntimeType runtime = providerPolicy.supported().contains(requestedRuntime)
                    ? requestedRuntime
                    : providerPolicy.defaultRuntime();
            return new Runtime(ProviderType.CODEX, runtime);
        }

        // Claude: 三级优先级，复用 SessionSendService 的解析逻辑
        String effectiveMode = SessionSendService.resolveEffectiveClaudeInvocationMode(requestedMode, sessionMode);
        RuntimeType requestedRuntime = RuntimeType.fromInvocationMode(effectiveMode);
        RuntimeType rt = providerPolicy.supported().contains(requestedRuntime)
                ? requestedRuntime
                : providerPolicy.defaultRuntime();
        return new Runtime(ProviderType.CLAUDE, rt);
    }

    /**
     * 判断给定 provider + invocationMode 是否为 CLI 模式。
     * 替代 SessionHandler.isCliModeActive()。
     *
     * @param provider             当前 provider
     * @param requestedInvocationMode 请求级 invocationMode
     * @param sessionInvocationMode   会话级 invocationMode
     * @return true if CLI mode
     */
    public static boolean isCliMode(
            String provider,
            String requestedInvocationMode,
            String sessionInvocationMode,
            RuntimePolicyConfig policy
    ) {
        Runtime runtime = resolve(provider, sessionInvocationMode, requestedInvocationMode, policy);
        return runtime.runtimeType() == RuntimeType.CLI;
    }
}
