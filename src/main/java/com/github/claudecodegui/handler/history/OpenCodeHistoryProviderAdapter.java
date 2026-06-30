package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.util.List;

final class OpenCodeHistoryProviderAdapter implements HistoryProviderAdapter {
    private final HandlerContext context;

    OpenCodeHistoryProviderAdapter(HandlerContext context) {
        this.context = context;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OPENCODE;
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        return "{\"success\":true,\"sessions\":[]}";
    }

    @Override
    public List<JsonObject> loadMessages(String sessionId, String projectPath) {
        OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
        return bridge != null ? bridge.getSessionMessages(sessionId, projectPath) : List.of();
    }

    @Override
    public HistoryDeleteResult deleteSession(String sessionId, String projectPath) {
        return HistoryDeleteResult.none();
    }

    @Override
    public void clearCache(String projectPath) {
        // OpenCode history is read from SQLite without Java-side index cache.
    }
}
