package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.provider.dsh.DshHistoryReader;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * DSH 会话历史适配器:经 channel-manager dsh 命令读 host 侧历史(listSessions/loadSession)。
 * DELETE 语义为 host 归档(archiveSession,非物理删除);无独立 ARCHIVE 面。
 * 会话列表 JSON 由 reader 经 bridge 产出:{success,sessions[],sessionCount,provider,total}。
 */
final class DshHistoryProviderAdapter implements HistoryProviderAdapter {

    private final DshHistoryReader reader;

    DshHistoryProviderAdapter() {
        this(new DshHistoryReader());
    }

    DshHistoryProviderAdapter(DshHistoryReader reader) {
        this.reader = reader;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.DSH;
    }

    @Override
    public Set<HistoryCapability> capabilities() {
        return Set.of(HistoryCapability.DELETE);
    }

    @Override
    public String loadSessionsJson(String projectPath) {
        return reader.getSessionsForProjectAsJson(projectPath);
    }

    @Override
    public HistoryMessageBatch loadMessages(String sessionId, String projectPath, HistoryMessageReadPolicy policy) {
        List<JsonObject> all = reader.getSessionMessages(sessionId, projectPath);
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
        // dsh 历史由 host 持有,Java 侧无缓存
    }
}
