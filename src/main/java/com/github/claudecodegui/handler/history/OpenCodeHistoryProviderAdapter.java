package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.provider.opencode.OpenCodeHistorySanitizer;
import com.github.claudecodegui.provider.opencode.OpenCodeHistoryService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

final class OpenCodeHistoryProviderAdapter implements HistoryProviderAdapter {
    private final OpenCodeHistoryService historyService;

    OpenCodeHistoryProviderAdapter() {
        this.historyService = new OpenCodeHistoryService();
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OPENCODE;
    }

    @Override
    public Set<HistoryCapability> capabilities() {
        return Set.of(HistoryCapability.ARCHIVE);
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        return HistorySessionsJsonEnhancer.normalizeSessionsJson(historyService.getSessionList(projectPath));
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
    public HistoryMessageBatch loadMessages(
            String sessionId,
            String projectPath,
            HistoryMessageReadPolicy policy
    ) {
        OpenCodeHistoryService.SessionHistoryQueryResult result = historyService.getSessionMessages(
                sessionId,
                projectPath,
                policy.maxMessageCount(),
                policy.maxUtf8Bytes()
        );
        List<JsonObject> messages = result.messages();
        sanitizeHistoryMessages(messages);
        return new HistoryMessageBatch(messages, result.totalMessageCount());
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
    public HistoryArchiveResult archiveSession(String sessionId, String projectPath) {
        return buildArchiveResult(historyService.archiveSession(sessionId));
    }

    static HistoryArchiveResult buildArchiveResult(int archived) {
        return new HistoryArchiveResult(archived > 0);
    }

    @Override
    public void clearCache(String projectPath) {
        // OpenCode history is read from SQLite without Java-side index cache.
    }
}