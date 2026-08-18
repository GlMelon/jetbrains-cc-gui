package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.NodeService;
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

    public ClaudeHistoryService() {
        NodeService nodeService = NodeService.getInstance();
        this.sessionQueryService = new ClaudeSessionQueryService(
                LOG,
                gson,
                nodeService.getNodeDetector(),
                nodeService::getSdkTestDir,
                nodeService.getProcessManager(),
                nodeService.getEnvConfigurator(),
                new ClaudeJsonOutputExtractor()
        );
    }

    /**
     * Get session messages for a given Claude session.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return sessionQueryService.getSessionMessages(sessionId, cwd);
    }

    /**
     * Get the latest user message from a session.
     */
    public JsonObject getLatestUserMessage(String sessionId, String cwd) {
        return sessionQueryService.getLatestUserMessage(sessionId, cwd);
    }
}
