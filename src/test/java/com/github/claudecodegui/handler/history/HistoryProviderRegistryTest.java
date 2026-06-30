package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertSame;

public class HistoryProviderRegistryTest {
    @Test
    public void adapterRoutesStrictProviderValue() {
        HistoryProviderAdapter claude = adapter(ProviderType.CLAUDE);
        HistoryProviderRegistry registry = new HistoryProviderRegistry(List.of(claude));

        assertSame(claude, registry.adapter(ProviderType.CLAUDE.value()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void adapterRejectsUnknownProviderInsteadOfFallingBackToClaude() {
        HistoryProviderRegistry registry = new HistoryProviderRegistry(List.of(adapter(ProviderType.CLAUDE)));

        registry.adapter("unknown");
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsDuplicateProvider() {
        new HistoryProviderRegistry(List.of(adapter(ProviderType.CLAUDE), adapter(ProviderType.CLAUDE)));
    }

    private static HistoryProviderAdapter adapter(ProviderType provider) {
        return new HistoryProviderAdapter() {
            @Override
            public ProviderType provider() {
                return provider;
            }

            @Override
            public String loadSessionsJson(String projectPath) {
                return "{\"success\":true,\"sessions\":[]}";
            }

            @Override
            public List<JsonObject> loadMessages(String sessionId, String projectPath) {
                return List.of();
            }

            @Override
            public HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException {
                return HistoryDeleteResult.none();
            }

            @Override
            public void clearCache(String projectPath) {
            }
        };
    }
}
