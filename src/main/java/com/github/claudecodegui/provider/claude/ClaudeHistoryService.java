package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.provider.SessionHistoryLoadResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * Independent history reading service for Claude provider.
 * Extracts history reading using NodeService
 * for Node.js infrastructure.
 */
public class ClaudeHistoryService {

    private static final Logger LOG = Logger.getInstance(ClaudeHistoryService.class);
    private static final Gson gson = new Gson();

    private final ClaudeSessionQueryService sessionQueryService;
    private final ClaudeHistoryPageService historyPageService;

    public ClaudeHistoryService() {
        NodeService nodeService = NodeService.getInstance();
        this.sessionQueryService = new ClaudeSessionQueryService(
                LOG,
                gson,
                nodeService.getNodeDetector(),
                nodeService::getBridgeDir,
                nodeService.getProcessManager(),
                nodeService.getEnvConfigurator(),
                new ClaudeJsonOutputExtractor()
        );
        this.historyPageService = new ClaudeHistoryPageService(sessionQueryService::getSessionMessages);
    }

    /**
     * Get session messages for a given Claude session.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return sessionQueryService.getSessionMessages(sessionId, cwd);
    }

    /**
     * Get the initial page of session history with pagination info.
     *
     * <p>异常刻意不上抛转空:保持 Claude 原有「加载失败经 orchestrator 对前端可见」语义,
     * 由 {@code SessionMessageOrchestrator#loadFromServer} 的 catch 统一处理。
     */
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        return historyPageService.loadInitialPage(sessionId, cwd);
    }

    /**
     * Load an earlier page of session history(经 {@code LoadCodexHistoryPageActionHandler} 路由调用).
     */
    public SessionHistoryLoadResult loadHistoryPage(String sessionId, String cwd, int beforeTurn) {
        return historyPageService.loadEarlierPage(sessionId, cwd, beforeTurn);
    }

    /**
     * Get the latest user message from a session.
     */
    public JsonObject getLatestUserMessage(String sessionId, String cwd) {
        return sessionQueryService.getLatestUserMessage(sessionId, cwd);
    }
}
