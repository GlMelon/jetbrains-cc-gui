package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.OpenCodeConfigReader;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 原生配置写穿协调器。
 *
 * <p>全局 SSOT({@code ~/.codemoss/config.json} 的 {@code mcpServers} 数组)的增删由
 * {@link McpServerManager} 落盘后,经本类同步到 codex({@code ~/.codex/config.toml})与
 * opencode({@code ~/.config/opencode/opencode.json})原生配置文件。claude 原生写仍由
 * {@link McpServerManager} 自持(含 projectPath 项目级合并与 syncMcpToClaudeSettings)。
 *
 * <p>全部 best-effort:单 provider 失败仅 LOG.warn,绝不中断主流程(全局 SSOT 已落盘即视为成功)。
 */
public class McpNativeConfigWriteThrough {
    private static final Logger LOG = Logger.getInstance(McpNativeConfigWriteThrough.class);

    private final CodexMcpServerManager codexMcpServerManager;
    private final OpenCodeSettingsManager openCodeSettingsManager;

    public McpNativeConfigWriteThrough(CodexMcpServerManager codexMcpServerManager,
                                       OpenCodeSettingsManager openCodeSettingsManager) {
        this.codexMcpServerManager = codexMcpServerManager;
        this.openCodeSettingsManager = openCodeSettingsManager;
    }

    /**
     * 写穿 upsert:codex 与 opencode 各自尝试(两家各自重过 SEC-01 闸门),失败仅 LOG.warn。
     */
    public void upsert(JsonObject server) {
        String serverId = server != null && server.has("id") ? server.get("id").getAsString() : "(unknown)";
        try {
            codexMcpServerManager.upsertMcpServer(server);
        } catch (Exception e) {
            LOG.warn("[McpWriteThrough] Codex native upsert failed for " + serverId + ": " + e.getMessage());
        }
        try {
            openCodeSettingsManager.upsertMcpServer(server);
        } catch (Exception e) {
            LOG.warn("[McpWriteThrough] OpenCode native upsert failed for " + serverId + ": " + e.getMessage());
        }
    }

    /**
     * 写穿 delete:codex 与 opencode 各自尝试,失败仅 LOG.warn。
     *
     * @return 任一原生存储确实删除了条目则 true
     */
    public boolean delete(String serverId) {
        boolean removed = false;
        try {
            removed |= codexMcpServerManager.deleteMcpServer(serverId);
        } catch (Exception e) {
            LOG.warn("[McpWriteThrough] Codex native delete failed for " + serverId + ": " + e.getMessage());
        }
        try {
            removed |= openCodeSettingsManager.deleteMcpServer(serverId);
        } catch (Exception e) {
            LOG.warn("[McpWriteThrough] OpenCode native delete failed for " + serverId + ": " + e.getMessage());
        }
        return removed;
    }

    /**
     * 读取 codex 原生 MCP servers(前端嵌套形状,含读时合成的 {@code apps} 字段),
     * 供一次性迁移导入全局 SSOT;读取失败返回空表。
     */
    public List<JsonObject> readCodex() {
        try {
            return codexMcpServerManager.getMcpServers();
        } catch (Exception e) {
            LOG.warn("[McpWriteThrough] Failed to read Codex native MCP servers: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 读取 opencode 原生 MCP servers(经 {@link OpenCodeSettingsManager#adaptMcpToFrontendShape}
     * 转前端嵌套形状),供一次性迁移导入全局 SSOT;读取失败返回空表。
     */
    public List<JsonObject> readOpenCode() {
        try {
            List<JsonObject> raw = OpenCodeConfigReader.readMcpServers();
            List<JsonObject> adapted = new ArrayList<>(raw.size());
            for (JsonObject info : raw) {
                adapted.add(OpenCodeSettingsManager.adaptMcpToFrontendShape(info));
            }
            return adapted;
        } catch (Exception e) {
            LOG.warn("[McpWriteThrough] Failed to read OpenCode native MCP servers: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}
