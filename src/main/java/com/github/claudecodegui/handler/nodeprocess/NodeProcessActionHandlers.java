package com.github.claudecodegui.handler.nodeprocess;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.service.NodeProcessInfo;
import com.github.claudecodegui.service.NodeProcessRegistry;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
                String json = buildProcessListJson(processes);
                pushUpdate(json);
            } catch (Exception e) {
                LOG.warn("[NodeProcessHandler] get_node_processes failed: " + e.getMessage(), e);
                // Push empty list so the UI doesn't hang forever
                pushUpdate(buildProcessListJson(Collections.emptyList()));
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
                    // CLI_SESSION 保护预检(§5.2):透传 cli_session_protected 错误码供前端渲染保护提示
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

    private String buildProcessListJson(List<NodeProcessInfo> processes) {
        long now = System.currentTimeMillis();
        int daemonCount = 0;
        int channelCount = 0;
        int orphanCount = 0;
        int cliSessionCount = 0;

        JsonArray array = new JsonArray();
        for (NodeProcessInfo info : processes) {
            JsonObject o = new JsonObject();
            o.addProperty("id", info.getId());
            o.addProperty("kind", info.getKind().name());
            if (info.getProvider() != null) {
                o.addProperty("provider", info.getProvider());
            }
            o.addProperty("pid", info.getPid());
            o.addProperty("alive", info.isAlive());
            o.addProperty("startedAt", info.getStartedAtMs());
            o.addProperty("uptimeMs", info.getUptimeMs());
            if (info.getCommand() != null) {
                o.addProperty("command", info.getCommand());
            }
            if (info.getHeapUsedBytes() >= 0) {
                o.addProperty("heapUsed", info.getHeapUsedBytes());
            }
            o.addProperty("activeRequestCount", info.getActiveRequestCount());
            if (info.getChannelId() != null) {
                o.addProperty("channelId", info.getChannelId());
            }
            if (info.getSessionId() != null) {
                o.addProperty("sessionId", info.getSessionId());
            }
            if (info.getTabName() != null) {
                o.addProperty("tabName", info.getTabName());
            }
            o.addProperty("orphan", info.isOrphan());
            array.add(o);

            switch (info.getKind()) {
                case DAEMON:
                    daemonCount++;
                    break;
                case CHANNEL:
                    channelCount++;
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
        totals.addProperty("daemon", daemonCount);
        totals.addProperty("channel", channelCount);
        totals.addProperty("orphan", orphanCount);
        totals.addProperty("cliSession", cliSessionCount);
        totals.addProperty("all", processes.size());

        JsonObject root = new JsonObject();
        root.addProperty("snapshotAt", now);
        root.add("totals", totals);
        root.add("processes", array);
        return gson.toJson(root);
    }

    private void runAsync(Runnable work) {
        CompletableFuture.runAsync(work, AppExecutorUtil.getAppExecutorService())
                .exceptionally(ex -> {
                    LOG.warn("[NodeProcessHandler] Async work failed: " + ex.getMessage(), ex);
                    return null;
                });
    }
}
