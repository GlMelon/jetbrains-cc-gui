package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Independent history reading service for Codex provider.
 * Extracts history reading (including pagination) into an independent service.
 * Codex history is read from the local filesystem (~/.codex/sessions/),
 * so this service does not depend on Node.js infrastructure.
 */
public class CodexHistoryService {

    private final CodexHistoryPageService historyPageService;

    public CodexHistoryService() {
        this(new CodexHistoryReader());
    }

    public CodexHistoryService(CodexHistoryReader historyReader) {
        this.historyPageService = new CodexHistoryPageService(new CodexHistoryPageReader(historyReader));
    }

    /**
     * Get persisted Codex session history messages.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return getInitialSessionHistory(sessionId, cwd).messages();
    }

    /**
     * Get the initial page of session history with pagination info.
     */
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        try {
            CodexHistoryPageResult result = historyPageService.loadInitialPage(sessionId);
            return new SessionHistoryLoadResult(result.messages(), result.pageInfo());
        } catch (Exception e) {
            return SessionHistoryLoadResult.fromMessages(List.of());
        }
    }

    /**
     * Load an earlier page of session history.
     */
    public CodexHistoryPageResult loadHistoryPage(String sessionId, int beforeTurn) {
        return historyPageService.loadEarlierPage(sessionId, beforeTurn);
    }
}