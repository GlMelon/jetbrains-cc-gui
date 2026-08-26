package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Independent history reading service for OpenCode provider.
 * Extracts OpenCode history reading (getSessionMessages / getSessionList / archiveSession)
 * into an independent service. Uses NodeService for Node.js infrastructure.
 */
public class OpenCodeHistoryService {

    private static final Logger LOG = Logger.getInstance(OpenCodeHistoryService.class);
    private static final int HISTORY_QUERY_TIMEOUT_SECONDS = 30;

    private final NodeService nodeService;
    private final Gson gson;

    public OpenCodeHistoryService() {
        this.nodeService = NodeService.getInstance();
        this.gson = GsonHolder.GSON;
    }

    /**
     * Read persisted OpenCode session history from the local OpenCode database.
     */
    public List<JsonObject> getSessionMessages(String sessionId, String cwd) {
        return getSessionMessagesResult(sessionId, cwd, null, null).messages();
    }

    /**
     * Reads a bounded history prefix while preserving the exact normalized source count.
     */
    public SessionHistoryQueryResult getSessionMessages(
            String sessionId,
            String cwd,
            int maxMessageCount,
            int maxUtf8Bytes
    ) {
        return getSessionMessagesResult(sessionId, cwd, maxMessageCount, maxUtf8Bytes);
    }

    private SessionHistoryQueryResult getSessionMessagesResult(
            String sessionId,
            String cwd,
            Integer maxMessageCount,
            Integer maxUtf8Bytes
    ) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionHistoryQueryResult.empty();
        }
        try {
            File bridgeDir = nodeService.getBridgeDir();
            if (bridgeDir == null) {
                LOG.warn("[OpenCode] Bridge directory not ready, cannot load history");
                return SessionHistoryQueryResult.empty();
            }

            List<String> command = buildGetSessionCommand();
            JsonObject stdin = new JsonObject();
            stdin.addProperty("sessionId", sessionId);
            if (maxMessageCount != null) {
                stdin.addProperty("maxMessageCount", maxMessageCount);
            }
            if (maxUtf8Bytes != null) {
                stdin.addProperty("maxUtf8Bytes", maxUtf8Bytes);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            configureProviderEnv(env);
            String node = nodeService.getNodeDetector().findNodeExecutable();
            nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);

            JsonObject result = runNodeQuery(pb, stdin, "getSession", sessionId);
            if (result == null || !result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result != null && result.has("error") && !result.get("error").isJsonNull()
                        ? result.get("error").getAsString()
                        : "unknown error";
                LOG.warn("[OpenCode] Failed to load session history: " + error);
                return SessionHistoryQueryResult.empty();
            }
            if (!result.has("messages") || !result.get("messages").isJsonArray()) {
                return SessionHistoryQueryResult.empty();
            }

            List<JsonObject> messages = new ArrayList<>();
            JsonArray array = result.getAsJsonArray("messages");
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) {
                    messages.add(array.get(i).getAsJsonObject());
                }
            }
            OpenCodeHistorySanitizer.sanitize(messages);
            int totalMessageCount = result.has("totalMessageCount")
                    ? Math.max(messages.size(), result.get("totalMessageCount").getAsInt())
                    : messages.size();
            return new SessionHistoryQueryResult(messages, totalMessageCount);
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to load session history: " + e.getMessage(), e);
            return SessionHistoryQueryResult.empty();
        }
    }

    /**
     * Enumeration of OpenCode sessions (symmetric to Codex CodexHistoryReader.getSessionsForProjectAsJson).
     */
    public String getSessionList(String projectPath) {
        try {
            File bridgeDir = nodeService.getBridgeDir();
            if (bridgeDir == null) {
                LOG.warn("[OpenCode] Bridge directory not ready, cannot list sessions");
                return "";
            }

            List<String> command = buildListSessionsCommand();
            JsonObject stdin = new JsonObject();
            stdin.addProperty("projectPath", projectPath != null ? projectPath : "");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            configureProviderEnv(env);
            String node = nodeService.getNodeDetector().findNodeExecutable();
            nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);

            JsonObject result = runNodeQuery(pb, stdin, "listSessions", null);
            if (result == null || !result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result != null && result.has("error") && !result.get("error").isJsonNull()
                        ? result.get("error").getAsString()
                        : "unknown error";
                LOG.warn("[OpenCode] Failed to list sessions: " + error);
                return "";
            }
            return result.toString();
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to list sessions: " + e.getMessage(), e);
            return "";
        }
    }

    /**
     * Archive (soft-delete) an OpenCode session.
     */
    public int archiveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        try {
            File bridgeDir = nodeService.getBridgeDir();
            if (bridgeDir == null) {
                LOG.warn("[OpenCode] Bridge directory not ready, cannot archive session");
                return 0;
            }

            List<String> command = buildArchiveSessionCommand();
            JsonObject stdin = new JsonObject();
            stdin.addProperty("sessionId", sessionId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            configureProviderEnv(env);
            String node = nodeService.getNodeDetector().findNodeExecutable();
            nodeService.getEnvConfigurator().updateProcessEnvironment(pb, node);

            JsonObject result = runNodeQuery(pb, stdin, "archiveSession", sessionId);
            if (result == null || !result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result != null && result.has("error") && !result.get("error").isJsonNull()
                        ? result.get("error").getAsString()
                        : "unknown error";
                LOG.warn("[OpenCode] Failed to archive session: " + error);
                return 0;
            }
            return result.has("archived") && result.get("archived").isJsonPrimitive()
                    ? result.get("archived").getAsInt()
                    : 0;
        } catch (Exception e) {
            LOG.warn("[OpenCode] Failed to archive session: " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Spawn channel-manager.js, write JSON to stdin, and return the parsed last JSON object.
     */
    private JsonObject runNodeQuery(ProcessBuilder pb, JsonObject stdin, String logTag, String sessionId) {
        try {
            StringBuilder output = new StringBuilder();
            String channelId = com.github.claudecodegui.bridge.ProcessManager.newChannelId("opencode-history-query");
            com.github.claudecodegui.bridge.ProcessManager processManager = nodeService.getProcessManager();
            Process process = null;
            CompletableFuture<Void> outputFuture = null;
            try {
                process = pb.start();
                processManager.registerProcess(channelId, process);
                Process runningProcess = process;
                // 显式 executor:drain 线程存活至子进程输出 EOF(上限 HISTORY_QUERY_TIMEOUT_SECONDS=30s),
                // 无 executor 会占住 commonPool worker 同一时长。
                outputFuture = CompletableFuture.runAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append('\n');
                        }
                    } catch (Exception e) {
                        LOG.debug("[OpenCode] " + logTag + " output drain failed: " + e.getMessage());
                    }
                }, AppExecutorUtil.getAppExecutorService());

                try (OutputStream stdinStream = process.getOutputStream()) {
                    stdinStream.write(gson.toJson(stdin).getBytes(StandardCharsets.UTF_8));
                    stdinStream.flush();
                }

                if (!process.waitFor(HISTORY_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                    LOG.warn("[OpenCode] " + logTag + " timed out" + (sessionId != null ? " for session: " + sessionId : ""));
                    return null;
                }
                waitForOutputDrain(outputFuture);
            } finally {
                if (outputFuture != null && !outputFuture.isDone()) {
                    outputFuture.cancel(true);
                }
                if (process != null) {
                    processManager.unregisterProcess(channelId, process);
                }
            }
            return extractLastJsonObject(output.toString());
        } catch (java.io.IOException e) {
            LOG.warn("[OpenCode] " + logTag + " I/O error: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("[OpenCode] " + logTag + " interrupted");
            return null;
        }
    }

    private void waitForOutputDrain(CompletableFuture<Void> outputFuture) {
        if (outputFuture == null) {
            return;
        }
        try {
            outputFuture.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.debug("[OpenCode] output drain did not finish cleanly: " + e.getMessage());
        }
    }

    private JsonObject extractLastJsonObject(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                try {
                    return gson.fromJson(line, JsonObject.class);
                } catch (Exception ignored) {
                    // Try earlier lines.
                }
            }
        }
        return null;
    }

    private List<String> buildGetSessionCommand() {
        return buildCommand("getSession");
    }

    private List<String> buildListSessionsCommand() {
        return buildCommand("listSessions");
    }

    private List<String> buildArchiveSessionCommand() {
        return buildCommand("archiveSession");
    }

    private List<String> buildCommand(String action) {
        List<String> cmd = new ArrayList<>();
        cmd.add(nodeService.getNodeExecutable());
        cmd.add("channel-manager.js");
        cmd.add(CommonConstants.PROVIDER_OPENCODE);
        cmd.add(action);
        return cmd;
    }

    private void configureProviderEnv(Map<String, String> env) {
        env.put(CliConstants.ENV_OPENCODE_USE_STDIN, "true");
    }

    /**
     * Result of a bounded history query.
     */
    public record SessionHistoryQueryResult(List<JsonObject> messages, int totalMessageCount) {
        public SessionHistoryQueryResult {
            messages = messages == null ? List.of() : List.copyOf(messages);
            if (totalMessageCount < messages.size()) {
                throw new IllegalArgumentException("totalMessageCount cannot be smaller than messages");
            }
        }

        static SessionHistoryQueryResult empty() {
            return new SessionHistoryQueryResult(List.of(), 0);
        }
    }
}
