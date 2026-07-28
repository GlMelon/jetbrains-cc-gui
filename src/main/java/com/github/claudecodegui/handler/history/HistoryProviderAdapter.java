package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import java.io.IOException;
import java.util.Set;

/**
 * Adapter for provider-native history storage.
 * <p>
 * Implementations own the storage details for their provider (for example JSONL files or
 * SQLite rows). Callers must use this contract instead of assuming a shared storage format.
 */
interface HistoryProviderAdapter {
    ProviderType provider();

    default Set<HistoryCapability> capabilities() {
        return Set.of();
    }

    default boolean supports(HistoryCapability capability) {
        return capabilities().contains(capability);
    }

    String loadSessionsJson(String projectPath);

    HistoryMessageBatch loadMessages(
            String sessionId,
            String projectPath,
            HistoryMessageReadPolicy policy
    );

    default HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException {
        return HistoryDeleteResult.none();
    }

    default HistoryArchiveResult archiveSession(String sessionId, String projectPath) throws IOException {
        return HistoryArchiveResult.none();
    }

    void clearCache(String projectPath);
}
