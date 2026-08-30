package com.github.claudecodegui.service;

import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.mcp.McpGatewayLifecycleState;
import com.github.claudecodegui.mcp.McpGatewayService;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class RuntimeResourceDiagnosticsTest {

    @Test
    public void captureAggregatesActiveProcessesByLifecycleOwner() {
        RuntimeResourceDiagnostics diagnostics = RuntimeResourceDiagnostics.capture(
                Arrays.asList(
                        process(NodeProcessInfo.Kind.CHANNEL, true),
                        process(NodeProcessInfo.Kind.ORPHAN, true),
                        process(NodeProcessInfo.Kind.DAEMON, false),
                        process(NodeProcessInfo.Kind.CLI_SESSION, true),
                        null),
                new CliPersistentProcessRegistry.Diagnostics(3, 2, 1, 4L, 5L),
                new McpGatewayService.Diagnostics(
                        McpGatewayLifecycleState.READY.value(),
                        null,
                        7L,
                        2,
                        true,
                        8L,
                        9L,
                        10L,
                        11L));

        assertEquals(2, diagnostics.activeProcesses().node());
        assertEquals(1, diagnostics.activeProcesses().cli());
        assertEquals(2, diagnostics.activeProcesses().mcp());
        assertEquals(5, diagnostics.activeProcesses().all());
        assertEquals(3, diagnostics.persistentRegistry().registrySize());
        assertEquals(4L, diagnostics.persistentRegistry().evictionCount());
        assertEquals(7L, diagnostics.gateway().processGeneration());
        assertEquals(11L, diagnostics.gateway().directDegradedCount());
    }

    @Test
    public void captureUsesDefensiveEmptyDiagnosticsForNullInputs() {
        RuntimeResourceDiagnostics diagnostics = RuntimeResourceDiagnostics.capture(null, null, null);

        assertEquals(0, diagnostics.activeProcesses().all());
        assertEquals(0, diagnostics.persistentRegistry().registrySize());
        assertEquals(McpGatewayLifecycleState.STOPPED.value(), diagnostics.gateway().lifecycleState());
        assertNull(diagnostics.gateway().lastFailure());
        assertFalse(diagnostics.gateway().refreshInFlight());
        assertEquals(-1L, diagnostics.gateway().lastColdStartDurationMs());
        assertEquals(-1L, diagnostics.gateway().lastCatalogReadyDurationMs());
    }

    @Test
    public void captureClampsNegativeGatewayActiveProcessCount() {
        McpGatewayService.Diagnostics gateway = new McpGatewayService.Diagnostics(
                McpGatewayLifecycleState.FAILED.value(),
                null,
                0L,
                -3,
                false,
                0L,
                -1L,
                -1L,
                0L);

        RuntimeResourceDiagnostics diagnostics = RuntimeResourceDiagnostics.capture(
                Collections.emptyList(), null, gateway);

        assertEquals(0, diagnostics.activeProcesses().mcp());
        assertEquals(0, diagnostics.activeProcesses().all());
    }

    private static NodeProcessInfo process(NodeProcessInfo.Kind kind, boolean alive) {
        return NodeProcessInfo.builder()
                .kind(kind)
                .pid(kind.ordinal() + 1L)
                .alive(alive)
                .build();
    }
}
