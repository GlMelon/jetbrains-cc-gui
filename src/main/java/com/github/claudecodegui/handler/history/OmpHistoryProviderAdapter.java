package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.provider.omp.OmpHistoryReader;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * OMP 会话历史适配器:~/.omp/agent/sessions 下的 JSONL(本地落盘,pi fork 布局)。
 * 支持 DELETE(删除会话文件并清理空父目录);无 ARCHIVE。
 * 会话列表 JSON 由 reader 直接产出:{success,sessions[],total,sessionCount,provider}。
 */
final class OmpHistoryProviderAdapter implements HistoryProviderAdapter {

    private final OmpHistoryReader reader;

    OmpHistoryProviderAdapter() {
        this(new OmpHistoryReader());
    }

    OmpHistoryProviderAdapter(OmpHistoryReader reader) {
        this.reader = reader;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OMP;
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
        List<JsonObject> all;
        try {
            all = reader.getSessionMessages(sessionId, projectPath);
        } catch (Exception e) {
            // 读失败降级空批次(与前端「会话可能已被清理」语义一致,不炸历史面板)
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
        // omp 历史为纯文件扫描,无 Java 侧缓存
    }
}
