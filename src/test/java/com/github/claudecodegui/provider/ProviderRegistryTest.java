package com.github.claudecodegui.provider;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class ProviderRegistryTest {

    @Test
    public void providerIdNormalizesIncomingValues() {
        assertEquals(ProviderId.CLAUDE, ProviderId.of(" CLAUDE "));
        assertEquals(new ProviderId(""), ProviderId.of(null));
    }

    @Test
    public void requiresRegisteredAdapterByProviderId() {
        ProviderAdapter claude = new FakeProviderAdapter(ProviderId.CLAUDE);
        ProviderRegistry registry = new ProviderRegistry(List.of(claude));

        assertSame(claude, registry.require(ProviderId.of("claude")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownProviderId() {
        ProviderRegistry registry = new ProviderRegistry(List.of());

        registry.require(ProviderId.of("missing"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateProviderIds() {
        new ProviderRegistry(List.of(
                new FakeProviderAdapter(ProviderId.CLAUDE),
                new FakeProviderAdapter(ProviderId.of("CLAUDE"))
        ));
    }

    private static final class FakeProviderAdapter implements ProviderAdapter {
        private final ProviderId providerId;

        private FakeProviderAdapter(ProviderId providerId) {
            this.providerId = providerId;
        }

        @Override
        public ProviderId providerId() {
            return providerId;
        }
    }
}
