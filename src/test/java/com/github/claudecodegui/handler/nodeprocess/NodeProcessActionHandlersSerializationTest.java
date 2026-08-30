package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.mcp.McpGatewayLifecycleState;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.payload.NodeProcessActiveProcessesPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessDiagnosticsPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessGatewayPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessInfoPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessPersistentRegistryPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessSnapshotPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessTotalsPayloadField;
import com.github.claudecodegui.service.NodeProcessInfo;
import com.github.claudecodegui.service.RuntimeResourceDiagnostics;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void processListWireKeysMatchPayloadFieldDeclarations() {
        NodeProcessInfo process = NodeProcessInfo.builder()
                .id("cli-session-42")
                .kind(NodeProcessInfo.Kind.CLI_SESSION)
                .provider("codex")
                .pid(42L)
                .alive(true)
                .startedAtMs(100L)
                .uptimeMs(200L)
                .command("codex exec")
                .heapUsedBytes(300L)
                .activeRequestCount(1)
                .channelId("channel-1")
                .sessionId("session-1")
                .tabName("AI1")
                .build();

        String json = new NodeProcessActionHandlers(null)
                .buildProcessListJson(Collections.singletonList(process), RuntimeResourceDiagnostics.empty());
        JsonObject root = GsonHolder.GSON.fromJson(json, JsonObject.class);
        JsonObject processJson = root
                .getAsJsonArray(NodeProcessSnapshotPayloadField.PROCESSES.wireKey())
                .get(0)
                .getAsJsonObject();
        JsonObject totalsJson = root.getAsJsonObject(NodeProcessSnapshotPayloadField.TOTALS.wireKey());
        JsonObject diagnosticsJson = root.getAsJsonObject(
                NodeProcessSnapshotPayloadField.DIAGNOSTICS.wireKey());
        JsonObject activeProcessesJson = diagnosticsJson.getAsJsonObject(
                NodeProcessDiagnosticsPayloadField.ACTIVE_PROCESSES.wireKey());
        JsonObject persistentRegistryJson = diagnosticsJson.getAsJsonObject(
                NodeProcessDiagnosticsPayloadField.PERSISTENT_REGISTRY.wireKey());
        JsonObject gatewayJson = diagnosticsJson.getAsJsonObject(
                NodeProcessDiagnosticsPayloadField.GATEWAY.wireKey());

        assertEquals(NodeProcessSnapshotPayloadField.wireKeys(), root.keySet());
        assertEquals(NodeProcessInfoPayloadField.wireKeys(), processJson.keySet());
        assertEquals(NodeProcessTotalsPayloadField.wireKeys(), totalsJson.keySet());
        assertEquals(NodeProcessDiagnosticsPayloadField.wireKeys(), diagnosticsJson.keySet());
        assertEquals(NodeProcessActiveProcessesPayloadField.wireKeys(), activeProcessesJson.keySet());
        assertEquals(
                NodeProcessPersistentRegistryPayloadField.wireKeys(), persistentRegistryJson.keySet());
        assertEquals(NodeProcessGatewayPayloadField.wireKeys(), gatewayJson.keySet());
        assertEquals(
                NodeProcessInfo.Kind.CLI_SESSION.value(),
                processJson.get(NodeProcessInfoPayloadField.KIND.wireKey()).getAsString());
        assertTrue(gatewayJson.get(NodeProcessGatewayPayloadField.LAST_FAILURE.wireKey()).isJsonNull());
    }

}
