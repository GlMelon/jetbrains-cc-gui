package com.github.claudecodegui.provider;

import com.github.claudecodegui.provider.claude.ClaudeProviderAdapter;
import com.github.claudecodegui.provider.codex.CodexProviderAdapter;
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

    @Test
    public void claudeAdapterExposesProviderMetadata() {
        ProviderAdapter adapter = new ClaudeProviderAdapter();

        assertEquals(ProviderId.CLAUDE, adapter.providerId());
        assertEquals(ProviderId.CLAUDE, adapter.viewModel().providerId());
        assertEquals("Claude", adapter.viewModel().displayName());
    }

    @Test
    public void codexAdapterExposesProviderMetadata() {
        ProviderAdapter adapter = new CodexProviderAdapter();

        assertEquals(ProviderId.CODEX, adapter.providerId());
        assertEquals(ProviderId.CODEX, adapter.viewModel().providerId());
        assertEquals("Codex", adapter.viewModel().displayName());
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

        @Override
        public ProviderViewModel viewModel() {
            return new ProviderViewModel(providerId, providerId.value());
        }
    }
}
