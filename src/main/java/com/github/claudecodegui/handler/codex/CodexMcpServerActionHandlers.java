package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.mcp.McpServerToolsRequest;
import com.github.claudecodegui.handler.mcp.McpServerToolsResponse;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.codex.CodexMcpService;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.CodexMcpServerManager;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Codex MCP server action handlers container.
 * Holds shared logic for Codex MCP server management operations.
 */
public class CodexMcpServerActionHandlers {

    private static final Logger LOG = Logger.getInstance(CodexMcpServerActionHandlers.class);

    private final HandlerContext context;
    private final CodexMcpServerManager codexMcpServerManager;
    private final CodexMcpService codexMcpService;

    public CodexMcpServerActionHandlers(HandlerContext context, CodexMcpServerManager codexMcpServerManager) {
        this.context = context;
        this.codexMcpServerManager = codexMcpServerManager;
        this.codexMcpService = new CodexMcpService();
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handleGetMcpServers() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!isCodexLocalConfigAuthorized()) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_LIST.value(), "[]");
                    });
                    return;
                }

                List<JsonObject> servers = codexMcpServerManager.getMcpServers();
                Gson gson = GsonHolder.GSON;
                String serversJson = gson.toJson(servers);

                LOG.info("[CodexMcpServerActionHandlers] Loaded " + servers.size() + " Codex MCP servers");

                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_LIST.value(), serversJson);
                });
            } catch (Exception e) {
                LOG.error("[CodexMcpServerActionHandlers] Failed to get Codex MCP servers: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_LIST.value(), "[]");
                });
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[CodexMcpServerActionHandlers] Unexpected error in handleGetMcpServers: " + ex.getMessage(), ex);
            return null;
        });
    }

    void handleGetMcpServerStatus() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!isCodexLocalConfigAuthorized()) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_STATUS.value(), "[]");
                    });
                    return;
                }

                List<JsonObject> statusList = codexMcpServerManager.getMcpServerStatus();
                Gson gson = GsonHolder.GSON;
                String statusJson = gson.toJson(statusList);

                LOG.info("[CodexMcpServerActionHandlers] Got status for " + statusList.size() + " Codex MCP servers");
                for (JsonObject status : statusList) {
                    if (status.has("name")) {
                        String serverName = status.get("name").getAsString();
                        String serverStatus = status.has("status") ? status.get("status").getAsString() : CommonConstants.UNKNOWN;
                        LOG.info("[CodexMcpServerActionHandlers] Server: " + serverName + ", Status: " + serverStatus);
                    }
                }

                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_STATUS.value(), statusJson);
                });
            } catch (Exception e) {
                LOG.error("[CodexMcpServerActionHandlers] Failed to get Codex MCP server status: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_STATUS.value(), "[]");
                });
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[CodexMcpServerActionHandlers] Unexpected error in handleGetMcpServerStatus: " + ex.getMessage(), ex);
            return null;
        });
    }

    void handleGetMcpServerTools(McpServerToolsRequest request) {
        Gson gson = GsonHolder.GSON;
        try {
            if (request == null || !request.isValid()) {
                sendToolsError(request, "Missing required requestId or serverId", gson);
                return;
            }
            if (!isCodexLocalConfigAuthorized()) {
                sendToolsError(request, ClaudeCodeGuiBundle.message("error.codexLocalAccessNotAuthorized"), gson);
                return;
            }

            JsonObject targetServer = null;
            List<JsonObject> servers = codexMcpServerManager.getMcpServers();
            for (JsonObject server : servers) {
                if (server.has("id") && request.serverId().equals(server.get("id").getAsString())) {
                    targetServer = server;
                    break;
                }
            }

            if (targetServer == null || !targetServer.has("server") || !targetServer.get("server").isJsonObject()) {
                sendToolsError(request, "Server not found or invalid config: " + request.serverId(), gson);
                return;
            }

            JsonObject serverConfig = targetServer.getAsJsonObject("server");
            // Inject cwd from session or project if not already set (mirrors upstream prepareServerConfig)
            String sessionCwd = context.getSession() != null ? context.getSession().getCwd() : null;
            String projectBasePath = context.getProject() != null ? context.getProject().getBasePath() : null;
            serverConfig = prepareServerConfig(serverConfig, sessionCwd, projectBasePath);
            LOG.info("[CodexMcpServerActionHandlers] Getting tools for Codex MCP server: " + request.serverId());

            codexMcpService.getMcpServerTools(request.serverId(), serverConfig)
                .thenAccept(result -> {
                    sendToolsResponse(McpServerToolsResponse.fromBridge(request, result), gson);
                })
                .exceptionally(e -> {
                    LOG.error("[CodexMcpServerActionHandlers] Failed to get MCP server tools: " + e.getMessage(), e);
                    sendToolsError(request, e.getMessage(), gson);
                    return null;
                });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to get MCP server tools: " + e.getMessage(), e);
            sendToolsError(request, e.getMessage(), gson);
        }
    }

    void handleAddMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            codexMcpServerManager.upsertMcpServer(server);
            refreshGateway();

            String serverId = server.has("id") ? server.get("id").getAsString() : CommonConstants.UNKNOWN;
            LOG.info("[CodexMcpServerActionHandlers] Added Codex MCP server: " + serverId);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_ADDED.value(), content);
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to add Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = "Failed to add Codex MCP server: " + e.getMessage();
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleUpdateMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            codexMcpServerManager.upsertMcpServer(server);
            refreshGateway();

            String serverId = server.has("id") ? server.get("id").getAsString() : CommonConstants.UNKNOWN;
            LOG.info("[CodexMcpServerActionHandlers] Updated Codex MCP server: " + serverId);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_UPDATED.value(), content);
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to update Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = "Failed to update Codex MCP server: " + e.getMessage();
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleDeleteMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String serverId = json.get("id").getAsString();

            boolean success = codexMcpServerManager.deleteMcpServer(serverId);

            if (success) {
                refreshGateway();
                LOG.info("[CodexMcpServerActionHandlers] Deleted Codex MCP server: " + serverId);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_DELETED.value(), serverId);
                    handleGetMcpServers();
                });
            } else {
                LOG.warn("[CodexMcpServerActionHandlers] Codex MCP server not found: " + serverId);
                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = "Codex MCP server not found: " + serverId;
                    context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
                });
            }
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to delete Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = "Failed to delete Codex MCP server: " + e.getMessage();
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleToggleMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            codexMcpServerManager.upsertMcpServer(server);
            refreshGateway();

            boolean isEnabled = !server.has("enabled") || server.get("enabled").getAsBoolean();
            String serverId = server.get("id").getAsString();
            String serverName = server.has("name") ? server.get("name").getAsString() : serverId;

            LOG.info("[CodexMcpServerActionHandlers] Toggled Codex MCP server: " + serverName + " (enabled: " + isEnabled + ")");

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_TOGGLED.value(), content);
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to toggle Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = "Failed to toggle Codex MCP server: " + e.getMessage();
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleValidateMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            Map<String, Object> validation = codexMcpServerManager.validateMcpServer(server);
            String validationJson = gson.toJson(validation);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_VALIDATED.value(), validationJson);
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to validate Codex MCP server: " + e.getMessage(), e);
        }
    }

    // --- Private helpers ---

    private void sendToolsError(McpServerToolsRequest request, String errorMessage, Gson gson) {
        sendToolsResponse(McpServerToolsResponse.error(request, errorMessage), gson);
    }

    private void sendToolsResponse(McpServerToolsResponse response, Gson gson) {
        String json = gson.toJson(response);
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_TOOLS.value(), json)
        );
    }

    private boolean isCodexLocalConfigAuthorized() {
        try {
            return context.getSettingsService().isCodexLocalConfigAuthorized();
        } catch (Exception e) {
            LOG.warn("[CodexMcpServerActionHandlers] Failed to read Codex local authorization state: " + e.getMessage());
            return false;
        }
    }

    /**
     * Prepare server config by injecting cwd if not already set.
     * Mirrors upstream CodexMcpServerHandler.prepareServerConfig.
     */
    static JsonObject prepareServerConfig(JsonObject originalConfig, String sessionCwd, String projectBasePath) {
        JsonObject serverConfig = originalConfig.deepCopy();
        if (!serverConfig.has("cwd")) {
            String cwd = sessionCwd != null && !sessionCwd.trim().isEmpty()
                    ? sessionCwd
                    : projectBasePath;
            if (cwd != null && !cwd.trim().isEmpty()) {
                serverConfig.addProperty("cwd", cwd);
            }
        }
        return serverConfig;
    }

    private void refreshGateway() {
        // 异步化:refreshConfig 在 synchronized(lock) 内跑 ensureStarted(60s 复用探测窗)+applySnapshot(60s 超时),
        // 而本类增/改/删 handler 在 webview 消息分发线程(MessageDispatchGate 监视器内)调用本方法——同步执行会在
        // gateway 不健康时把整个 webview↔Java 消息通道串行卡住 ~130s。改投共享后台池,与
        // ProjectConfigHandler.handleSetMcpGatewayEnabled 的异步模式对齐(设置写入已在调用方同步完成)。
        Project project = context.getProject();
        if (project == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            if (project.isDisposed()) {
                return;
            }
            try {
                McpGatewayService.getInstance(project).refreshConfig(project.getBasePath());
            } catch (Exception e) {
                LOG.warn("[CodexMcpServerActionHandlers] Failed to refresh MCP Gateway: " + e.getMessage());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
