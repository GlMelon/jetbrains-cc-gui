package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import com.github.claudecodegui.session.SessionSendService;

/**
 * 收口三处分散的 "是否 CLI" 计算逻辑。
 * <p>
 * 原先在 SessionSendService、SessionHandler.isCliModeActive、SessionRuntimeRouter
 * 三处各自计算 effective runtime，现统一到此 resolver。
 * <p>
 * 解析逻辑:
 * - Claude: 三级优先级(sessionMode > requestedMode > settings)→ CLI 或 SDK
 * - Codex: requestedMode 明确且 supported 时用之;否则取 policy.default(默认 SDK,可在「路由策略」面板切换)
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
            // codex 由前端「调用模式」UI 统一驱动(方向 A):requestedMode 明确(cli/sdk)且 supported 支持时优先用之;
            // 为 null(前端调用模式未加载)时回退到「路由策略」面板的 codex default(默认 SDK)。
            if (requestedMode != null) {
                RuntimeType requestedRuntime = RuntimeType.fromInvocationMode(requestedMode);
                if (providerPolicy.supported().contains(requestedRuntime)) {
                    return new Runtime(ProviderType.CODEX, requestedRuntime);
                }
            }
            return new Runtime(ProviderType.CODEX, providerPolicy.defaultRuntime());
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
