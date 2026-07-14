package com.github.claudecodegui.handler.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Typed downstream payload for MCP tools results. Every terminal path carries
 * the originating request and server identifiers so the webview can reject
 * stale asynchronous responses deterministically.
 */
public record McpServerToolsResponse(
        String requestId,
        String serverId,
        String serverName,
        JsonArray tools,
        String error
) {
    private static final String FIELD_SERVER_NAME = "serverName";
    private static final String FIELD_TOOLS = "tools";
    private static final String FIELD_ERROR = "error";

    public McpServerToolsResponse {
        requestId = requestId == null ? "" : requestId;
        serverId = serverId == null ? "" : serverId;
        serverName = serverName == null ? "" : serverName;
        tools = tools == null ? new JsonArray() : tools;
    }

    public static McpServerToolsResponse fromBridge(McpServerToolsRequest request, JsonObject result) {
        if (result == null) {
            return error(request, "Empty tools response");
        }
        String serverName = getString(result, FIELD_SERVER_NAME);
        String error = getString(result, FIELD_ERROR);
        JsonArray tools = result.has(FIELD_TOOLS) && result.get(FIELD_TOOLS).isJsonArray()
                ? result.getAsJsonArray(FIELD_TOOLS)
                : new JsonArray();
        return new McpServerToolsResponse(
                request.requestId(),
                request.serverId(),
                serverName,
                tools,
                error.isEmpty() ? null : error
        );
    }

    public static McpServerToolsResponse error(McpServerToolsRequest request, String message) {
        String requestId = request != null ? request.requestId() : "";
        String serverId = request != null ? request.serverId() : "";
        String normalizedMessage = message == null || message.isBlank() ? "Unknown error" : message;
        return new McpServerToolsResponse(requestId, serverId, "", new JsonArray(), normalizedMessage);
    }

    private static String getString(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString()
                : "";
    }
}
