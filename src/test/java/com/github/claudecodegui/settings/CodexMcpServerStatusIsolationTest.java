package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CodexMcpServerStatusIsolationTest {

    @Test
    public void failedProbeDoesNotHideSuccessfulServerStatus() {
        List<JsonObject> servers = Arrays.asList(server("broken"), server("healthy"));

        List<JsonObject> result = CodexMcpServerManager.collectServerStatuses(servers, server -> {
            String name = server.get(CommonConstants.JSON_KEY_NAME).getAsString();
            if ("broken".equals(name)) {
                throw new IllegalStateException("handshake failed");
            }
            JsonObject status = new JsonObject();
            status.addProperty(CommonConstants.JSON_KEY_NAME, name);
            status.addProperty(CommonConstants.JSON_KEY_STATUS, CommonConstants.MCP_STATUS_CONNECTED);
            return status;
        });

        Assert.assertEquals(2, result.size());
        assertStatus(result.get(0), "broken", CommonConstants.MCP_STATUS_FAILED);
        Assert.assertEquals("handshake failed",
                result.get(0).get(CommonConstants.JSON_KEY_ERROR).getAsString());
        assertStatus(result.get(1), "healthy", CommonConstants.MCP_STATUS_CONNECTED);
    }

    private static JsonObject server(String name) {
        JsonObject server = new JsonObject();
        server.addProperty(CommonConstants.JSON_KEY_NAME, name);
        return server;
    }

    private static void assertStatus(JsonObject actual, String expectedName, String expectedStatus) {
        Assert.assertEquals(expectedName, actual.get(CommonConstants.JSON_KEY_NAME).getAsString());
        Assert.assertEquals(expectedStatus, actual.get(CommonConstants.JSON_KEY_STATUS).getAsString());
    }
}
