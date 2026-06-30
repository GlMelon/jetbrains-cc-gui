package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

interface HistoryProviderAdapter {
    ProviderType provider();

    String loadSessionsJson(String projectPath);

    List<JsonObject> loadMessages(String sessionId, String projectPath);

    HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException;

    void clearCache(String projectPath);
}
