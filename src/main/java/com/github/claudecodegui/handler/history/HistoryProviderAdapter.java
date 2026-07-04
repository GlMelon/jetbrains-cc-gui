package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

/**
 * Adapter for provider-native history storage.
 * <p>
 * Implementations own the storage details for their provider (for example JSONL files or
 * SQLite rows). Callers must use this contract instead of assuming a shared storage format.
 */
interface HistoryProviderAdapter {
    ProviderType provider();

    String loadSessionsJson(String projectPath);

    List<JsonObject> loadMessages(String sessionId, String projectPath);

    HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException;

    void clearCache(String projectPath);
}
