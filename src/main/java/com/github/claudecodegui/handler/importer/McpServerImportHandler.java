package com.github.claudecodegui.handler.importer;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.mcp.importer.McpServerImportService;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * Typed handler for {@link UpstreamAction#PARSE_COPILOT_MCP_CONFIG}.
 *
 * <p>Parses an external MCP configuration (GitHub Copilot format) into internal server entries
 * via {@link McpServerImportService} and sends them back to the webview as an import preview
 * ({@link DownstreamEvent#MCP_IMPORT_PREVIEW}). Persisting the previewed servers stays on the
 * existing add/save path in the webview ({@code ADD_MCP_SERVER}/{@code ADD_CODEX_MCP_SERVER}) —
 * the backend only does the format mapping, the frontend only pastes / previews / confirms.
 */
public final class McpServerImportHandler implements FrontendActionHandler<String> {

    private static final Logger LOG = Logger.getInstance(McpServerImportHandler.class);
    private static final Gson GSON = new Gson();

    private final McpServerImportService importService;

    public McpServerImportHandler() {
        this(new McpServerImportService());
    }

    /** Test-only: inject a fake import service. */
    McpServerImportHandler(McpServerImportService importService) {
        this.importService = importService;
    }

    @Override
    public UpstreamAction action() {
        return UpstreamAction.PARSE_COPILOT_MCP_CONFIG;
    }

    @Override
    public Class<String> payloadType() {
        return String.class;
    }

    @Override
    public void handle(String payload, FrontendActionContext context) {
        JsonObject response = new JsonObject();
        try {
            JsonObject request = GSON.fromJson(payload, JsonObject.class);
            boolean isCodexMode = request != null
                && request.has("isCodexMode")
                && !request.get("isCodexMode").isJsonNull()
                && request.get("isCodexMode").getAsBoolean();

            String rawJson = request != null && request.has("json") && !request.get("json").isJsonNull()
                ? request.get("json").getAsString()
                : null;
            if (rawJson == null || rawJson.trim().isEmpty()) {
                throw new IllegalArgumentException("Configuration is empty.");
            }

            JsonObject config = GSON.fromJson(rawJson, JsonObject.class);
            List<JsonObject> servers = importService.parseCopilotConfig(config, isCodexMode);
            response.add("servers", GSON.toJsonTree(servers));
        } catch (Exception e) {
            LOG.warn("Failed to parse Copilot MCP config: " + e.getMessage());
            response.add("servers", new JsonArray());
            response.addProperty("error", e.getMessage());
        }

        String json = GSON.toJson(response);
        var ctx = context.handlerContext();
        ApplicationManager.getApplication().invokeLater(
            () -> ctx.dispatchEvent(DownstreamEvent.MCP_IMPORT_PREVIEW.value(), json));
    }
}
