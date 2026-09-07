package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.provider.minimax.MiniMaxHistoryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MiniMax Code(mcode)会话历史适配器:~/.minimax/v2/sessions 下的 snapshot.json 布局。
 * 支持 DELETE(删除会话目录);无 ARCHIVE(mcode 无原生归档面)。
 */
final class MiniMaxHistoryProviderAdapter implements HistoryProviderAdapter {

    private final MiniMaxHistoryReader reader;

    /** 生产装配:默认根(~/.minimax/v2/sessions,MINIMAX_CODE_HOME/MINIMAX_HOME 可覆盖)。 */
    MiniMaxHistoryProviderAdapter() {
        this(new MiniMaxHistoryReader());
    }

    /** 测试注入:显式 reader(自定义 minimaxHome)。 */
    MiniMaxHistoryProviderAdapter(MiniMaxHistoryReader reader) {
        this.reader = reader;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.MINIMAX;
    }

    @Override
    public Set<HistoryCapability> capabilities() {
        return Set.of(HistoryCapability.DELETE);
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        try {
            List<MiniMaxHistoryReader.SessionInfo> sessions = reader.listSessionsForProject(projectPath);
            int totalMessages = sessions.stream().mapToInt(s -> s.messageCount).sum();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("sessions", sessions);
            result.put("total", totalMessages);
            result.put("sessionCount", sessions.size());
            return GsonHolder.GSON.toJson(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Failed to read MiniMax sessions: " + e.getMessage());
            return GsonHolder.GSON.toJson(error);
        }
    }

    @Override
    public HistoryMessageBatch loadMessages(String sessionId, String projectPath, HistoryMessageReadPolicy policy) {
        List<JsonObject> all;
        try {
            all = reader.getSessionMessages(sessionId, projectPath);
        } catch (Exception e) {
            all = List.of();
        }
        BoundedHistoryMessageCollector collector = new BoundedHistoryMessageCollector(policy);
        all.forEach(collector::append);
        return collector.toBatch();
    }

    @Override
    public HistoryDeleteResult deleteSession(String sessionId, String projectPath) {
        boolean deleted;
        try {
            deleted = reader.deleteSession(sessionId, projectPath);
        } catch (Exception e) {
            deleted = false;
        }
        return new HistoryDeleteResult(deleted, deleted ? 1 : 0);
    }

    @Override
    public void clearCache(String projectPath) {
        // minimax 历史为纯文件扫描,无 Java 侧缓存
    }
}
