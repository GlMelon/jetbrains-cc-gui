package com.github.claudecodegui.mcp;

import com.google.gson.JsonObject;

/**
 * Provider-neutral MCP server description consumed by the Node Gateway.
 */
public record McpGatewayServerSpec(
        String sourceProvider,
        String serverId,
        boolean enabled,
        String transport,
        JsonObject config
) {
    public McpGatewayServerSpec {
        if (sourceProvider == null || sourceProvider.isBlank()) {
            throw new IllegalArgumentException("sourceProvider required");
        }
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId required");
        }
        transport = transport == null || transport.isBlank()
                ? McpGatewayConstants.TRANSPORT_STDIO
                : transport;
        config = config != null ? config.deepCopy() : new JsonObject();
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty(McpGatewayConstants.KEY_SOURCE_PROVIDER, sourceProvider);
        obj.addProperty(McpGatewayConstants.KEY_SERVER_ID, serverId);
        obj.addProperty(McpGatewayConstants.KEY_ENABLED, enabled);
        obj.addProperty(McpGatewayConstants.KEY_TRANSPORT, transport);
        obj.add(McpGatewayConstants.KEY_CONFIG, config.deepCopy());
        return obj;
    }
}
