package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.opencode.OpenCodeHistorySanitizer;
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
        return HistorySessionsJsonEnhancer.normalizeSessionsJson(bridge != null ? bridge.getSessionList(projectPath) : "");
    }

    /**
     * 把 getSessionList 的失败空串归一化为合法空会话 JSON(对称 Codex/Claude reader 始终返回合法 JSON)。
     * 避免 HistoryLoadService.enhanceHistoryWithFavorites → fromJson("")=null → 前端 JSON.parse("")
     * 抛"解析历史数据失败"。纯函数,便于无 HandlerContext(具体类,Platform 依赖)单测。
     */
    static String normalizeSessionsJson(String json) {
        return HistorySessionsJsonEnhancer.normalizeSessionsJson(json);
    }

    @Override
    public List<JsonObject> loadMessages(String sessionId, String projectPath) {
        OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
        if (bridge == null) {
            return List.of();
        }
        List<JsonObject> messages = bridge.getSessionMessages(sessionId, projectPath);
        sanitizeHistoryMessages(messages);
        return messages;
    }

    /**
     * 后处理 OpenCode 历史消息:对 user 消息剥除 IDE 拼接的上下文(## Project Modules、
     * ## Opened Files Context 等),对称 Claude 历史路径调 UserMessageSanitizer。
     * <p>
     * 委托 {@link OpenCodeHistorySanitizer#sanitize}(bridge.getSessionMessages 已在 choke point
     * 清理一遍,此处为历史面板路径的幂等兜底)。保留 package-private 签约供现有单测。
     */
    static void sanitizeHistoryMessages(List<JsonObject> messages) {
        OpenCodeHistorySanitizer.sanitize(messages);
    }

    @Override
    public HistoryDeleteResult deleteSession(String sessionId, String projectPath) {
        OpenCodeSDKBridge bridge = context.getOpenCodeSDKBridge();
        if (bridge == null) {
            return HistoryDeleteResult.none();
        }
        return buildDeleteResult(bridge.deleteSession(sessionId));
    }

    /**
     * 把 ai-bridge archiveSession 返回的受影响行数映射为 HistoryDeleteResult(对称 Codex
     * 把"文件是否删掉"映射为 mainDeleted)。OpenCode 走软删除(归档),无独立 agent 文件,
     * 故 agentFilesDeleted 恒 0。纯函数,便于无 HandlerContext(具体类,Platform 依赖)单测。
     */
    static HistoryDeleteResult buildDeleteResult(int archived) {
        return new HistoryDeleteResult(archived > 0, 0);
    }

    @Override
    public void clearCache(String projectPath) {
        // OpenCode history is read from SQLite without Java-side index cache.
    }
}

