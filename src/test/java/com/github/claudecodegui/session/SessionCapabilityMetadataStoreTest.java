package com.github.claudecodegui.session;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionCapabilityMetadataStoreTest {

    @Test
    public void saveFindAndRemoveAreScopedByProviderAndSession() throws Exception {
        SessionCapabilityMetadataStore store = newStore();
        store.save("claude", "s1", SessionNegotiatedCapabilities.cli(true, true, false), 10L);
        store.save("codex", "s1", SessionNegotiatedCapabilities.cli(false, true, false), 20L);

        JsonObject claude = store.find("claude", "s1");
        JsonObject codex = store.find("codex", "s1");
        assertNotNull(claude);
        assertNotNull(codex);
        assertTrue(claude.get("thinkingAvailable").getAsBoolean());
        assertTrue(!codex.get("thinkingAvailable").getAsBoolean());

        store.remove("claude", "s1");
        assertNull(store.find("claude", "s1"));
        assertNotNull(store.find("codex", "s1"));
    }

    @Test
    public void serializedNullDegradationReasonIsRetained() throws Exception {
        SessionCapabilityMetadataStore store = newStore();
        store.save("claude", "s1", SessionNegotiatedCapabilities.cli(true, true, false), 10L);

        JsonObject snapshot = store.find("claude", "s1");
        assertNotNull(snapshot);
        assertTrue(snapshot.get("degradationReason").isJsonNull());
    }

    @Test
    public void corruptedMetadataIsIgnoredAndCanBeReplaced() throws Exception {
        SessionCapabilityMetadataStore store = newStore();
        Files.createDirectories(store.metadataPathForTest().getParent());
        Files.writeString(store.metadataPathForTest(), "not-json", StandardCharsets.UTF_8);

        assertNull(store.find("claude", "s1"));
        store.save("claude", "s1", SessionNegotiatedCapabilities.cli(true, true, false), 10L);

        assertNotNull(store.find("claude", "s1"));
    }

    @Test
    public void invalidKeysDoNotCreateMetadataFile() throws Exception {
        SessionCapabilityMetadataStore store = newStore();
        store.save("", "s1", SessionNegotiatedCapabilities.cli(true, true, false), 10L);
        store.save("claude", "", SessionNegotiatedCapabilities.cli(true, true, false), 10L);

        assertFalse(Files.exists(store.metadataPathForTest()));
    }

    private static SessionCapabilityMetadataStore newStore() throws Exception {
        Path directory = Files.createTempDirectory("session-capability-store");
        return new SessionCapabilityMetadataStore(directory.resolve("session-capabilities.json"));
    }
}
