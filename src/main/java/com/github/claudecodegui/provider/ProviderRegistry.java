package com.github.claudecodegui.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}
