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
        OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
        return normalizeSessionsJson(bridge != null ? bridge.getSessionList(projectPath) : "");
    }

    /**
     * 把 getSessionList 的失败空串归一化为合法空会话 JSON(对称 Codex/Claude reader 始终返回合法 JSON)。
     * 避免 HistoryLoadService.enhanceHistoryWithFavorites → fromJson("")=null → 前端 JSON.parse("")
     * 抛"解析历史数据失败"。纯函数,便于无 HandlerContext(具体类,Platform 依赖)单测。
     */
    static String normalizeSessionsJson(String json) {
        return (json != null && !json.isBlank()) ? json : "{\"success\":true,\"sessions\":[]}";
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
