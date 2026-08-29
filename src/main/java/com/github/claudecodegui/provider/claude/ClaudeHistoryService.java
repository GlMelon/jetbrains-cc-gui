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

    // ⚠️ 构造期不可解析 NodeService(内部 new EnvironmentConfigurator 触碰 IntelliJ 平台
    // Application 单例):本服务在 ClaudeSession 构造链上,纯 JUnit 装配环境会 NPE。
    // 惰性到首次读历史时初始化(double-checked locking,字段为 immutable 视图)。
    private volatile ClaudeSessionQueryService sessionQueryService;
    private volatile ClaudeHistoryPageService historyPageService;

    public ClaudeHistoryService() {
    }

    private ClaudeSessionQueryService sessionQueryService() {
        ClaudeSessionQueryService local = sessionQueryService;
        if (local == null) {
            synchronized (this) {
                local = sessionQueryService;
                if (local == null) {
                    NodeService nodeService = NodeService.getInstance();
                    local = new ClaudeSessionQueryService(
                            LOG,
                            gson,
                            nodeService.getNodeDetector(),
                            nodeService::getBridgeDir,
                            nodeService.getProcessManager(),
                            nodeService.getEnvConfigurator(),
                            new ClaudeJsonOutputExtractor()
                    );
                    sessionQueryService = local;
                    historyPageService = new ClaudeHistoryPageService(local::getSessionMessages);
                }
            }
        }
        return local;
    }

    private ClaudeHistoryPageService historyPageService() {
        sessionQueryService();  // 确保成对初始化
        return historyPageService;
    }

    /**
     * Get session messages for a given Claude session.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return sessionQueryService().getSessionMessages(sessionId, cwd);
    }

    /**
     * Get the initial page of session history with pagination info.
     *
     * <p>异常刻意不上抛转空:保持 Claude 原有「加载失败经 orchestrator 对前端可见」语义,
     * 由 {@code SessionMessageOrchestrator#loadFromServer} 的 catch 统一处理。
     */
    public SessionHistoryLoadResult getInitialSessionHistory(String sessionId, String cwd) {
        return historyPageService().loadInitialPage(sessionId, cwd);
    }

    /**
     * Load an earlier page of session history(经 {@code LoadCodexHistoryPageActionHandler} 路由调用).
     */
    public SessionHistoryLoadResult loadHistoryPage(String sessionId, String cwd, int beforeTurn) {
        return historyPageService().loadEarlierPage(sessionId, cwd, beforeTurn);
    }

    /**
     * Get the latest user message from a session.
     */
    public JsonObject getLatestUserMessage(String sessionId, String cwd) {
        return sessionQueryService().getLatestUserMessage(sessionId, cwd);
    }
}
