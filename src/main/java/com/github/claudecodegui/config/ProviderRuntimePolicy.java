package com.github.claudecodegui.config;

import com.github.claudecodegui.session.runtime.RuntimeType;

import java.util.Set;

/**
 * 单个 provider 的路由策略。
 *
 * @param enabled         是否启用此 provider
 * @param supported       支持的 runtime 类型集合（SDK/CLI）
 * @param defaultRuntime  默认 runtime 类型（必须在 supported 集合内）
 */
public record ProviderRuntimePolicy(
        boolean enabled,
        Set<RuntimeType> supported,
        RuntimeType defaultRuntime
) {
    public ProviderRuntimePolicy {
        if (enabled && (supported == null || supported.isEmpty())) {
            throw new IllegalArgumentException("Enabled provider must have at least one supported runtime");
        }
        if (enabled && defaultRuntime != null && !supported.contains(defaultRuntime)) {
            throw new IllegalArgumentException("Default runtime must be in supported set");
        }
    }
}
