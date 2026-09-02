package com.github.claudecodegui.cli.codex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodexServiceTierPolicyTest {

    @Test
    public void supportsTierAdvertisedBySelectedModel() throws Exception {
        Path catalog = Files.createTempFile("codex-model-catalog", ".json");
        try {
            Files.writeString(catalog,
                    "{\"models\":[{\"slug\":\"gpt-5.3-codex\",\"service_tiers\":[\"fast\"]}]}",
                    StandardCharsets.UTF_8);

            CodexServiceTierPolicy policy = new CodexServiceTierPolicy(catalog);

            assertTrue(policy.supports("gpt-5.3-codex", "fast"));
            assertFalse(policy.supports("gpt-5.3-codex", "priority"));
            assertFalse(policy.supports("gpt-5.4", "fast"));
        } finally {
            Files.deleteIfExists(catalog);
        }
    }

    @Test
    public void supportsObjectBasedAdditionalSpeedTier() {
        JsonObject catalog = JsonParser.parseString(
                "{\"models\":[{\"id\":\"gpt-5.4\",\"additional_speed_tiers\":[{\"id\":\"fast\"}]}]}"
        ).getAsJsonObject();

        assertTrue(CodexServiceTierPolicy.supports(catalog, "gpt-5.4", "fast"));
    }

    @Test
    public void failsClosedForMissingOrMalformedCatalog() throws Exception {
        Path missing = Path.of("codex-catalog-that-does-not-exist.json");
        assertFalse(new CodexServiceTierPolicy(missing).supports("gpt-5.3-codex", "fast"));

        Path malformed = Files.createTempFile("codex-model-catalog-malformed", ".json");
        try {
            Files.writeString(malformed, "not-json", StandardCharsets.UTF_8);
            assertFalse(new CodexServiceTierPolicy(malformed).supports("gpt-5.3-codex", "fast"));
        } finally {
            Files.deleteIfExists(malformed);
        }
    }
}