package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.payload.SessionCapabilitiesPayloadField;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;

import java.util.Locale;

/** Builds the backend-owned capability snapshot shown by the chat drawer. */
public final class SessionCapabilityService {
    private static final String EMPTY = "";
    private static final String ID_SEPARATOR = ":";
    private static final String MCP_STATUS_ERROR = "Unable to read MCP Gateway status";

    private SessionCapabilityService() {
    }

    public static String build(Project project, ClaudeSession session) {
        long observedAt = System.currentTimeMillis();
        JsonObject payload = new JsonObject();
        if (session == null) {
            addEmptySession(payload, observedAt);
            return GsonHolder.GSON.toJson(payload);
        }

        payload.addProperty(SessionCapabilitiesPayloadField.SESSION_ID.wireKey(), safe(session.getSessionId()));
        payload.addProperty(
                SessionCapabilitiesPayloadField.RUNTIME_EPOCH.wireKey(),
                safe(session.getRuntimeSessionEpoch())
        );
        payload.addProperty(SessionCapabilitiesPayloadField.PROVIDER.wireKey(), safe(session.getProvider()));
        payload.addProperty(SessionCapabilitiesPayloadField.OBSERVED_AT.wireKey(), observedAt);

        JsonArray mcp = new JsonArray();
        String mcpError = null;
        boolean available = false;
        if (project != null) {
            try {
                String statusJson = McpGatewayService.getInstance(project).statusJson();
                JsonElement root = JsonParser.parseString(
                        statusJson == null ? McpGatewayConstants.EMPTY_JSON_OBJECT : statusJson
                );
                if (root.isJsonObject()) {
                    JsonElement serversElement = root.getAsJsonObject().get(McpGatewayConstants.KEY_SERVERS);
                    if (serversElement != null && serversElement.isJsonArray()) {
                        available = true;
                        for (JsonElement serverElement : serversElement.getAsJsonArray()) {
                            appendServer(mcp, serverElement, session.getProvider());
                        }
                    }
                }
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                mcpError = MCP_STATUS_ERROR;
            } catch (RuntimeException e) {
                mcpError = MCP_STATUS_ERROR;
            }
        }
        payload.addProperty(SessionCapabilitiesPayloadField.MCP_AVAILABLE.wireKey(), available);
        if (mcpError == null) {
            payload.add(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), JsonNull.INSTANCE);
        } else {
            payload.addProperty(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), mcpError);
        }
        payload.add(SessionCapabilitiesPayloadField.MCP.wireKey(), mcp);
        payload.add(SessionCapabilitiesPayloadField.SKILLS.wireKey(), session.getSkillSnapshot().toJson());
        return GsonHolder.GSON.toJson(payload);
    }

    private static void addEmptySession(JsonObject payload, long observedAt) {
        payload.addProperty(SessionCapabilitiesPayloadField.SESSION_ID.wireKey(), EMPTY);
        payload.addProperty(SessionCapabilitiesPayloadField.RUNTIME_EPOCH.wireKey(), EMPTY);
        payload.addProperty(SessionCapabilitiesPayloadField.PROVIDER.wireKey(), EMPTY);
        payload.addProperty(SessionCapabilitiesPayloadField.OBSERVED_AT.wireKey(), observedAt);
        payload.addProperty(SessionCapabilitiesPayloadField.MCP_AVAILABLE.wireKey(), false);
        payload.add(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), JsonNull.INSTANCE);
        payload.add(SessionCapabilitiesPayloadField.MCP.wireKey(), new JsonArray());
        payload.add(SessionCapabilitiesPayloadField.SKILLS.wireKey(), new JsonArray());
    }

    private static void appendServer(JsonArray target, JsonElement element, String currentProvider) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject server = element.getAsJsonObject();
        String provider = stringValue(server, McpGatewayConstants.KEY_SOURCE_PROVIDER);
        if (provider != null && !provider.isEmpty() && currentProvider != null
                && !currentProvider.isEmpty() && !provider.equalsIgnoreCase(currentProvider)) {
            return;
        }
        String name = stringValue(server, McpGatewayConstants.KEY_SERVER_ID);
        if (name == null || name.isEmpty()) {
            name = SessionCapabilityState.UNKNOWN.value();
        }
        JsonObject item = new JsonObject();
        item.addProperty(
                SessionMcpCapabilityPayloadField.ID.wireKey(),
                (provider == null || provider.isEmpty() ? EMPTY : provider + ID_SEPARATOR) + name
        );
        item.addProperty(SessionMcpCapabilityPayloadField.NAME.wireKey(), name);
        item.addProperty(SessionMcpCapabilityPayloadField.PROVIDER.wireKey(), safe(provider));
        item.addProperty(
                SessionMcpCapabilityPayloadField.STATE.wireKey(),
                mapState(stringValue(server, McpGatewayConstants.KEY_STATE))
        );
        copyNullable(
                item,
                SessionMcpCapabilityPayloadField.LAST_ERROR.wireKey(),
                server.get(McpGatewayConstants.KEY_LAST_ERROR)
        );
        copyNullable(
                item,
                SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey(),
                server.get(McpGatewayConstants.KEY_LAST_SUCCESS_AT)
        );
        copyNumber(
                item,
                SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey(),
                server.get(McpGatewayConstants.KEY_FAILURE_COUNT)
        );
        item.addProperty(SessionMcpCapabilityPayloadField.OBSERVED.wireKey(), true);
        target.add(item);
    }

    private static String mapState(String state) {
        return state == null || state.isEmpty()
                ? SessionCapabilityState.UNKNOWN.value()
                : state.toLowerCase(Locale.ROOT);
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static void copyNullable(JsonObject target, String key, JsonElement value) {
        target.add(key, value == null ? JsonNull.INSTANCE : value.deepCopy());
    }

    private static void copyNumber(JsonObject target, String key, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            target.add(key, value.deepCopy());
        } else {
            target.addProperty(key, 0);
        }
    }

    private static String safe(String value) {
        return value == null ? EMPTY : value;
    }
}
