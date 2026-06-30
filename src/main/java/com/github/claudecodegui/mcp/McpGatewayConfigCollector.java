package com.github.claudecodegui.mcp;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.OpenCodeConfigReader;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects provider MCP settings into a provider-neutral Gateway snapshot.
 */
public class McpGatewayConfigCollector {
    private static final Logger LOG = Logger.getInstance(McpGatewayConfigCollector.class);

    private final CodemossSettingsService settingsService;

    public McpGatewayConfigCollector(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public McpGatewayConfigSnapshot collect(long revision, String projectPath) {
        List<McpGatewayServerSpec> servers = new ArrayList<>();
        collectClaude(projectPath, servers);
        collectCodex(servers);
        collectOpenCode(servers);
        return McpGatewayConfigSnapshot.create(revision, projectPath, servers);
    }

    private void collectClaude(String projectPath, List<McpGatewayServerSpec> servers) {
        try {
            for (JsonObject server : settingsService.getMcpServersWithProjectPath(projectPath)) {
                McpGatewayServerSpec spec = fromFrontendShape(CommonConstants.PROVIDER_CLAUDE, server);
                if (spec != null) {
                    servers.add(spec);
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to collect Claude MCP settings: " + e.getMessage());
        }
    }

    private void collectCodex(List<McpGatewayServerSpec> servers) {
        try {
            for (JsonObject server : settingsService.getCodexMcpServerManager().getMcpServers()) {
                McpGatewayServerSpec spec = fromFrontendShape(CommonConstants.PROVIDER_CODEX, server);
                if (spec != null) {
                    servers.add(spec);
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to collect Codex MCP settings: " + e.getMessage());
        }
    }

    private void collectOpenCode(List<McpGatewayServerSpec> servers) {
        try {
            for (JsonObject server : OpenCodeConfigReader.readMcpServers()) {
                String id = getString(server, CommonConstants.JSON_KEY_ID);
                if (id == null || id.isBlank()) {
                    continue;
                }
                boolean enabled = !server.has(McpGatewayConstants.KEY_ENABLED)
                        || server.get(McpGatewayConstants.KEY_ENABLED).getAsBoolean();
                JsonObject config = server.deepCopy();
                String transport = normalizeOpenCodeTransport(getString(config, McpGatewayConstants.KEY_TYPE));
                servers.add(new McpGatewayServerSpec(
                        CommonConstants.PROVIDER_OPENCODE,
                        id,
                        enabled,
                        transport,
                        config
                ));
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to collect OpenCode MCP settings: " + e.getMessage());
        }
    }

    private static McpGatewayServerSpec fromFrontendShape(String provider, JsonObject server) {
        if (server == null) {
            return null;
        }
        String id = getString(server, CommonConstants.JSON_KEY_ID);
        if (id == null || id.isBlank()) {
            id = getString(server, CommonConstants.JSON_KEY_NAME);
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        boolean enabled = !server.has(McpGatewayConstants.KEY_ENABLED)
                || server.get(McpGatewayConstants.KEY_ENABLED).getAsBoolean();
        JsonObject config = server.has("server") && server.get("server").isJsonObject()
                ? server.getAsJsonObject("server").deepCopy()
                : server.deepCopy();
        String transport = normalizeTransport(getString(config, McpGatewayConstants.KEY_TYPE), config);
        return new McpGatewayServerSpec(provider, id, enabled, transport, config);
    }

    private static String normalizeTransport(String type, JsonObject config) {
        if (type != null) {
            String lower = type.trim().toLowerCase();
            if (CommonConstants.MCP_TRANSPORT_HTTP.equals(lower)) {
                return McpGatewayConstants.TRANSPORT_HTTP;
            }
            if (CommonConstants.MCP_TRANSPORT_SSE.equals(lower)) {
                return McpGatewayConstants.TRANSPORT_SSE;
            }
        }
        if (config != null && config.has(McpGatewayConstants.KEY_URL)) {
            return McpGatewayConstants.TRANSPORT_HTTP;
        }
        return McpGatewayConstants.TRANSPORT_STDIO;
    }

    private static String normalizeOpenCodeTransport(String type) {
        if (type == null || type.isBlank() || "local".equalsIgnoreCase(type)) {
            return McpGatewayConstants.TRANSPORT_STDIO;
        }
        return normalizeTransport(type, null);
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.isJsonPrimitive() ? element.getAsString() : GsonHolder.GSON.toJson(element);
    }
}
