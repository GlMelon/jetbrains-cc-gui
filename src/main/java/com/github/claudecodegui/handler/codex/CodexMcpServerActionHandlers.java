package com.github.claudecodegui.handler.codex;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.provider.common.DaemonConstants;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.CodexMcpServerManager;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
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

    public CodexMcpServerActionHandlers(HandlerContext context, CodexMcpServerManager codexMcpServerManager) {
        this.context = context;
        this.codexMcpServerManager = codexMcpServerManager;
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handleGetMcpServers() {
        CompletableFuture.runAsync(() -> {
            try {
                if (!isCodexLocalConfigAuthorized()) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_LIST.value(), context.escapeJs("[]"));
                    });
                    return;
                }

                List<JsonObject> servers = codexMcpServerManager.getMcpServers();
                Gson gson = GsonHolder.GSON;
                String serversJson = gson.toJson(servers);

                LOG.info("[CodexMcpServerActionHandlers] Loaded " + servers.size() + " Codex MCP servers");

                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_LIST.value(), context.escapeJs(serversJson));
                });
            } catch (Exception e) {
                LOG.error("[CodexMcpServerActionHandlers] Failed to get Codex MCP servers: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_LIST.value(), context.escapeJs("[]"));
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
                        context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_STATUS.value(), context.escapeJs("[]"));
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
                        String serverStatus = status.has("status") ? status.get("status").getAsString() : DaemonConstants.UNKNOWN;
                        LOG.info("[CodexMcpServerActionHandlers] Server: " + serverName + ", Status: " + serverStatus);
                    }
                }

                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_STATUS.value(), context.escapeJs(statusJson));
                });
            } catch (Exception e) {
                LOG.error("[CodexMcpServerActionHandlers] Failed to get Codex MCP server status: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_STATUS.value(), context.escapeJs("[]"));
                });
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[CodexMcpServerActionHandlers] Unexpected error in handleGetMcpServerStatus: " + ex.getMessage(), ex);
            return null;
        });
    }

    void handleGetMcpServerTools(String content) {
        try {
            if (!isCodexLocalConfigAuthorized()) {
                Gson gson = GsonHolder.GSON;
                sendToolsError("", ClaudeCodeGuiBundle.message("error.codexLocalAccessNotAuthorized"), gson);
                return;
            }

            Gson gson = GsonHolder.GSON;
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json == null || !json.has("serverId")) {
                sendToolsError("", "Missing required field: serverId", gson);
                return;
            }
            String serverId = json.get("serverId").getAsString();

            JsonObject targetServer = null;
            List<JsonObject> servers = codexMcpServerManager.getMcpServers();
            for (JsonObject server : servers) {
                if (server.has("id") && serverId.equals(server.get("id").getAsString())) {
                    targetServer = server;
                    break;
                }
            }

            if (targetServer == null || !targetServer.has("server") || !targetServer.get("server").isJsonObject()) {
                sendToolsError(serverId, "Server not found or invalid config: " + serverId, gson);
                return;
            }

            JsonObject serverConfig = targetServer.getAsJsonObject("server");
            LOG.info("[CodexMcpServerActionHandlers] Getting tools for Codex MCP server: " + serverId);

            context.getCodexSDKBridge().getMcpServerTools(serverId, serverConfig)
                .thenAccept(result -> {
                    String resultJson = gson.toJson(result);
                    ApplicationManager.getApplication().invokeLater(() ->
                        context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_TOOLS.value(), context.escapeJs(resultJson))
                    );
                })
                .exceptionally(e -> {
                    LOG.error("[CodexMcpServerActionHandlers] Failed to get MCP server tools: " + e.getMessage(), e);
                    sendToolsError(serverId, e.getMessage(), gson);
                    return null;
                });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to get MCP server tools: " + e.getMessage(), e);
            Gson gson = GsonHolder.GSON;
            sendToolsError("", e.getMessage(), gson);
        }
    }

    void handleAddMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            codexMcpServerManager.upsertMcpServer(server);

            String serverId = server.has("id") ? server.get("id").getAsString() : DaemonConstants.UNKNOWN;
            LOG.info("[CodexMcpServerActionHandlers] Added Codex MCP server: " + serverId);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_ADDED.value(), context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to add Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs("Failed to add Codex MCP server: " + e.getMessage());
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleUpdateMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            codexMcpServerManager.upsertMcpServer(server);

            String serverId = server.has("id") ? server.get("id").getAsString() : DaemonConstants.UNKNOWN;
            LOG.info("[CodexMcpServerActionHandlers] Updated Codex MCP server: " + serverId);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_UPDATED.value(), context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to update Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs("Failed to update Codex MCP server: " + e.getMessage());
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
                LOG.info("[CodexMcpServerActionHandlers] Deleted Codex MCP server: " + serverId);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_DELETED.value(), context.escapeJs(serverId));
                    handleGetMcpServers();
                });
            } else {
                LOG.warn("[CodexMcpServerActionHandlers] Codex MCP server not found: " + serverId);
                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs("Codex MCP server not found: " + serverId);
                    context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
                });
            }
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to delete Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs("Failed to delete Codex MCP server: " + e.getMessage());
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleToggleMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            codexMcpServerManager.upsertMcpServer(server);

            boolean isEnabled = !server.has("enabled") || server.get("enabled").getAsBoolean();
            String serverId = server.get("id").getAsString();
            String serverName = server.has("name") ? server.get("name").getAsString() : serverId;

            LOG.info("[CodexMcpServerActionHandlers] Toggled Codex MCP server: " + serverName + " (enabled: " + isEnabled + ")");

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_TOGGLED.value(), context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to toggle Codex MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs("Failed to toggle Codex MCP server: " + e.getMessage());
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
                context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_VALIDATED.value(), context.escapeJs(validationJson));
            });
        } catch (Exception e) {
            LOG.error("[CodexMcpServerActionHandlers] Failed to validate Codex MCP server: " + e.getMessage(), e);
        }
    }

    // --- Private helpers ---

    private void sendToolsError(String serverId, String errorMessage, Gson gson) {
        JsonObject errorResult = new JsonObject();
        errorResult.addProperty("serverId", serverId != null ? serverId : "");
        errorResult.addProperty("error", errorMessage != null ? errorMessage : "Unknown error");
        errorResult.add("tools", new com.google.gson.JsonArray());
        String json = gson.toJson(errorResult);
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.CODEX_MCP_SERVER_TOOLS.value(), context.escapeJs(json))
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
}
