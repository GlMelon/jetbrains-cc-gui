package com.github.claudecodegui.handler.opencode;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class OpenCodeMcpServerStatusTest {

    @Test
    public void mergeKeepsReadyAndBackoffServersIndependent() {
        List<JsonObject> configured = Arrays.asList(configured("healthy", true), configured("broken", true));
        String statusJson = gatewayStatus(
                gatewayServer("healthy", McpGatewayConstants.STATE_READY, null),
                gatewayServer("broken", McpGatewayConstants.STATE_BACKOFF, "connection refused")
        );

        List<JsonObject> result = OpenCodeMcpServerActionHandlers.mergeOpenCodeStatuses(configured, statusJson);

        Assert.assertEquals(2, result.size());
        assertStatus(result.get(0), "healthy", CommonConstants.MCP_STATUS_CONNECTED);
        assertStatus(result.get(1), "broken", CommonConstants.MCP_STATUS_FAILED);
        Assert.assertEquals("connection refused",
                result.get(1).get(CommonConstants.JSON_KEY_ERROR).getAsString());
    }

    @Test
    public void mergeFillsMissingGatewayStatusesFromConfiguration() {
        List<JsonObject> configured = Arrays.asList(configured("waiting", true), configured("disabled", false));

        List<JsonObject> result = OpenCodeMcpServerActionHandlers.mergeOpenCodeStatuses(configured, "{}");

        Assert.assertEquals(2, result.size());
        assertStatus(result.get(0), "waiting", CommonConstants.MCP_STATUS_PENDING);
        assertStatus(result.get(1), "disabled", CommonConstants.MCP_STATUS_DISABLED);
    }

    @Test
    public void malformedGatewayEntryDoesNotHideHealthyServer() {
        JsonObject malformed = new JsonObject();
        malformed.addProperty(McpGatewayConstants.KEY_SOURCE_PROVIDER, CommonConstants.PROVIDER_OPENCODE);
        malformed.add(McpGatewayConstants.KEY_SERVER_ID, new JsonObject());
        String statusJson = gatewayStatus(
                malformed,
                gatewayServer("healthy", McpGatewayConstants.STATE_DEGRADED, null)
        );

        List<JsonObject> result = OpenCodeMcpServerActionHandlers.mergeOpenCodeStatuses(
                List.of(configured("healthy", true)), statusJson);

        Assert.assertEquals(1, result.size());
        assertStatus(result.get(0), "healthy", CommonConstants.MCP_STATUS_CONNECTED);
    }

    @Test
    public void malformedConfiguredEntryDoesNotHideHealthyServer() {
        JsonObject malformed = configured("malformed", true);
        malformed.add(McpGatewayConstants.KEY_ENABLED, new JsonObject());
        List<JsonObject> configured = Arrays.asList(malformed, configured("healthy", true));

        List<JsonObject> result = OpenCodeMcpServerActionHandlers.mergeOpenCodeStatuses(
                configured,
                gatewayStatus(gatewayServer("healthy", McpGatewayConstants.STATE_READY, null))
        );

        Assert.assertEquals(2, result.size());
        assertStatus(result.get(0), "malformed", CommonConstants.MCP_STATUS_PENDING);
        assertStatus(result.get(1), "healthy", CommonConstants.MCP_STATUS_CONNECTED);
    }

    private static JsonObject configured(String id, boolean enabled) {
        JsonObject server = new JsonObject();
        server.addProperty(CommonConstants.JSON_KEY_ID, id);
        server.addProperty(McpGatewayConstants.KEY_ENABLED, enabled);
        return server;
    }

    private static JsonObject gatewayServer(String id, String state, String lastError) {
        JsonObject server = new JsonObject();
        server.addProperty(McpGatewayConstants.KEY_SOURCE_PROVIDER, CommonConstants.PROVIDER_OPENCODE);
        server.addProperty(McpGatewayConstants.KEY_SERVER_ID, id);
        server.addProperty(McpGatewayConstants.KEY_STATE, state);
        if (lastError != null) {
            server.addProperty(McpGatewayConstants.KEY_LAST_ERROR, lastError);
        }
        return server;
    }

    private static String gatewayStatus(JsonObject... servers) {
        JsonArray array = new JsonArray();
        for (JsonObject server : servers) {
            array.add(server);
        }
        JsonObject root = new JsonObject();
        root.add(McpGatewayConstants.KEY_SERVERS, array);
        return root.toString();
    }

    private static void assertStatus(JsonObject actual, String expectedName, String expectedStatus) {
        Assert.assertEquals(expectedName, actual.get(CommonConstants.JSON_KEY_NAME).getAsString());
        Assert.assertEquals(expectedStatus, actual.get(CommonConstants.JSON_KEY_STATUS).getAsString());
    }
}
