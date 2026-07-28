package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistorySessionsJsonEnhancerTest {

    @Test
    public void normalizeSessionsJsonReturnsEmptySessionsForMissingInput() {
        assertEquals(HistorySessionsJsonEnhancer.EMPTY_SESSIONS_JSON,
                HistorySessionsJsonEnhancer.normalizeSessionsJson(null));
        assertEquals(HistorySessionsJsonEnhancer.EMPTY_SESSIONS_JSON,
                HistorySessionsJsonEnhancer.normalizeSessionsJson(""));
    }

    @Test
    public void normalizeSessionsJsonKeepsValidSessionPayload() {
        String json = "{\"success\":true,\"sessions\":[{\"sessionId\":\"s1\"}]}";

        assertEquals(json, HistorySessionsJsonEnhancer.normalizeSessionsJson(json));
    }

    @Test
    public void normalizeSessionsJsonAddsMissingSessionsArray() {
        JsonObject result = GsonHolder.GSON.fromJson(
                HistorySessionsJsonEnhancer.normalizeSessionsJson("{\"success\":true}"), JsonObject.class);

        assertTrue(result.get("success").getAsBoolean());
        assertTrue(result.get("sessions").isJsonArray());
        assertEquals(0, result.getAsJsonArray("sessions").size());
    }

    @Test
    public void enhanceHistoryWithFavoritesAddsProviderAndFavoriteState() {
        String history = "{\"success\":true,\"sessions\":[{\"sessionId\":\"s1\"},{\"sessionId\":\"s2\"}]}";
        String favorites = "{\"s1\":{\"favoritedAt\":123}}";

        JsonObject result = GsonHolder.GSON.fromJson(
                HistorySessionsJsonEnhancer.enhanceHistoryWithFavorites(history, ProviderType.CODEX.value(), favorites),
                JsonObject.class);

        JsonObject first = result.getAsJsonArray("sessions").get(0).getAsJsonObject();
        JsonObject second = result.getAsJsonArray("sessions").get(1).getAsJsonObject();
        assertEquals(ProviderType.CODEX.value(), first.get("provider").getAsString());
        assertTrue(first.get("isFavorited").getAsBoolean());
        assertEquals(123L, first.get("favoritedAt").getAsLong());
        assertFalse(second.get("isFavorited").getAsBoolean());
        assertTrue(result.get("favorites").isJsonObject());
    }

    @Test
    public void enhanceHistoryWithTitlesOverridesTitle() {
        String history = "{\"success\":true,\"sessions\":[{\"sessionId\":\"s1\",\"title\":\"old\"}]}";
        String titles = "{\"s1\":{\"customTitle\":\"new\"}}";

        JsonObject result = GsonHolder.GSON.fromJson(
                HistorySessionsJsonEnhancer.enhanceHistoryWithTitles(history, titles), JsonObject.class);

        JsonObject session = result.getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertEquals("new", session.get("title").getAsString());
        assertTrue(session.get("hasCustomTitle").getAsBoolean());
    }

    @Test
    public void enhanceHistoryWithCapabilitiesAddsBackendCapabilityPayload() {
        JsonObject result = GsonHolder.GSON.fromJson(
                HistorySessionsJsonEnhancer.enhanceHistoryWithCapabilities(
                        "{\"success\":true,\"sessions\":[]}",
                        new HistoryCapabilities(false, true)),
                JsonObject.class);

        JsonObject capabilities = result.getAsJsonObject("capabilities");
        assertFalse(capabilities.get("canDelete").getAsBoolean());
        assertTrue(capabilities.get("canArchive").getAsBoolean());
    }

    @Test
    public void enhanceHistoryWithCapabilitiesDefaultsToNoCapabilities() {
        JsonObject result = GsonHolder.GSON.fromJson(
                HistorySessionsJsonEnhancer.enhanceHistoryWithCapabilities(
                        "{\"success\":true,\"sessions\":[]}", null),
                JsonObject.class);

        JsonObject capabilities = result.getAsJsonObject("capabilities");
        assertFalse(capabilities.get("canDelete").getAsBoolean());
        assertFalse(capabilities.get("canArchive").getAsBoolean());
    }
}
