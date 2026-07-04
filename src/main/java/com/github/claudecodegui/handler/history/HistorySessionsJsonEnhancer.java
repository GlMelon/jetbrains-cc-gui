package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Normalizes provider session-list JSON and enriches it with metadata used by the history UI.
 */
class HistorySessionsJsonEnhancer {
    private static final Logger LOG = Logger.getInstance(HistorySessionsJsonEnhancer.class);
    static final String EMPTY_SESSIONS_JSON = "{\"success\":true,\"sessions\":[]}";

    private final NodeJsServiceCaller nodeJsServiceCaller;

    HistorySessionsJsonEnhancer(NodeJsServiceCaller nodeJsServiceCaller) {
        this.nodeJsServiceCaller = nodeJsServiceCaller;
    }

    String enhance(String provider, String rawJson) {
        String normalizedJson = normalizeSessionsJson(rawJson);
        String favoritesJson = loadFavoritesJson();
        String withFavorites = enhanceHistoryWithFavorites(normalizedJson, provider, favoritesJson);
        String titlesJson = loadTitlesJson();
        return enhanceHistoryWithTitles(withFavorites, titlesJson);
    }

    static String normalizeSessionsJson(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return EMPTY_SESSIONS_JSON;
        }
        try {
            JsonObject history = GsonHolder.GSON.fromJson(rawJson, JsonObject.class);
            if (history == null) {
                return EMPTY_SESSIONS_JSON;
            }
            if (!history.has("sessions") || !history.get("sessions").isJsonArray()) {
                history.add("sessions", new JsonArray());
                if (!history.has("success")) {
                    history.addProperty("success", true);
                }
                return GsonHolder.GSON.toJson(history);
            }
            return rawJson;
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Normalize sessions JSON failed, fallback to empty sessions: " + e.getMessage());
            return EMPTY_SESSIONS_JSON;
        }
    }

    static String enhanceHistoryWithFavorites(String historyJson, String currentProvider, String favoritesJson) {
        String normalizedJson = normalizeSessionsJson(historyJson);
        try {
            JsonObject history = GsonHolder.GSON.fromJson(normalizedJson, JsonObject.class);
            JsonObject favorites = parseObjectOrEmpty(favoritesJson);

            JsonArray sessions = history.getAsJsonArray("sessions");
            for (int i = 0; i < sessions.size(); i++) {
                JsonElement element = sessions.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject session = element.getAsJsonObject();
                JsonElement sessionIdElement = session.get("sessionId");
                if (sessionIdElement == null || !sessionIdElement.isJsonPrimitive()) {
                    continue;
                }
                String sessionId = sessionIdElement.getAsString();
                session.addProperty("provider", currentProvider);

                if (favorites.has(sessionId) && favorites.get(sessionId).isJsonObject()) {
                    JsonObject favoriteInfo = favorites.getAsJsonObject(sessionId);
                    session.addProperty("isFavorited", true);
                    if (favoriteInfo.has("favoritedAt") && favoriteInfo.get("favoritedAt").isJsonPrimitive()) {
                        session.addProperty("favoritedAt", favoriteInfo.get("favoritedAt").getAsLong());
                    }
                } else {
                    session.addProperty("isFavorited", false);
                }
            }

            history.add("favorites", favorites);
            return GsonHolder.GSON.toJson(history);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Enhance history favorites failed, return normalized data: " + e.getMessage());
            return normalizedJson;
        }
    }

    static String enhanceHistoryWithTitles(String historyJson, String titlesJson) {
        String normalizedJson = normalizeSessionsJson(historyJson);
        try {
            JsonObject history = GsonHolder.GSON.fromJson(normalizedJson, JsonObject.class);
            JsonObject titles = parseObjectOrEmpty(titlesJson);

            JsonArray sessions = history.getAsJsonArray("sessions");
            for (int i = 0; i < sessions.size(); i++) {
                JsonElement element = sessions.get(i);
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject session = element.getAsJsonObject();
                JsonElement sessionIdElement = session.get("sessionId");
                if (sessionIdElement == null || !sessionIdElement.isJsonPrimitive()) {
                    continue;
                }
                String sessionId = sessionIdElement.getAsString();

                if (titles.has(sessionId) && titles.get(sessionId).isJsonObject()) {
                    JsonObject titleInfo = titles.getAsJsonObject(sessionId);
                    if (titleInfo.has("customTitle") && titleInfo.get("customTitle").isJsonPrimitive()) {
                        session.addProperty("title", titleInfo.get("customTitle").getAsString());
                        session.addProperty("hasCustomTitle", true);
                    }
                }
            }

            return GsonHolder.GSON.toJson(history);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Enhance history titles failed, return normalized data: " + e.getMessage());
            return normalizedJson;
        }
    }

    private String loadFavoritesJson() {
        try {
            return nodeJsServiceCaller.callNodeJsFavoritesService("loadFavorites", "");
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Load favorites failed, continue without favorites: " + e.getMessage());
            return "{}";
        }
    }

    private String loadTitlesJson() {
        try {
            return nodeJsServiceCaller.callNodeJsTitlesService("loadTitles");
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Load titles failed, continue without custom titles: " + e.getMessage());
            return "{}";
        }
    }

    private static JsonObject parseObjectOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonObject object = GsonHolder.GSON.fromJson(json, JsonObject.class);
            return object != null ? object : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }
}
