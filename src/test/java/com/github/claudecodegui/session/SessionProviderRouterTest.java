package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderRegistry;
import com.github.claudecodegui.provider.ProviderViewModel;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class SessionProviderRouterTest {

    @Test
    public void cleanupProviderSessionClearsCodexThreadOnlyForCodex() {
        RecordingProviderAdapter codexAdapter = new RecordingProviderAdapter(ProviderId.CODEX);
        SessionProviderRouter router = new SessionProviderRouter(new ProviderRegistry(List.of(
                new RecordingProviderAdapter(ProviderId.CLAUDE),
                codexAdapter
        )));

        router.cleanupProviderSession("claude", "session-a", "/workspace/a");
        assertEquals(0, codexAdapter.clearCalls);

        router.cleanupProviderSession("codex", "thread-b", "/workspace/b");
        assertEquals(1, codexAdapter.clearCalls);
        assertEquals("thread-b", codexAdapter.lastThreadId);
    }

    private static class RecordingProviderAdapter implements ProviderAdapter {
        private final ProviderId providerId;
        private int clearCalls;
        private String lastThreadId;

        private RecordingProviderAdapter(ProviderId providerId) {
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

        @Override
        public void cleanupProviderSession(String sessionId, String cwd) {
            clearCalls++;
            lastThreadId = sessionId;
        }
    }
}
