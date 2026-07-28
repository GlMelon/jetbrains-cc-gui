package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class HistoryProviderRegistryTest {
    @Test
    public void adapterRoutesStrictProviderValue() {
        HistoryProviderAdapter claude = adapter(ProviderType.CLAUDE, Set.of(HistoryCapability.DELETE));
        HistoryProviderRegistry registry = new HistoryProviderRegistry(List.of(claude));

        assertSame(claude, registry.adapter(ProviderType.CLAUDE.value()));
    }

    @Test
    public void capabilitiesAreBackendAuthoritativePerProvider() {
        HistoryProviderRegistry registry = new HistoryProviderRegistry(List.of(
                adapter(ProviderType.CLAUDE, Set.of(HistoryCapability.DELETE)),
                adapter(ProviderType.CODEX, Set.of(HistoryCapability.DELETE)),
                adapter(ProviderType.OPENCODE, Set.of(HistoryCapability.ARCHIVE))));

        assertEquals(new HistoryCapabilities(true, false), registry.capabilities(ProviderType.CLAUDE.value()));
        assertEquals(new HistoryCapabilities(true, false), registry.capabilities(ProviderType.CODEX.value()));
        assertEquals(new HistoryCapabilities(false, true), registry.capabilities(ProviderType.OPENCODE.value()));
        assertTrue(registry.supports(ProviderType.CLAUDE.value(), HistoryCapability.DELETE));
        assertFalse(registry.supports(ProviderType.CLAUDE.value(), HistoryCapability.ARCHIVE));
        assertTrue(registry.supports(ProviderType.OPENCODE.value(), HistoryCapability.ARCHIVE));
        assertFalse(registry.supports(ProviderType.OPENCODE.value(), HistoryCapability.DELETE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void adapterRejectsUnknownProviderInsteadOfFallingBackToClaude() {
        HistoryProviderRegistry registry = new HistoryProviderRegistry(List.of(
                adapter(ProviderType.CLAUDE, Set.of(HistoryCapability.DELETE))));

        registry.adapter("unknown");
    }

    @Test(expected = IllegalArgumentException.class)
    public void constructorRejectsDuplicateProvider() {
        new HistoryProviderRegistry(List.of(
                adapter(ProviderType.CLAUDE, Set.of()),
                adapter(ProviderType.CLAUDE, Set.of())));
    }

    private static HistoryProviderAdapter adapter(ProviderType provider, Set<HistoryCapability> capabilities) {
        return new HistoryProviderAdapter() {
            @Override
            public ProviderType provider() {
                return provider;
            }

            @Override
            public Set<HistoryCapability> capabilities() {
                return capabilities;
            }

            @Override
            public String loadSessionsJson(String projectPath) {
                return "{\"success\":true,\"sessions\":[]}";
            }

            @Override
            public HistoryMessageBatch loadMessages(
                    String sessionId,
                    String projectPath,
                    HistoryMessageReadPolicy policy
            ) {
                return HistoryMessageBatch.empty();
            }

            @Override
            public void clearCache(String projectPath) {
            }
        };
    }
}
