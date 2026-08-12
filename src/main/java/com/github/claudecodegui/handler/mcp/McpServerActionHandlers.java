package com.github.claudecodegui.handler.mcp;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.claude.ClaudeMcpService;
import com.github.claudecodegui.protocol.DownstreamEvent;
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
    private final ClaudeMcpService claudeMcpService;

    public McpServerActionHandlers(HandlerContext context) {
        this.context = context;
        this.claudeMcpService = new ClaudeMcpService();
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
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_LIST.value(), context.escapeJs(serversJson));
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to get MCP servers: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_LIST.value(), context.escapeJs("[]"));
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

    void handleGetMcpServerTools(McpServerToolsRequest request) {
        Gson gson = GsonHolder.GSON;
        if (request == null || !request.isValid()) {
            LOG.warn("[McpServerActionHandlers] Rejected invalid MCP tools request");
            sendToolsResponse(McpServerToolsResponse.error(request, "Missing required requestId or serverId"), gson);
            return;
        }

        LOG.info("[McpServerActionHandlers] Getting tools for server: " + request.serverId());
        waitForBridgeAndFetchTools(request, gson);
    }

    void handleAddMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            context.getSettingsService().upsertMcpServer(server);
            refreshGateway();

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_ADDED.value(), context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to add MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message("mcp.addServerFailedWithReason", e.getMessage()));
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
            });
        }
    }

    void handleUpdateMcpServer(String content) {
        try {
            Gson gson = GsonHolder.GSON;
            JsonObject server = gson.fromJson(content, JsonObject.class);

            context.getSettingsService().upsertMcpServer(server);
            refreshGateway();

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_UPDATED.value(), context.escapeJs(content));
                handleGetMcpServers();
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to update MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message("mcp.updateServerFailedWithReason", e.getMessage()));
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
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
                refreshGateway();
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.MCP_SERVER_DELETED.value(), context.escapeJs(serverId));
                    handleGetMcpServers();
                });
            } else {
                ApplicationManager.getApplication().invokeLater(() -> {
                    String reason = ClaudeCodeGuiBundle.message("mcp.serverNotFound");
                    String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message(
                            "mcp.deleteServerFailedWithReason", reason));
                    context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
                });
            }
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to delete MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                String errorMsg = context.escapeJs(ClaudeCodeGuiBundle.message("mcp.deleteServerFailedWithReason", e.getMessage()));
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), errorMsg);
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
            refreshGateway();

            boolean isEnabled = !server.has("enabled") || server.get("enabled").getAsBoolean();
            String serverId = server.get("id").getAsString();
            String serverName = server.has("name") ? server.get("name").getAsString() : serverId;

            LOG.info("[McpServerActionHandlers] Toggled MCP server: " + serverName + " (enabled: " + isEnabled + ")");

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_TOGGLED.value(), context.escapeJs(content));
                handleGetMcpServers();
                handleGetMcpServerStatus();
            });
        } catch (Exception e) {
            LOG.error("[McpServerActionHandlers] Failed to toggle MCP server: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), context.escapeJs("切换 MCP 服务器状态失败: " + e.getMessage()));
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
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_VALIDATED.value(), context.escapeJs(validationJson));
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

                claudeMcpService.getMcpServerStatus(cwd)
                    .thenAccept(statusList -> {
                        Gson gson = GsonHolder.GSON;
                        String statusJson = gson.toJson(statusList);

                        LOG.info("[McpServerActionHandlers] MCP server status received: " + statusList.size() + " servers");
                        for (JsonObject status : statusList) {
                            if (status.has("name")) {
                                String serverName = status.get("name").getAsString();
                                String serverStatus = status.has("status") ? status.get("status").getAsString() : CommonConstants.UNKNOWN;
                                LOG.info("[McpServerActionHandlers] Server: " + serverName + ", Status: " + serverStatus);
                            }
                        }

                        ApplicationManager.getApplication().invokeLater(() -> {
                            context.dispatchEvent(DownstreamEvent.MCP_SERVER_STATUS.value(), context.escapeJs(statusJson));
                        });
                    })
                    .exceptionally(e -> {
                        LOG.error("[McpServerActionHandlers] Failed to get MCP server status: "
                            + e.getMessage(), e);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            context.dispatchEvent(DownstreamEvent.MCP_SERVER_STATUS.value(), context.escapeJs("[]"));
                        });
                        return null;
                    });
                publishGatewayStatus();
            } catch (Exception e) {
                LOG.error("[McpServerActionHandlers] Error while waiting for bridge or fetching status: "
                    + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent(DownstreamEvent.MCP_SERVER_STATUS.value(), context.escapeJs("[]"));
                });
            }
        });
    }

    private void refreshGateway() {
        try {
            if (context.getProject() != null) {
                String projectPath = context.getProject().getBasePath();
                McpGatewayService.getInstance(context.getProject()).refreshConfig(projectPath);
            }
        } catch (Exception e) {
            LOG.warn("[McpServerActionHandlers] Failed to refresh MCP Gateway: " + e.getMessage());
        }
    }

    /**
     * 手动重载 MCP Gateway(用户点"重载 Gateway"):硬重置 + 重建。自动加载失败时用。
     * 异步执行(pooled 线程,参照 ProjectConfigHandler.handleSetMcpGatewayEnabled 的模式);
     * 成功发 {@code TOAST_SUCCESS_I18N}(i18n key)+ 推 gateway 状态,失败发 {@code TOAST_ERROR}(带原因)。
     */
    void handleReloadMcpGateway() {
        if (context.getProject() == null) {
            return;
        }
        String projectPath = context.getProject().getBasePath();
        CompletableFuture.runAsync(() -> {
            try {
                McpGatewayService.getInstance(context.getProject()).reloadGateway(projectPath);
                publishGatewayStatus();
                ApplicationManager.getApplication().invokeLater(() ->
                        context.dispatchEvent(DownstreamEvent.TOAST_SUCCESS_I18N.value(),
                                context.escapeJs("mcp.gatewayReloaded")));
            } catch (Exception e) {
                LOG.warn("[McpServerActionHandlers] Failed to reload MCP Gateway: " + e.getMessage(), e);
                publishGatewayStatus();
                ApplicationManager.getApplication().invokeLater(() -> {
                    String msg = ClaudeCodeGuiBundle.message("mcp.gatewayReloadFailed", e.getMessage());
                    context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), context.escapeJs(msg));
                });
            }
        });
    }

    private void publishGatewayStatus() {
        try {
            if (context.getProject() == null) {
                return;
            }
            String status = McpGatewayService.getInstance(context.getProject()).statusJson();
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MCP_GATEWAY_STATUS.value(), context.escapeJs(status));
            });
        } catch (Exception e) {
            LOG.warn("[McpServerActionHandlers] Failed to publish MCP Gateway status: " + e.getMessage());
        }
    }

    private void waitForBridgeAndFetchTools(McpServerToolsRequest request, Gson gson) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!BridgePreloader.isBridgeReady()) {
                    LOG.info("[McpServerActionHandlers] Bridge not ready yet for tools, waiting...");
                    boolean ready = BridgePreloader.waitForBridgeAsync()
                        .get(10, TimeUnit.SECONDS);
                    if (ready) {
                        LOG.info("[McpServerActionHandlers] Bridge is now ready, fetching tools");
                    } else {
                        LOG.warn("[McpServerActionHandlers] Bridge still not ready after timeout");
                        sendToolsResponse(McpServerToolsResponse.error(request, "AI bridge is not ready"), gson);
                        return;
                    }
                }

                String toolsCwd = context.getProject() != null
                        ? context.getProject().getBasePath() : null;
                claudeMcpService.getMcpServerTools(request.serverId(), toolsCwd)
                    .thenAccept(result -> {
                        McpServerToolsResponse response = McpServerToolsResponse.fromBridge(request, result);
                        LOG.info("[McpServerActionHandlers] Got tools result for request: " + request.requestId());
                        sendToolsResponse(response, gson);
                    })
                    .exceptionally(e -> {
                        LOG.error("[McpServerActionHandlers] Failed to get MCP server tools: "
                            + e.getMessage(), e);
                        sendToolsResponse(McpServerToolsResponse.error(request, e.getMessage()), gson);
                        return null;
                    });
            } catch (Exception e) {
                LOG.error("[McpServerActionHandlers] Error while waiting for bridge or fetching tools: "
                    + e.getMessage(), e);
                sendToolsResponse(McpServerToolsResponse.error(request, e.getMessage()), gson);
            }
        });
    }

    private void sendToolsResponse(McpServerToolsResponse response, Gson gson) {
        String json = gson.toJson(response);
        ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.MCP_SERVER_TOOLS.value(), context.escapeJs(json))
        );
    }
}
