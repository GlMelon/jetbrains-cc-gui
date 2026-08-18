package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.NodeService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Independent MCP server query service for Claude provider.
 * Extracts MCP status/tools query using NodeService
 * for Node.js infrastructure.
 */
public class ClaudeMcpService {

    private static final Logger LOG = Logger.getInstance(ClaudeMcpService.class);
    private static final Gson gson = new Gson();

    private final ClaudeMcpQueryService mcpQueryService;

    public ClaudeMcpService() {
        NodeService nodeService = NodeService.getInstance();
        this.mcpQueryService = new ClaudeMcpQueryService(
                LOG,
                gson,
                nodeService.getNodeDetector(),
                nodeService::getSdkTestDir,
                nodeService.getProcessManager(),
                nodeService.getEnvConfigurator(),
                new ClaudeJsonOutputExtractor()
        );
    }

    public CompletableFuture<List<JsonObject>> getMcpServerStatus(String cwd) {
        return mcpQueryService.getMcpServerStatus(cwd);
    }

    public CompletableFuture<JsonObject> getMcpServerTools(String serverId, String cwd) {
        return mcpQueryService.getMcpServerTools(serverId, cwd);
    }
}
