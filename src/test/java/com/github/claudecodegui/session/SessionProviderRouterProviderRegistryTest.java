package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.ProviderAdapter;
import com.github.claudecodegui.provider.ProviderId;
import com.github.claudecodegui.provider.ProviderRegistry;
import com.github.claudecodegui.provider.ProviderViewModel;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class SessionProviderRouterProviderRegistryTest {

    @Test
    public void delegatesSessionOperationsToRegisteredProviderAdapter() {
        RecordingProviderAdapter claude = new RecordingProviderAdapter(ProviderId.CLAUDE);
        RecordingProviderAdapter codex = new RecordingProviderAdapter(ProviderId.CODEX);
        SessionProviderRouter router = new SessionProviderRouter(new ProviderRegistry(List.of(claude, codex)));

        router.cleanupProviderSession("codex", "session-1", "/workspace");
        List<JsonObject> messages = router.getSessionMessages("codex", "session-1", "/workspace");

        assertSame(codex.messages, messages);
        assertEquals(1, codex.cleanupCalls);
        assertEquals(1, codex.getMessagesCalls);
        assertEquals(0, claude.cleanupCalls);
        assertEquals("session-1", codex.lastSessionId);
        assertEquals("/workspace", codex.lastCwd);
    }

    private static final class RecordingProviderAdapter implements ProviderAdapter {
        private final ProviderId providerId;
        private final List<JsonObject> messages = List.of(new JsonObject());
        private int cleanupCalls;
        private int getMessagesCalls;
        private String lastSessionId;
        private String lastCwd;

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
            cleanupCalls++;
            lastSessionId = sessionId;
            lastCwd = cwd;
        }

        @Override
        public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
            getMessagesCalls++;
            lastSessionId = sessionId;
            lastCwd = cwd;
            return messages;
        }
    }
}
