package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP 配置领域 Service。
 *
 * <p>持有 {@link McpServerManager} 并通过 {@link ConfigStore} 提供 config.json fallback；
 * Claude 原生 MCP 配置仍由 {@link ClaudeSettingsManager} 负责。Facade 只保留兼容调用面。
 */
public final class McpSettingsService {
    private final McpServerManager mcpServerManager;

    public McpSettingsService(
            ConfigStore configStore,
            Gson gson,
            ClaudeSettingsManager claudeSettingsManager) {
        this.mcpServerManager = new McpServerManager(
                gson,
                (ignored) -> {
                    try {
                        return configStore.read();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        configStore.write(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                claudeSettingsManager
        );
    }

    public List<JsonObject> getMcpServers() throws IOException {
        return mcpServerManager.getMcpServers();
    }

    public List<JsonObject> getMcpServersWithProjectPath(String projectPath) throws IOException {
        return mcpServerManager.getMcpServersWithProjectPath(projectPath);
    }

    public void upsertMcpServer(JsonObject server) throws IOException {
        mcpServerManager.upsertMcpServer(server);
    }

    public void upsertMcpServer(JsonObject server, String projectPath) throws IOException {
        mcpServerManager.upsertMcpServer(server, projectPath);
    }

    public boolean deleteMcpServer(String serverId) throws IOException {
        return mcpServerManager.deleteMcpServer(serverId);
    }

    public Map<String, Object> validateMcpServer(JsonObject server) {
        return mcpServerManager.validateMcpServer(server);
    }
}
