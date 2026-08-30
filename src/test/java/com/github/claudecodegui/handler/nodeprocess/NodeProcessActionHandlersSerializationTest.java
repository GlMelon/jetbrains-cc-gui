package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.mcp.McpGatewayLifecycleState;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.NodeProcessInfo;
import com.github.claudecodegui.service.RuntimeResourceDiagnostics;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class NodeProcessActionHandlersSerializationTest {

    @Test
    public void processListIncludesBackendOwnedRuntimeDiagnostics() {
        NodeProcessInfo process = NodeProcessInfo.builder()
                .kind(NodeProcessInfo.Kind.CLI_SESSION)
                .pid(42L)
                .alive(true)
                .build();
        RuntimeResourceDiagnostics diagnostics = RuntimeResourceDiagnostics.capture(
                Collections.singletonList(process),
                new CliPersistentProcessRegistry.Diagnostics(2, 1, 1, 3L, 4L),
                new McpGatewayService.Diagnostics(
                        McpGatewayLifecycleState.CATALOG_LOADING.value(),
                        null,
                        5L,
                        1,
                        false,
                        6L,
                        7L,
                        8L,
                        9L));

        String json = new NodeProcessActionHandlers(null)
                .buildProcessListJson(Collections.singletonList(process), diagnostics);
        JsonObject root = GsonHolder.GSON.fromJson(json, JsonObject.class);
        JsonObject payload = root.getAsJsonObject("diagnostics");

        assertEquals(0, payload.getAsJsonObject("activeProcesses").get("node").getAsInt());
        assertEquals(1, payload.getAsJsonObject("activeProcesses").get("cli").getAsInt());
        assertEquals(1, payload.getAsJsonObject("activeProcesses").get("mcp").getAsInt());
        assertEquals(2, payload.getAsJsonObject("activeProcesses").get("all").getAsInt());
        assertEquals(2, payload.getAsJsonObject("persistentRegistry").get("registrySize").getAsInt());
        assertEquals(4L, payload.getAsJsonObject("persistentRegistry")
                .get("rebuildCooldownHitCount").getAsLong());
        assertEquals(McpGatewayLifecycleState.CATALOG_LOADING.value(),
                payload.getAsJsonObject("gateway").get("lifecycleState").getAsString());
        assertEquals(9L, payload.getAsJsonObject("gateway").get("directDegradedCount").getAsLong());
        assertFalse(payload.getAsJsonObject("gateway").get("refreshInFlight").getAsBoolean());
    }
}
