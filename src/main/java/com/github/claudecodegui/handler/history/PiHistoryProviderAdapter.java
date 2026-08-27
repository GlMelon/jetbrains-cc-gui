package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.provider.pi.PiHistoryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pi CLI 会话历史适配器:~/.pi/agent/sessions 下的 JSONL(v3 公开规范)。
 * 支持 DELETE(官方语义即删文件);无 ARCHIVE。
 */
final class PiHistoryProviderAdapter implements HistoryProviderAdapter {

    private final PiHistoryReader reader;

    /** 生产装配:默认根(~/.pi/agent/sessions)。 */
    PiHistoryProviderAdapter() {
        this(new PiHistoryReader());
    }

    /** 测试注入:显式 ~/.pi/agent 基目录。 */
    PiHistoryProviderAdapter(PiHistoryReader reader) {
        this.reader = reader;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.PI;
    }

    @Override
    public Set<HistoryCapability> capabilities() {
        return Set.of(HistoryCapability.DELETE);
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        List<PiHistoryReader.PiSessionInfo> sessions = reader.listSessions(projectPath);
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
        Path file = reader.findSessionFile(sessionId, projectPath);
        List<JsonObject> all = reader.loadMessages(file);
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
        // pi 历史为纯文件扫描,无 Java 侧缓存
    }
}
