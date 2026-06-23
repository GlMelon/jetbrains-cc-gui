package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.startup.BridgePreloader;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * MCP server action handlers container.
 * Holds shared logic for MCP server management operations.
 */
public class McpServerActionHandlers {

    private static final Logger LOG = Logger.getInstance(McpServerActionHandlers.class);

    private final HandlerContext context;

    public McpServerActionHandlers(HandlerContext context) {
        this.context = context;
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handleGetMcpServers() {
        try {
            String projectPath = context.getProject() != null
                ? context.getProject().getBasePath()
                : null;

            List<JsonObject> servers = context.getSettingsService().getMcpServersWithProjectPath(projectPath);
            Gson gson = GsonHolder.GSON;
            String serversJson = gson.toJson(servers);

            LOG.info("[McpServerActionHandlers] Loaded " + servers.size() + " MCP servers for project: "
                + (projectPath != null ? projectPath : "(no project)"));

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("mcp.server_list", context.escapeJs(serversJson));
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to get MCP servers: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("mcp.server_list", context.escapeJs("[]"));
            });
        }
    }

    void handleGetMcpServerStatus() {
        try {
            String cwd = context.getProject() != null
                ? context.getProject().getBasePath()
                : null;
            waitForBridgeAndFetchStatus(cwd);
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to get MCP server status: " + e.getMessage(), e);
        }
    }

    void handleGetMcpServerTools(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String serverId = json.get("serverId").getAsString();

            LOG.info("[McpServerActionHandlers] Getting tools for server: " + serverId);
            waitForBridgeAndFetchTools(serverId, gson);
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to get MCP server tools: " + e.getMessage(), e);
        }
    }

    void handleAddMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            context.getSettingsService().upsertMcpServer(server);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("mcp.server_added", context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to add MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message("mcp.addServerFailedWithReason", e.getMessage()));
                context.dispatchEvent("toast.error", errorMsg);
            });
        }
    }

    void handleUpdateMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            context.getSettingsService().upsertMcpServer(server);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("mcp.server_updated", context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to update MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message("mcp.updateServerFailedWithReason", e.getMessage()));
                context.dispatchEvent("toast.error", errorMsg);
            });
        }
    }

    void handleDeleteMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String serverId = json.get("id").getAsString();

            boolean success = context.getSettingsService().deleteMcpServer(serverId);

            if (success) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent("mcp.server_deleted", context.escapeJs(serverId));
                    handleGetMcpServers();
                });
            } else {
                ApplicationManager.getApplication().invokeLater(() -> {
                    String reason = ClaudeCodeGuiBundle.message("mcp.serverNotFound");
                    String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message(
                            "mcp.deleteServerFailedWithReason", reason));
                    context.dispatchEvent("toast.error", errorMsg);
                });
            }
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to delete MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message("mcp.deleteServerFailedWithReason", e.getMessage()));
                context.dispatchEvent("toast.error", errorMsg);
            });
        }
    }

    void handleToggleMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            String projectPath = context.getProject() != null
                ? context.getProject().getBasePath()
                : null;
            context.getSettingsService().upsertMcpServer(server, projectPath);

            boolean isEnabled = !server.has("enabled") || server.get("enabled").getAsBoolean();
            String serverId = server.get("id").getAsString();
            String serverName = server.has("name") ? server.get("name").getAsString() : serverId;

            LOG.info("[McpServerActionHandlers] Toggled MCP server: " + serverName + " (enabled: " + isEnabled + ")");

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("mcp.server_toggled", context.escapeJs(content));
                handleGetMcpServers();
                handleGetMcpServerStatus();
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to toggle MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("toast.error", context.escapeJs("切换 MCP 服务器状态失败: " + e.getMessage()));
            });
        }
    }

    void handleValidateMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            Map<String, Object> validation = context.getSettingsService().validateMcpServer(server);
            String validationJson = gson.toJson(validation);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent("mcp.server_validated", context.escapeJs(validationJson));
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to validate MCP server: " + e.getMessage(), e);
        }
    }

    // --- Private helpers ---

    private void waitForBridgeAndFetchStatus(String cwd) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!BridgePreloader.isBridgeReady()) {
                    LOG.info("[McpServerActionHandlers] Bridge not ready yet, waiting...");
                    boolean ready = BridgePreloader.waitForBridgeAsync()
                        .get(10, TimeUnit.SECONDS);
                    if (ready) {
                        LOG.info("[McpServerActionHandlers] Bridge is now ready, fetching status");
                    } else {
                        LOG.warn("[McpServerActionHandlers] Bridge still not ready after timeout, proceeding anyway");
                    }
                }

                context.getClaudeSDKBridge().getMcpServerStatus(cwd)
                    .thenAccept(statusList -> {
                        Gson gson = GsonHolder.GSON;
                        String statusJson = gson.toJson(statusList);

                        LOG.info("[McpServerActionHandlers] MCP server status received: " + statusList.size() + " servers");
                        for (JsonObject status : statusList) {
                            if (status.has("name")) {
                                String serverName = status.get("name").getAsString();
                                String serverStatus = status.has("status") ? status.get("status").getAsString() : "unknown";
                                LOG.info("[McpServerActionHandlers] Server: " + serverName + ", Status: " + serverStatus);
                            }
                        }

                        ApplicationManager.getApplication().invokeLater(() -> {
                            context.dispatchEvent("mcp.server_status", context.escapeJs(statusJson));
                        });
                    })
                    .exceptionally(e -> {
                        LOG.error("[McpServerActionHandlers] Failed to get MCP server status: "
                            + e.getMessage(), e);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            context.dispatchEvent("mcp.server_status", context.escapeJs("[]"));
                        });
                        return null;
                    });
            } catch (Exception e) {
                LOG.error("[McpServerActionHandlers] Error while waiting for bridge or fetching status: "
                    + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent("mcp.server_status", context.escapeJs("[]"));
                });
            }
        });
    }

    private void waitForBridgeAndFetchTools(String serverId, Gson gson) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!BridgePreloader.isBridgeReady()) {
                    LOG.info("[McpServerActionHandlers] Bridge not ready yet for tools, waiting...");
                    boolean ready = BridgePreloader.waitForBridgeAsync()
                        .get(10, TimeUnit.SECONDS);
                    if (ready) {
                        LOG.info("[McpServerActionHandlers] Bridge is now ready, fetching tools");
                    } else {
                        LOG.warn("[McpServerActionHandlers] Bridge still not ready after timeout, proceeding anyway");
                    }
                }

                String toolsCwd = context.getProject() != null
                        ? context.getProject().getBasePath() : null;
                context.getClaudeSDKBridge().getMcpServerTools(serverId, toolsCwd)
                    .thenAccept(result -> {
                        String resultJson = gson.toJson(result);
                        LOG.info("[McpServerActionHandlers] Got tools result: " + resultJson);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            context.dispatchEvent("mcp.server_tools", context.escapeJs(resultJson));
                        });
                    })
                    .exceptionally(e -> {
                        LOG.error("[McpServerActionHandlers] Failed to get MCP server tools: "
                            + e.getMessage(), e);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            JsonObject errorResult = new JsonObject();
                            errorResult.addProperty("serverId", serverId);
                            errorResult.addProperty("error", e.getMessage());
                            errorResult.add("tools", new com.google.gson.JsonArray());
                            context.dispatchEvent("mcp.server_tools", context.escapeJs(gson.toJson(errorResult)));
                        });
                        return null;
                    });
            } catch (Exception e) {
                LOG.error("[McpServerActionHandlers] Error while waiting for bridge or fetching tools: "
                    + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject errorResult = new JsonObject();
                    errorResult.addProperty("serverId", serverId);
                    errorResult.addProperty("error", e.getMessage());
                    errorResult.add("tools", new com.google.gson.JsonArray());
                    context.dispatchEvent("mcp.server_tools", context.escapeJs(gson.toJson(errorResult)));
                });
            }
        });
    }
}
