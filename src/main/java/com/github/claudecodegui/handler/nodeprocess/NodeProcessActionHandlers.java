package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.payload.NodeProcessActiveProcessesPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessDiagnosticsPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessGatewayPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessPendingInteractionsPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessInfoPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessPersistentRegistryPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessSnapshotPayloadField;
import com.github.claudecodegui.protocol.payload.NodeProcessTotalsPayloadField;
import com.github.claudecodegui.service.NodeProcessInfo;
import com.github.claudecodegui.service.NodeProcessRegistry;
import com.github.claudecodegui.service.ResourceDiagnosticsService;
import com.github.claudecodegui.service.RuntimeResourceDiagnostics;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Container for Node process management action handlers (B2 迁移).
 * Routes frontend events through {@link NodeProcessRegistry}; all I/O runs on the
 * IDE shared executor so the CEF IO thread is never blocked.
 */
public class NodeProcessActionHandlers {

    private static final Logger LOG = Logger.getInstance(NodeProcessActionHandlers.class);

    /**
     * Delay between dispatching a kill command and refreshing the snapshot.
     * Gives the OS a moment to reap the terminated process so the next snapshot
     * reflects reality. Tuned via scheduled executor — no thread blocked.
     */
    private static final long KILL_REFRESH_DELAY_MS = 200L;

    private final HandlerContext context;
    private final Gson gson = GsonHolder.GSON;

    public NodeProcessActionHandlers(HandlerContext context) {
        this.context = context;
    }

    // ============================================================================
    // Operations
    // ============================================================================

    public void handleGetNodeProcesses() {
        runAsync(() -> {
            try {
                NodeProcessRegistry registry = NodeProcessRegistry.getInstance(context.getProject());
                List<NodeProcessInfo> processes = registry.snapshot();
                RuntimeResourceDiagnostics diagnostics = ResourceDiagnosticsService
                        .getInstance(context.getProject())
                        .snapshot(processes);
                String json = buildProcessListJson(processes, diagnostics);
                pushUpdate(json);
            } catch (Exception e) {
                LOG.warn("[NodeProcessHandler] get_node_processes failed: " + e.getMessage(), e);
                // Push empty list so the UI doesn't hang forever
                pushUpdate(buildProcessListJson(
                        Collections.emptyList(), RuntimeResourceDiagnostics.empty()));
            }
        });
    }

    public void handleKillNodeProcess(String rawContent) {
        runAsync(() -> {
            long pid = -1;
            String reportedId = null;
            try {
                JsonObject payload = gson.fromJson(rawContent, JsonObject.class);
                if (payload != null) {
                    if (payload.has("pid") && !payload.get("pid").isJsonNull()) {
                        pid = payload.get("pid").getAsLong();
                    }
                    if (payload.has("id") && !payload.get("id").isJsonNull()) {
                        reportedId = payload.get("id").getAsString();
                    }
                }
            } catch (Exception e) {
                LOG.warn("[NodeProcessHandler] kill_node_process bad payload: " + e.getMessage());
            }

            boolean success = false;
            String error = null;
            if (pid > 0) {
                try {
                    NodeProcessRegistry registry = NodeProcessRegistry.getInstance(context.getProject());
                    // CLI_SESSION 保护预检:透传 cli_session_protected 错误码供前端渲染保护提示
                    String protectedReason = registry.checkKillProtected(pid);
                    if (protectedReason != null) {
                        error = protectedReason;
                    } else {
                        success = registry.killByPid(pid);
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
            } else {
                error = "Invalid or missing PID";
            }

            // Report kill result to frontend
            JsonObject result = new JsonObject();
            result.addProperty("pid", pid);
            if (reportedId != null) {
                result.addProperty("id", reportedId);
            }
            result.addProperty("success", success);
            if (error != null) {
                result.addProperty("error", error);
            }
            pushKillResult(gson.toJson(result));

            // Refresh the list so the UI immediately reflects the kill. Use a
            // scheduled executor instead of Thread.sleep — the AppExecutor pool
            // is shared with the rest of the IDE and we must not block its workers.
            scheduleRefresh(KILL_REFRESH_DELAY_MS);
        });
    }

    public void handleKillAllOrphans() {
        runAsync(() -> {
            int killed = 0;
            String error = null;
            try {
                NodeProcessRegistry registry = NodeProcessRegistry.getInstance(context.getProject());
                killed = registry.killAllOrphans();
            } catch (Exception e) {
                error = e.getMessage();
                LOG.warn("[NodeProcessHandler] kill_all_orphans failed: " + e.getMessage());
            }

            JsonObject result = new JsonObject();
            result.addProperty("killed", killed);
            if (error != null) {
                result.addProperty("error", error);
            }
            pushKillResult(gson.toJson(result));

            scheduleRefresh(KILL_REFRESH_DELAY_MS);
        });
    }

    /**
     * Schedules a snapshot refresh after the given delay without blocking
     * any worker thread. Uses the IDE's shared scheduled executor.
     */
    private void scheduleRefresh(long delayMs) {
        AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(this::handleGetNodeProcesses, delayMs, TimeUnit.MILLISECONDS);
    }

    // ============================================================================
    // Frontend push helpers
    // ============================================================================

    private void pushUpdate(String json) {
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.NODE_PROCESS_LIST.value(), context.escapeJs(json))
        );
    }

    private void pushKillResult(String json) {
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.NODE_PROCESS_KILL_RESULT.value(), context.escapeJs(json))
        );
    }

    // ============================================================================
    // Serialization
    // ============================================================================

    String buildProcessListJson(
            List<NodeProcessInfo> processes,
            RuntimeResourceDiagnostics diagnostics
    ) {
        long now = System.currentTimeMillis();
        int daemonCount = 0;
        int channelCount = 0;
        int mcpGatewayCount = 0;
        int orphanCount = 0;
        int cliSessionCount = 0;

        JsonArray array = new JsonArray();
        for (NodeProcessInfo info : processes) {
            JsonObject processJson = new JsonObject();
            processJson.addProperty(NodeProcessInfoPayloadField.ID.wireKey(), info.getId());
            processJson.addProperty(NodeProcessInfoPayloadField.KIND.wireKey(), info.getKind().value());
            if (info.getProvider() != null) {
                processJson.addProperty(NodeProcessInfoPayloadField.PROVIDER.wireKey(), info.getProvider());
            }
            processJson.addProperty(NodeProcessInfoPayloadField.PID.wireKey(), info.getPid());
            processJson.addProperty(NodeProcessInfoPayloadField.ALIVE.wireKey(), info.isAlive());
            processJson.addProperty(NodeProcessInfoPayloadField.STARTED_AT.wireKey(), info.getStartedAtMs());
            processJson.addProperty(NodeProcessInfoPayloadField.UPTIME_MS.wireKey(), info.getUptimeMs());
            if (info.getCommand() != null) {
                processJson.addProperty(NodeProcessInfoPayloadField.COMMAND.wireKey(), info.getCommand());
            }
            if (info.getHeapUsedBytes() >= 0) {
                processJson.addProperty(NodeProcessInfoPayloadField.HEAP_USED.wireKey(), info.getHeapUsedBytes());
            }
            processJson.addProperty(
                    NodeProcessInfoPayloadField.ACTIVE_REQUEST_COUNT.wireKey(),
                    info.getActiveRequestCount());
            if (info.getChannelId() != null) {
                processJson.addProperty(NodeProcessInfoPayloadField.CHANNEL_ID.wireKey(), info.getChannelId());
            }
            if (info.getSessionId() != null) {
                processJson.addProperty(NodeProcessInfoPayloadField.SESSION_ID.wireKey(), info.getSessionId());
            }
            if (info.getTabName() != null) {
                processJson.addProperty(NodeProcessInfoPayloadField.TAB_NAME.wireKey(), info.getTabName());
            }
            processJson.addProperty(NodeProcessInfoPayloadField.ORPHAN.wireKey(), info.isOrphan());
            if (info.getProjectLifecycleId() != null) {
                processJson.addProperty(
                        NodeProcessInfoPayloadField.PROJECT_LIFECYCLE_ID.wireKey(), info.getProjectLifecycleId());
            }
            if (info.getRuntimeSessionEpoch() != null) {
                processJson.addProperty(
                        NodeProcessInfoPayloadField.RUNTIME_SESSION_EPOCH.wireKey(), info.getRuntimeSessionEpoch());
            }
            if (info.getResponseTurnEpoch() != null) {
                processJson.addProperty(
                        NodeProcessInfoPayloadField.RESPONSE_TURN_EPOCH.wireKey(), info.getResponseTurnEpoch());
            }
            if (info.getProcessGeneration() != null) {
                processJson.addProperty(
                        NodeProcessInfoPayloadField.PROCESS_GENERATION.wireKey(), info.getProcessGeneration());
            }
            array.add(processJson);

            switch (info.getKind()) {
                case DAEMON:
                    daemonCount++;
                    break;
                case CHANNEL:
                    channelCount++;
                    break;
                case MCP_GATEWAY:
                    mcpGatewayCount++;
                    break;
                case ORPHAN:
                    orphanCount++;
                    break;
                case CLI_SESSION:
                    cliSessionCount++;
                    break;
            }
        }

        JsonObject totals = new JsonObject();
        totals.addProperty(NodeProcessTotalsPayloadField.DAEMON.wireKey(), daemonCount);
        totals.addProperty(NodeProcessTotalsPayloadField.CHANNEL.wireKey(), channelCount);
        totals.addProperty(NodeProcessTotalsPayloadField.MCP_GATEWAY.wireKey(), mcpGatewayCount);
        totals.addProperty(NodeProcessTotalsPayloadField.ORPHAN.wireKey(), orphanCount);
        totals.addProperty(NodeProcessTotalsPayloadField.CLI_SESSION.wireKey(), cliSessionCount);
        totals.addProperty(NodeProcessTotalsPayloadField.ALL.wireKey(), processes.size());

        JsonObject root = new JsonObject();
        root.addProperty(NodeProcessSnapshotPayloadField.SNAPSHOT_AT.wireKey(), now);
        root.add(NodeProcessSnapshotPayloadField.TOTALS.wireKey(), totals);
        root.add(NodeProcessSnapshotPayloadField.PROCESSES.wireKey(), array);
        root.add(
                NodeProcessSnapshotPayloadField.DIAGNOSTICS.wireKey(),
                buildDiagnosticsJson(diagnostics));
        return root.toString();
    }

    private JsonObject buildDiagnosticsJson(RuntimeResourceDiagnostics diagnostics) {
        RuntimeResourceDiagnostics fallback = RuntimeResourceDiagnostics.empty();
        RuntimeResourceDiagnostics safeDiagnostics = diagnostics == null ? fallback : diagnostics;
        RuntimeResourceDiagnostics.ActiveProcessCounts activeProcesses =
                safeDiagnostics.activeProcesses() == null
                        ? fallback.activeProcesses()
                        : safeDiagnostics.activeProcesses();
        CliPersistentProcessRegistry.Diagnostics persistentRegistry =
                safeDiagnostics.persistentRegistry() == null
                        ? fallback.persistentRegistry()
                        : safeDiagnostics.persistentRegistry();
        McpGatewayService.Diagnostics gateway = safeDiagnostics.gateway() == null
                ? fallback.gateway()
                : safeDiagnostics.gateway();
        var pendingInteractions = safeDiagnostics.pendingInteractions() == null
                ? fallback.pendingInteractions()
                : safeDiagnostics.pendingInteractions();

        JsonObject activeProcessesJson = new JsonObject();
        activeProcessesJson.addProperty(
                NodeProcessActiveProcessesPayloadField.NODE.wireKey(), activeProcesses.node());
        activeProcessesJson.addProperty(
                NodeProcessActiveProcessesPayloadField.CLI.wireKey(), activeProcesses.cli());
        activeProcessesJson.addProperty(
                NodeProcessActiveProcessesPayloadField.MCP.wireKey(), activeProcesses.mcp());
        activeProcessesJson.addProperty(
                NodeProcessActiveProcessesPayloadField.ALL.wireKey(), activeProcesses.all());

        JsonObject persistentRegistryJson = new JsonObject();
        persistentRegistryJson.addProperty(
                NodeProcessPersistentRegistryPayloadField.REGISTRY_SIZE.wireKey(),
                persistentRegistry.registrySize());
        persistentRegistryJson.addProperty(
                NodeProcessPersistentRegistryPayloadField.USABLE_PROCESS_COUNT.wireKey(),
                persistentRegistry.usableProcessCount());
        persistentRegistryJson.addProperty(
                NodeProcessPersistentRegistryPayloadField.PENDING_REBUILD_COUNT.wireKey(),
                persistentRegistry.pendingRebuildCount());
        persistentRegistryJson.addProperty(
                NodeProcessPersistentRegistryPayloadField.EVICTION_COUNT.wireKey(),
                persistentRegistry.evictionCount());
        persistentRegistryJson.addProperty(
                NodeProcessPersistentRegistryPayloadField.REBUILD_COOLDOWN_HIT_COUNT.wireKey(),
                persistentRegistry.rebuildCooldownHitCount());

        JsonObject gatewayJson = new JsonObject();
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.LIFECYCLE_STATE.wireKey(), gateway.lifecycleState());
        if (gateway.lastFailure() == null) {
            gatewayJson.add(NodeProcessGatewayPayloadField.LAST_FAILURE.wireKey(), JsonNull.INSTANCE);
        } else {
            gatewayJson.addProperty(
                    NodeProcessGatewayPayloadField.LAST_FAILURE.wireKey(), gateway.lastFailure());
        }
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.PROCESS_GENERATION.wireKey(), gateway.processGeneration());
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.ACTIVE_PROCESS_COUNT.wireKey(), gateway.activeProcessCount());
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.REFRESH_IN_FLIGHT.wireKey(), gateway.refreshInFlight());
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.RESTART_COUNT.wireKey(), gateway.restartCount());
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.LAST_COLD_START_DURATION_MS.wireKey(),
                gateway.lastColdStartDurationMs());
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.LAST_CATALOG_READY_DURATION_MS.wireKey(),
                gateway.lastCatalogReadyDurationMs());
        gatewayJson.addProperty(
                NodeProcessGatewayPayloadField.DIRECT_DEGRADED_COUNT.wireKey(),
                gateway.directDegradedCount());

        JsonObject pendingInteractionsJson = new JsonObject();
        pendingInteractionsJson.addProperty(
                NodeProcessPendingInteractionsPayloadField.PENDING_PERMISSION_REQUESTS.wireKey(),
                pendingInteractions.pendingPermissionRequests());
        pendingInteractionsJson.addProperty(
                NodeProcessPendingInteractionsPayloadField.PENDING_TOOL_CALLS.wireKey(),
                pendingInteractions.pendingToolCalls());
        pendingInteractionsJson.addProperty(
                NodeProcessPendingInteractionsPayloadField.ORPHAN_TOOL_RESULTS.wireKey(),
                pendingInteractions.orphanToolResults());

        JsonObject diagnosticsJson = new JsonObject();
        diagnosticsJson.add(
                NodeProcessDiagnosticsPayloadField.ACTIVE_PROCESSES.wireKey(), activeProcessesJson);
        diagnosticsJson.add(
                NodeProcessDiagnosticsPayloadField.PERSISTENT_REGISTRY.wireKey(), persistentRegistryJson);
        diagnosticsJson.add(NodeProcessDiagnosticsPayloadField.GATEWAY.wireKey(), gatewayJson);
        diagnosticsJson.add(
                NodeProcessDiagnosticsPayloadField.PENDING_INTERACTIONS.wireKey(),
                pendingInteractionsJson);
        return diagnosticsJson;
    }

    private void runAsync(Runnable work) {
        CompletableFuture.runAsync(work, AppExecutorUtil.getAppExecutorService())
                .exceptionally(ex -> {
                    LOG.warn("[NodeProcessHandler] Async work failed: " + ex.getMessage(), ex);
                    return null;
                });
    }
}
