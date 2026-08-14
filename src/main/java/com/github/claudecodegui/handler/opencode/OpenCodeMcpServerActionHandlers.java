package com.github.claudecodegui.handler.opencode;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.OpenCodeConfigReader;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.OpenCodeSettingsManager;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OpenCode MCP server action handlers 容器（只读）。
 *
 * <p>与 Claude/Codex 的差异：OpenCode 的 MCP server 在插件 channel 层无 getMcpServerStatus/
 * 列工具命令（{@code opencode-channel.js} 显式返回 {@code opencode_mcp_passthrough}）。
 * 但插件 MCP Gateway 经 {@code collectOpenCode} 已对 OpenCode server 真实建连，其 {@code /status}
 * 端点按 {@code sourceProvider:serverId} 聚合了实时健康。故：
 * <ul>
 *   <li>server 列表：{@link OpenCodeConfigReader#readMcpServers()} 读 {@code ~/.config/opencode/opencode.json}
 *       的 {@code mcp} 字段（global 层），适配成前端 {@code McpServer} 嵌套形状。</li>
 *   <li>连接状态：{@link McpGatewayService#statusJson()} 拿聚合状态，过滤 {@code sourceProvider=="opencode"}
 *       并把 gateway state 词表（READY/DEGRADED/STARTING/BACKOFF/STOPPED）映射到前端词表
 *       （connected/pending/failed/disabled）。这是比 Claude/Codex 的 channel spawn 更优的数据源。</li>
 * </ul>
 * <p>增删改/toggle（2026-08-14 起）：经 {@link OpenCodeSettingsManager} 外科手术式读写 opencode.json
 * 的 {@code mcp} 段（global 层，保留 provider 等其他段；写前过 {@code McpCommandRiskEvaluator} SEC-01
 * 闸门），写后刷新 MCP Gateway——对称 Claude/Codex 的 manager 落盘模式（不走 {@code opencode mcp}
 * CLI，其无 remove 子命令）。工具列表仍不可达——OpenCode 无列工具 API，工具配置后自动暴露给 LLM。
 */
public class OpenCodeMcpServerActionHandlers {

    private static final Logger LOG = Logger.getInstance(OpenCodeMcpServerActionHandlers.class);

    private final HandlerContext context;
    private final OpenCodeSettingsManager settingsManager;

    public OpenCodeMcpServerActionHandlers(HandlerContext context) {
        this.context = context;
        this.settingsManager = new OpenCodeSettingsManager(GsonHolder.GSON);
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handleGetMcpServers() {
        try {
            List<JsonObject> raw = OpenCodeConfigReader.readMcpServers();
            List<JsonObject> adapted = new ArrayList<>();
            for (JsonObject info : raw) {
                adapted.add(adaptToServerShape(info));
            }
            String json = GsonHolder.GSON.toJson(adapted);
            LOG.info("[OpenCodeMcpServerActionHandlers] Loaded " + adapted.size() + " OpenCode MCP servers");

            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.OPENCODE_MCP_SERVER_LIST.value(), context.escapeJs(json))
            );
        } catch (Exception e) {
            LOG.error("[OpenCodeMcpServerActionHandlers] Failed to get OpenCode MCP servers: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.OPENCODE_MCP_SERVER_LIST.value(), context.escapeJs("[]"))
            );
        }
    }

    void handleGetMcpServerStatus() {
        CompletableFuture.runAsync(() -> {
            try {
                Project project = context.getProject();
                List<JsonObject> result = project == null
                    ? new ArrayList<>()
                    : extractOpenCodeStatus(McpGatewayService.getInstance(project).statusJson());
                Gson gson = GsonHolder.GSON;
                String json = gson.toJson(result);

                ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.OPENCODE_MCP_SERVER_STATUS.value(), context.escapeJs(json))
                );
            } catch (Exception e) {
                LOG.warn("[OpenCodeMcpServerActionHandlers] Failed to get OpenCode MCP server status: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.OPENCODE_MCP_SERVER_STATUS.value(), context.escapeJs("[]"))
                );
            }
        });
    }

    // --- 增删改/toggle(对称 CodexMcpServerActionHandlers,落盘在 OpenCodeSettingsManager) ---

    void handleAddMcpServer(String content) {
        try {
            JsonObject server = GsonHolder.GSON.fromJson(content, JsonObject.class);
            settingsManager.upsertMcpServer(server);
            refreshGateway();
            LOG.info("[OpenCodeMcpServerActionHandlers] Added OpenCode MCP server: " + serverIdOf(server));
            CompletableFuture.runAsync(this::handleGetMcpServers);
        } catch (Exception e) {
            LOG.error("[OpenCodeMcpServerActionHandlers] Failed to add OpenCode MCP server: " + e.getMessage(), e);
            dispatchError("Failed to add OpenCode MCP server: " + e.getMessage());
        }
    }

    void handleUpdateMcpServer(String content) {
        try {
            JsonObject server = GsonHolder.GSON.fromJson(content, JsonObject.class);
            settingsManager.upsertMcpServer(server);
            refreshGateway();
            LOG.info("[OpenCodeMcpServerActionHandlers] Updated OpenCode MCP server: " + serverIdOf(server));
            CompletableFuture.runAsync(this::handleGetMcpServers);
        } catch (Exception e) {
            LOG.error("[OpenCodeMcpServerActionHandlers] Failed to update OpenCode MCP server: " + e.getMessage(), e);
            dispatchError("Failed to update OpenCode MCP server: " + e.getMessage());
        }
    }

    void handleDeleteMcpServer(String content) {
        try {
            JsonObject json = GsonHolder.GSON.fromJson(content, JsonObject.class);
            String serverId = json.has("id") ? json.get("id").getAsString() : "";

            boolean success = settingsManager.deleteMcpServer(serverId);
            if (success) {
                refreshGateway();
                LOG.info("[OpenCodeMcpServerActionHandlers] Deleted OpenCode MCP server: " + serverId);
                CompletableFuture.runAsync(this::handleGetMcpServers);
            } else {
                LOG.warn("[OpenCodeMcpServerActionHandlers] OpenCode MCP server not found: " + serverId);
                dispatchError("OpenCode MCP server not found: " + serverId);
            }
        } catch (Exception e) {
            LOG.error("[OpenCodeMcpServerActionHandlers] Failed to delete OpenCode MCP server: " + e.getMessage(), e);
            dispatchError("Failed to delete OpenCode MCP server: " + e.getMessage());
        }
    }

    void handleToggleMcpServer(String content) {
        try {
            JsonObject server = GsonHolder.GSON.fromJson(content, JsonObject.class);
            settingsManager.upsertMcpServer(server);
            refreshGateway();
            LOG.info("[OpenCodeMcpServerActionHandlers] Toggled OpenCode MCP server: " + serverIdOf(server)
                    + " (enabled: " + (!server.has("enabled") || server.get("enabled").getAsBoolean()) + ")");
            CompletableFuture.runAsync(this::handleGetMcpServers);
        } catch (Exception e) {
            LOG.error("[OpenCodeMcpServerActionHandlers] Failed to toggle OpenCode MCP server: " + e.getMessage(), e);
            dispatchError("Failed to toggle OpenCode MCP server: " + e.getMessage());
        }
    }

    private static String serverIdOf(JsonObject server) {
        return server != null && server.has("id") ? server.get("id").getAsString() : CommonConstants.UNKNOWN;
    }

    /** 刷新 MCP Gateway 配置(增删改/toggle 后同步 snapshot,对称 Codex 的 refreshGateway)。 */
    private void refreshGateway() {
        try {
            if (context.getProject() != null) {
                McpGatewayService.getInstance(context.getProject()).refreshConfig(context.getProject().getBasePath());
            }
        } catch (Exception e) {
            LOG.warn("[OpenCodeMcpServerActionHandlers] Failed to refresh MCP Gateway: " + e.getMessage());
        }
    }

    private void dispatchError(String message) {
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), context.escapeJs(message))
        );
    }

    // --- Private helpers ---

    /**
     * 把 {@link OpenCodeConfigReader#readMcpServers()} 返回的扁平 OpenCode server 配置
     * （{@code {id,type,enabled,command?[数组],url?,environment?}}）适配成前端 {@code McpServer}
     * 嵌套形状（{@code {id,name,enabled,server:{type,command,args?,url?,env?}}}）。
     *
     * <p>OpenCode 的 {@code command} 是数组（{@code ["npx","-y","pkg"]}，command+args 合一），
     * 前端 {@code McpServerSpec} 是 {@code command:string + args[]}，故拆分 command[0]→command、
     * command[1:]→args，使 {@code ServerCard} 的 {@code [command,...args].join(' ')} 正确还原。
     */
    private static JsonObject adaptToServerShape(JsonObject info) {
        JsonObject out = new JsonObject();
        String id = info.has("id") ? info.get("id").getAsString() : "";
        out.addProperty("id", id);
        out.addProperty("name", id);
        out.addProperty("enabled", !info.has("enabled") || info.get("enabled").getAsBoolean());

        JsonObject server = new JsonObject();
        String type = info.has("type") ? info.get("type").getAsString() : "local";
        server.addProperty("type", "remote".equals(type) ? "sse" : "stdio");

        if (info.has("command") && info.get("command").isJsonArray()) {
            JsonArray cmdArr = info.getAsJsonArray("command");
            if (cmdArr.size() > 0 && cmdArr.get(0).isJsonPrimitive()) {
                server.addProperty("command", cmdArr.get(0).getAsString());
            }
            if (cmdArr.size() > 1) {
                JsonArray args = new JsonArray();
                for (int i = 1; i < cmdArr.size(); i++) {
                    if (cmdArr.get(i).isJsonPrimitive()) {
                        args.add(cmdArr.get(i).getAsString());
                    }
                }
                server.add("args", args);
            }
        }
        if (info.has("url") && info.get("url").isJsonPrimitive()) {
            server.addProperty("url", info.get("url").getAsString());
        }
        if (info.has("environment") && info.get("environment").isJsonObject()) {
            server.add("env", info.getAsJsonObject("environment"));
        }
        out.add("server", server);
        return out;
    }

    /**
     * 从 MCP Gateway 聚合状态 JSON 中提取 OpenCode server 的连接状态。
     *
     * <p>gateway {@code /status} 输出 {@code {revision,uptimeMs,servers:[HealthEntry...]}}，
     * 每个 HealthEntry = {@code {serverId,sourceProvider,state,lastError,...}}。过滤
     * {@code sourceProvider=="opencode"}，把 gateway state 映射到前端 {@code McpServerStatusInfo.status} 词表。
     */
    private static List<JsonObject> extractOpenCodeStatus(String statusJson) {
        List<JsonObject> result = new ArrayList<>();
        if (statusJson == null || statusJson.isBlank() || "{}".equals(statusJson.trim())) {
            return result;
        }
        try {
            JsonObject root = JsonParser.parseString(statusJson).getAsJsonObject();
            if (!root.has("servers") || !root.get("servers").isJsonArray()) {
                return result;
            }
            for (JsonElement el : root.getAsJsonArray("servers")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject s = el.getAsJsonObject();
                String src = s.has("sourceProvider") ? s.get("sourceProvider").getAsString() : "";
                if (!CommonConstants.PROVIDER_OPENCODE.equals(src)) {
                    continue;
                }
                String serverId = s.has("serverId") ? s.get("serverId").getAsString() : "";
                String state = s.has("state") ? s.get("state").getAsString() : "";
                String mapped = mapGatewayState(state);

                JsonObject info = new JsonObject();
                info.addProperty("name", serverId);
                info.addProperty("status", mapped);
                if ("failed".equals(mapped)
                        && s.has("lastError") && s.get("lastError").isJsonPrimitive()) {
                    info.addProperty("error", s.get("lastError").getAsString());
                }
                result.add(info);
            }
        } catch (Exception e) {
            LOG.warn("[OpenCodeMcpServerActionHandlers] Failed to parse gateway status: " + e.getMessage());
        }
        return result;
    }

    /**
     * gateway state 词表（server-supervisor.js：READY/DEGRADED/STARTING/BACKOFF/STOPPED）
     * → 前端 {@code McpServerStatusInfo.status} 词表（connected/failed/pending/disabled）。
     */
    private static String mapGatewayState(String state) {
        if (state == null) {
            return "pending";
        }
        switch (state) {
            case "READY":
            case "DEGRADED":
                return "connected";
            case "BACKOFF":
                return "failed";
            case "STOPPED":
                return "disabled";
            case "STARTING":
            default:
                return "pending";
        }
    }
}
