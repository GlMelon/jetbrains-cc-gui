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
