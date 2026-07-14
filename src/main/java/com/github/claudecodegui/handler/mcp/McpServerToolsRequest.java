package com.github.claudecodegui.handler.mcp;

/**
 * Typed upstream payload for a single MCP server tools request.
 */
public record McpServerToolsRequest(
        String requestId,
        String serverId,
        boolean forceRefresh
) {
    public McpServerToolsRequest {
        requestId = normalize(requestId);
        serverId = normalize(serverId);
    }

    public boolean isValid() {
        return !requestId.isEmpty() && !serverId.isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
