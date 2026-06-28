package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import com.github.claudecodegui.session.SessionSendService;
import com.github.claudecodegui.session.SessionState;

/**
 * 收口三处分散的 "是否 CLI" 计算逻辑。
 * <p>
 * 原先在 SessionSendService、SessionHandler.isCliModeActive、SessionRuntimeRouter
 * 三处各自计算 effective runtime，现统一到此 resolver。
 * <p>
 * 解析逻辑(三 provider claude/codex/opencode 统一):
 * - sessionMode(会话快照)优先 > requestedMode(请求级);两者都无效时 Claude 回退 settings,Codex/OpenCode 回退「路由策略」default
 * - 解析出的 runtime 不在路由策略 supported 内 → 降级到 policy.default 并标记 degraded(供 toast 提示)
 * - 纯快照语义:已有会话锁死快照模式,设置切换只影响新会话(快照创建见 SessionLifecycleManager.initializeSessionRuntimeDefaults)
 */
public final class EffectiveRuntimeResolver {

    private EffectiveRuntimeResolver() {}

    /**
     * 解析结果。
     */
    public record Runtime(ProviderType provider, RuntimeType runtimeType, boolean degraded) {}

    /**
     * 降级时的用户面向提示文案(表现层)。runtime 未降级或为 null 时返回 null,
     * 调用方(SessionSendService.warnIfRuntimeDegraded)据此决定是否经 notifyStatusMessage 推送 toast。
     */
    public static String degradedNotice(Runtime runtime) {
        if (runtime == null || !runtime.degraded()) {
            return null;
        }
        return "请求的调用模式不在路由策略允许范围内,已回退到 "
                + runtime.runtimeType() + " 模式(见 设置-行为-路由策略)";
    }

    /**
     * 解析 effective runtime。
     *
     * @param provider        当前 provider（"claude" / "codex" / "opencode"）
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

        // 三 provider 统一 sessionMode(会话快照)优先(纯快照语义核心):
        // 快照有效 → 用快照;否则 requestedMode;两者都无效时 Claude 回退 settings,Codex/OpenCode 回退 policy.default。
        // Codex/OpenCode 的 null 兜底刻意不读 settings(Platform 依赖,测试环境 NPE),用 policy.defaultRuntime() 保持可测;
        // 生产中三者快照总有效(SessionLifecycleManager.initializeSessionRuntimeDefaults 创建时快照),null 路径仅防御。
        String snapshotMode = normalizeInvocationMode(sessionMode);
        String requestMode = normalizeInvocationMode(requestedMode);
        String effectiveMode = snapshotMode != null ? snapshotMode : requestMode;
        if (effectiveMode == null) {
            if (pt == ProviderType.CLAUDE) {
                // Claude 保留三级回退(含 settings 读取,helper 自带 catch 兜底 SDK)。
                effectiveMode = SessionSendService.resolveEffectiveClaudeInvocationMode(requestedMode, sessionMode);
            } else {
                // Codex/OpenCode 无快照无请求:回退「路由策略」面板的 default(非 settings,可单测)。
                return new Runtime(pt, providerPolicy.defaultRuntime(), false);
            }
        }
        RuntimeType requestedRuntime = RuntimeType.fromInvocationMode(effectiveMode);
        boolean degraded = !providerPolicy.supported().contains(requestedRuntime);
        RuntimeType rt = degraded ? providerPolicy.defaultRuntime() : requestedRuntime;
        return new Runtime(pt, rt, degraded);
    }

    /** 规范化 invocationMode:trim 后校验合法(cli/sdk)则返回,否则 null。纯函数,无 Platform 依赖。 */
    private static String normalizeInvocationMode(String mode) {
        if (mode == null) {
            return null;
        }
        String trimmed = mode.trim();
        return SessionState.isValidClaudeInvocationMode(trimmed) ? trimmed : null;
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
