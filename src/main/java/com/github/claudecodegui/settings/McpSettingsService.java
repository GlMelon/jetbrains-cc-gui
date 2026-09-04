package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP 配置领域 Service。
 *
 * <p>持有 {@link McpServerManager} 并通过 {@link ConfigStore} 提供全局 SSOT(config.json
 * mcpServers)读写;Claude 原生 MCP 配置仍由 {@link ClaudeSettingsManager} 负责,
 * codex / opencode 原生写穿经注入的 manager 完成。Facade 只保留兼容调用面。
 */
public final class McpSettingsService {
    private final McpServerManager mcpServerManager;

    public McpSettingsService(
            ConfigStore configStore,
            Gson gson,
            ClaudeSettingsManager claudeSettingsManager,
            CodexMcpServerManager codexMcpServerManager,
            OpenCodeSettingsManager openCodeSettingsManager) {
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
                claudeSettingsManager,
                codexMcpServerManager,
                openCodeSettingsManager
        );
    }

    public List<JsonObject> getMcpServers() throws IOException {
        return mcpServerManager.getMcpServers();
    }

    public List<JsonObject> getMcpServersWithProjectPath(String projectPath) throws IOException {
        return mcpServerManager.getMcpServersWithProjectPath(projectPath);
    }

    /**
     * 读取 claude 原生 MCP 配置(~/.claude.json 直读,含项目级合并),供一次性迁移与
     * MCP Gateway collector 使用;不走全局 SSOT。
     */
    public List<JsonObject> readClaudeNativeMcpServers(String projectPath) {
        return mcpServerManager.readClaudeNativeMcpServers(projectPath);
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
