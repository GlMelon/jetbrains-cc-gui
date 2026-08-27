package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.provider.grok.GrokHistoryReader;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grok 会话历史适配器:~/.grok/sessions 下的 chat_history.jsonl。
 * 支持 DELETE(删除会话目录);无 ARCHIVE(grok 无原生归档面)。
 * 会话列表 JSON 与 Codex 同构:{success,sessions[],total,sessionCount}。
 */
final class GrokHistoryProviderAdapter implements HistoryProviderAdapter {

    private final GrokHistoryReader reader;

    /** 生产装配:默认根(~/.grok/sessions)。 */
    GrokHistoryProviderAdapter() {
        this(new GrokHistoryReader());
    }

    /** 测试注入:显式会话根目录。 */
    GrokHistoryProviderAdapter(GrokHistoryReader reader) {
        this.reader = reader;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.GROK;
    }

    @Override
    public Set<HistoryCapability> capabilities() {
        return Set.of(HistoryCapability.DELETE);
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        List<GrokHistoryReader.GrokSessionInfo> sessions = reader.listSessions(projectPath);
        int totalMessages = sessions.stream().mapToInt(s -> s.messageCount).sum();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("sessions", sessions);
        result.put("total", totalMessages);
        result.put("sessionCount", sessions.size());
        return GsonHolder.GSON.toJson(result);
    }

    @Override
    public HistoryMessageBatch loadMessages(String sessionId, String projectPath, HistoryMessageReadPolicy policy) {
        Path dir = reader.findSessionDir(sessionId, projectPath);
        List<JsonObject> all = dir == null ? List.of() : reader.loadMessages(dir);
        BoundedHistoryMessageCollector collector = new BoundedHistoryMessageCollector(policy);
        all.forEach(collector::append);
        return collector.toBatch();
    }

    @Override
    public HistoryDeleteResult deleteSession(String sessionId, String projectPath) {
        boolean deleted = reader.deleteSession(sessionId, projectPath);
        return new HistoryDeleteResult(deleted, deleted ? 1 : 0);
    }

    @Override
    public void clearCache(String projectPath) {
        // grok 历史为纯文件扫描,无 Java 侧缓存
    }
}
