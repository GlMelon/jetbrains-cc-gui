package com.github.claudecodegui.config;



import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 路由策略配置。
 * <p>
 * 管理 provider×runtime 矩阵。默认配置中 Claude 与 Codex 对称:均支持 SDK+CLI,默认 SDK。
 * 存储于 ~/.codemoss/config.json 的 "runtime" 节点。
 */
public class RuntimePolicyConfig {

    private Map<ProviderType, ProviderRuntimePolicy> providers;

    /**
     * 构建默认配置(Claude/Codex/OpenCode 对称:均支持 SDK+CLI,默认 SDK)。
     * <ul>
     *   <li>Claude: enabled, 支持 SDK+CLI, 默认 SDK</li>
     *   <li>Codex: enabled, 支持 SDK+CLI, 默认 SDK</li>
     *   <li>OpenCode: enabled, 支持 SDK+CLI, 默认 SDK</li>
     * </ul>
     */
    private static final RuntimePolicyConfig DEFAULT = buildDefault();

    public RuntimePolicyConfig() {
        this.providers = new LinkedHashMap<>();
    }

    public RuntimePolicyConfig(Map<ProviderType, ProviderRuntimePolicy> providers) {
        this.providers = providers != null ? new LinkedHashMap<>(providers) : new LinkedHashMap<>();
    }

    public Map<ProviderType, ProviderRuntimePolicy> providers() {
        return providers;
    }

    public void setProviders(Map<ProviderType, ProviderRuntimePolicy> providers) {
        this.providers = providers != null ? new LinkedHashMap<>(providers) : new LinkedHashMap<>();
    }

    /**
     * 获取指定 provider 的策略。不存在则返回 null。
     */
    public ProviderRuntimePolicy of(ProviderType provider) {
        return providers.get(provider);
    }

    /**
     * 以默认策略为基底合并本配置(向后兼容):补全默认存在而本配置缺失的 provider,
     * 保留用户对已知 provider 的自定义(含显式 enabled=false 的禁用)。
     * <p>
     * 修复存量用户旧 config.json 在「OpenCode 加入路由策略」之前持久化、缺失 opencode →
     * {@code of(OPENCODE)=null} → {@code EffectiveRuntimeResolver.resolve} 抛
     * "Provider disabled/unknown: opencode" → opencode+CLI 请求被强制回退 SDK 静默失败的
     * 向后兼容 bug(2026-06-28 复现)。
     *
     * @return 合并后的完整策略(默认基底 + 本配置覆盖)
     */
    public RuntimePolicyConfig mergeWithDefaults() {
        var merged = new LinkedHashMap<>(getDefault().providers());
        merged.putAll(this.providers);
        return new RuntimePolicyConfig(merged);
    }

    /**
     * 返回默认配置(Claude 与 Codex 对称)。
     */
    public static RuntimePolicyConfig getDefault() {
        return new RuntimePolicyConfig(new LinkedHashMap<>(DEFAULT.providers()));
    }

    private static RuntimePolicyConfig buildDefault() {
        var m = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        m.put(ProviderType.CLAUDE, new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));
        m.put(ProviderType.CODEX, new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));
        m.put(ProviderType.OPENCODE, new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));
        return new RuntimePolicyConfig(m);
    }
}
