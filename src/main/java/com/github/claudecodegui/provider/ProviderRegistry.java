package com.github.claudecodegui.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProviderRegistry {
    private final Map<ProviderId, ProviderAdapter> adapters;

    public ProviderRegistry(List<? extends ProviderAdapter> adapters) {
        this.adapters = new LinkedHashMap<>();
        for (ProviderAdapter adapter : adapters) {
            ProviderId providerId = adapter.providerId();
            if (this.adapters.putIfAbsent(providerId, adapter) != null) {
                throw new IllegalArgumentException("Duplicate provider adapter: " + providerId.value());
            }
        }
    }

    public ProviderAdapter require(ProviderId providerId) {
        ProviderAdapter adapter = adapters.get(providerId);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown provider: " + providerId.value());
        }
        return adapter;
    }

    /**
     * 指定 Provider 是否声明支持某能力。
     *
     * <p>未知 Provider 返回 {@code false}（不抛异常）：能力探测属于“查询而非路由”，
     * 未知 Provider 自然不支持任何能力，调用方可据此优雅降级，而非中断。
     */
    public boolean hasCapability(ProviderId providerId, ProviderCapability capability) {
        ProviderAdapter adapter = adapters.get(providerId);
        return adapter != null && adapter.supports(capability);
    }

    /**
     * 指定 Provider 声明的全部能力。未知 Provider 抛 {@link IllegalArgumentException}（对称 {@link #require}）。
     */
    public Set<ProviderCapability> capabilities(ProviderId providerId) {
        return require(providerId).capabilities();
    }

    /**
     * 声明支持指定能力的全部 Provider（按注册顺序）。
     */
    public List<ProviderId> providersWithCapability(ProviderCapability capability) {
        return adapters.values().stream()
                .filter(adapter -> adapter.supports(capability))
                .map(ProviderAdapter::providerId)
                .toList();
    }
}
