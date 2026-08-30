package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.MessageBlockContract;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HistoryProviderRegistry {
    private final Map<ProviderType, HistoryProviderAdapter> adapters;

    HistoryProviderRegistry(List<? extends HistoryProviderAdapter> adapters) {
        this.adapters = new LinkedHashMap<>();
        for (HistoryProviderAdapter adapter : adapters) {
            if (this.adapters.putIfAbsent(adapter.provider(), adapter) != null) {
                throw new IllegalArgumentException("Duplicate history provider: " + adapter.provider().value());
            }
        }
    }

    static HistoryProviderRegistry createDefault(HandlerContext context) {
        return new HistoryProviderRegistry(List.of(
                new ClaudeHistoryProviderAdapter(),
                new CodexHistoryProviderAdapter(),
                new OpenCodeHistoryProviderAdapter(),
                new GrokHistoryProviderAdapter(),
                new KimiHistoryProviderAdapter(),
                new PiHistoryProviderAdapter(),
                new OmpHistoryProviderAdapter(),
                new DshHistoryProviderAdapter()
        ));
    }

    HistoryProviderAdapter adapter(String provider) {
        String normalizedProvider = provider == null ? null : provider.trim().toLowerCase(Locale.ROOT);
        ProviderType providerType = ProviderType.fromValue(normalizedProvider)
                .orElseThrow(() -> new IllegalArgumentException("Unknown history provider: " + provider));
        HistoryProviderAdapter adapter = adapters.get(providerType);
        if (adapter == null) {
            throw new IllegalArgumentException("No history provider registered: " + providerType.value());
        }
        return adapter;
    }

    HistoryCapabilities capabilities(String provider) {
        return HistoryCapabilities.from(adapter(provider));
    }

    boolean supports(String provider, HistoryCapability capability) {
        return adapter(provider).supports(capability);
    }

    HistoryMessageBatch loadMessages(
            String provider,
            String sessionId,
            String projectPath,
            HistoryMessageReadPolicy policy
    ) {
        HistoryMessageBatch raw = adapter(provider).loadMessages(sessionId, projectPath, policy);
        List<JsonObject> normalized = MessageBlockContract.normalizeHistoryMessages(raw.messages());
        return new HistoryMessageBatch(normalized, raw.totalMessageCount());
    }
}
