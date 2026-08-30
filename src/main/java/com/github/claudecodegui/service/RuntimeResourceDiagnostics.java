package com.github.claudecodegui.service;

import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.mcp.McpGatewayLifecycleState;
import com.github.claudecodegui.mcp.McpGatewayService;

import java.util.Collections;
import java.util.List;

/**
 * Immutable backend-owned resource diagnostics included in the Node process panel snapshot.
 *
 * <p>The webview renders these already-aggregated values and does not derive process totals or
 * lifecycle conclusions itself.</p>
 */
public record RuntimeResourceDiagnostics(
        ActiveProcessCounts activeProcesses,
        CliPersistentProcessRegistry.Diagnostics persistentRegistry,
        McpGatewayService.Diagnostics gateway
) {

    /** Active child process counts split by lifecycle owner. */
    public record ActiveProcessCounts(int node, int cli, int mcp, int all) {
    }

    public static RuntimeResourceDiagnostics capture(
            List<NodeProcessInfo> processes,
            CliPersistentProcessRegistry.Diagnostics persistentRegistry,
            McpGatewayService.Diagnostics gateway
    ) {
        List<NodeProcessInfo> safeProcesses = processes == null ? Collections.emptyList() : processes;
        int activeNodeCount = 0;
        int activeCliCount = 0;
        for (NodeProcessInfo process : safeProcesses) {
            if (process == null || !process.isAlive()) {
                continue;
            }
            if (process.getKind() == NodeProcessInfo.Kind.CLI_SESSION) {
                activeCliCount++;
            } else {
                activeNodeCount++;
            }
        }
        int activeMcpCount = gateway == null ? 0 : Math.max(0, gateway.activeProcessCount());
        ActiveProcessCounts activeProcesses = new ActiveProcessCounts(
                activeNodeCount,
                activeCliCount,
                activeMcpCount,
                activeNodeCount + activeCliCount + activeMcpCount);
        return new RuntimeResourceDiagnostics(
                activeProcesses,
                persistentRegistry == null ? emptyPersistentRegistry() : persistentRegistry,
                gateway == null ? emptyGateway() : gateway);
    }

    public static RuntimeResourceDiagnostics empty() {
        return capture(Collections.emptyList(), emptyPersistentRegistry(), emptyGateway());
    }

    private static CliPersistentProcessRegistry.Diagnostics emptyPersistentRegistry() {
        return new CliPersistentProcessRegistry.Diagnostics(0, 0, 0, 0L, 0L);
    }

    private static McpGatewayService.Diagnostics emptyGateway() {
        return new McpGatewayService.Diagnostics(
                McpGatewayLifecycleState.STOPPED.value(),
                null,
                0L,
                0,
                false,
                0L,
                -1L,
                -1L,
                0L);
    }
}
