package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.mcp.McpVerifyCircuitBreaker;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Queries MCP server status and tool metadata through the Node bridge.
 * Status results are cached for 30 seconds to avoid spawning a Node.js
 * process on every frontend poll.
 */
class ClaudeMcpQueryService {

    private static final long STATUS_CACHE_TTL_MS = 30_000L; // 30 seconds
    private static final String CHANNEL_SCRIPT = "channel-manager.js";
    private static final String MCP_STATUS_CHANNEL_ID = "__mcp_status__";
    private static final String MCP_TOOLS_CHANNEL_ID = "__mcp_tools__";

    private final Logger log;
    private final Gson gson;
    private final NodeDetector nodeDetector;
    private final Supplier<File> sdkDirSupplier;
    private final ProcessManager processManager;
    private final EnvironmentConfigurator envConfigurator;
    private final ClaudeJsonOutputExtractor outputExtractor;

    // Cache for MCP server status queries (keyed by cwd)
    private volatile String cachedStatusCwd;
    private volatile List<JsonObject> cachedStatusResult;
    private volatile long cachedStatusTimestamp = 0;

    // in-flight 查询合并表(key=cwd):同 cwd 的并发请求共享同一个 future,后端只 spawn 一次。
    // 防线背景:前端 MCP_GATEWAY_STATUS 订阅器曾与 status 查询形成乒乓风暴(120ms/轮,1 小时
    // 496 次 spawn,系统进程耗尽),仅靠 30s 成功缓存挡不住"0 成功"的风暴(失败不写缓存)。
    private final ConcurrentHashMap<String, CompletableFuture<List<JsonObject>>> inFlightStatusQueries =
            new ConcurrentHashMap<>();
    // 失败负缓存冷却:一次查询产出空结果后,冷却期内直接返回空,不再 spawn node。
    // 空结果含"查询失败"与"项目确实无 MCP server"两种,10s 冷却对后者无害(配置不会 10s 内变)。
    private static final long EMPTY_RESULT_COOLDOWN_MS = 10_000L;
    private volatile long lastEmptyResultTimestamp = 0;

    // 单 server 验证熔断器:连续失败 ≥3 的 server 跳过验证(名单经 stdin 传给 ai-bridge,
    // 不再冷启动 spawn),5min 后放行一次试探。失败不影响成功 server 的正常验证。
    private final McpVerifyCircuitBreaker circuitBreaker = new McpVerifyCircuitBreaker();

    // ── tools 查询防线(status 有四层,tools 此前一层没有:每次展开 server 卡都 spawn 一个
    //    node 进程占 65s latch,坏 server 每次点击固定浪费一个)──
    /** in-flight 合并(key=serverId):同 server 并发 tools 请求共享同一个 future。 */
    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> inFlightToolsQueries =
            new ConcurrentHashMap<>();
    /** 失败负缓存:key=serverId,值=失败时刻;冷却期内直接返回缓存失败,不 spawn。 */
    private final ConcurrentHashMap<String, Long> toolsFailureAt = new ConcurrentHashMap<>();
    private static final long TOOLS_FAILURE_COOLDOWN_MS = 10_000L;

    ClaudeMcpQueryService(
            Logger log,
            Gson gson,
            NodeDetector nodeDetector,
            Supplier<File> sdkDirSupplier,
            ProcessManager processManager,
            EnvironmentConfigurator envConfigurator,
            ClaudeJsonOutputExtractor outputExtractor
    ) {
        this.log = log;
        this.gson = gson;
        this.nodeDetector = nodeDetector;
        this.sdkDirSupplier = sdkDirSupplier;
        this.processManager = processManager;
        this.envConfigurator = envConfigurator;
        this.outputExtractor = outputExtractor;
    }

    // getMcpServerStatus/getMcpServerTools 均以显式 executor 落共享后台池:executeMarkerQuery 内
    // spawn node 子进程并 latch 等待最长 65s,无 executor 会把 ForkJoinPool.commonPool worker
    // (大小≈CPU核数-1)占住同一时长,并发几个查询就耗尽并行度。
    CompletableFuture<List<JsonObject>> getMcpServerStatus(String cwd) {
        // Return cached result if fresh
        List<JsonObject> cached = cachedStatusResult;
        String cachedCwd = cachedStatusCwd;
        if (cached != null && cwd != null && cwd.equals(cachedCwd)
                && System.currentTimeMillis() - cachedStatusTimestamp < STATUS_CACHE_TTL_MS) {
            log.debug("[McpStatus] Returning cached status (" + cached.size() + " servers)");
            return CompletableFuture.completedFuture(cached);
        }

        // 失败负缓存:最近一次空结果仍在冷却期内 → 直接返回空,不 spawn node
        if (System.currentTimeMillis() - lastEmptyResultTimestamp < EMPTY_RESULT_COOLDOWN_MS) {
            log.debug("[McpStatus] Empty-result cooldown active, returning empty without spawn");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // in-flight 合并:同 cwd 并发请求共享同一个 future,风暴时后端只 spawn 一次
        String statusKey = cwd != null ? cwd : "";
        CompletableFuture<List<JsonObject>> newFuture = new CompletableFuture<>();
        CompletableFuture<List<JsonObject>> inFlight = inFlightStatusQueries.putIfAbsent(statusKey, newFuture);
        if (inFlight != null) {
            log.info("[McpStatus] Joining in-flight query, cwd=" + cwd);
            return inFlight;
        }

        CompletableFuture<List<JsonObject>> query = CompletableFuture.supplyAsync(() -> {
            log.info("[McpStatus] Starting getMcpServerStatus, cwd=" + cwd);

            JsonObject stdinInput = new JsonObject();
            stdinInput.addProperty("cwd", cwd != null ? cwd : "");
            // 熔断名单:连续失败 ≥3 的 server 本次跳过验证(ai-bridge 直接合成失败结果)
            java.util.Set<String> skip = circuitBreaker.serversToSkip(System.currentTimeMillis());
            if (!skip.isEmpty()) {
                JsonArray skipArray = new JsonArray();
                skip.forEach(skipArray::add);
                stdinInput.add("skipVerify", skipArray);
                log.info("[McpStatus] Circuit open, skipping verification: " + skip);
            }

            MarkerResult result = executeMarkerQuery(
                    MCP_STATUS_CHANNEL_ID,
                    "getMcpServerStatus",
                    stdinInput,
                    "[MCP_SERVER_STATUS]",
                    "[McpStatus]"
            );

            if (result.markerJson != null && !result.markerJson.isEmpty()) {
                try {
                    JsonArray serversArray = gson.fromJson(result.markerJson, JsonArray.class);
                    List<JsonObject> servers = new ArrayList<>();
                    for (var server : serversArray) {
                        servers.add(server.getAsJsonObject());
                    }
                    log.info("[McpStatus] Successfully parsed " + servers.size() + " MCP servers in " + result.elapsedMs + "ms");
                    cachedStatusCwd = cwd;
                    cachedStatusResult = servers;
                    cachedStatusTimestamp = System.currentTimeMillis();
                    return servers;
                } catch (Exception e) {
                    log.warn("[McpStatus] Failed to parse MCP status JSON: " + e.getMessage());
                }
            }

            log.info("[McpStatus] Marker not found, trying fallback (elapsed=" + result.elapsedMs + "ms)");
            List<JsonObject> servers = new ArrayList<>();

            for (String line : result.fullOutput.split("\n")) {
                if (line.startsWith("[MCP_SERVER_STATUS]")) {
                    String fallbackJson = line.substring("[MCP_SERVER_STATUS]".length()).trim();
                    try {
                        JsonArray serversArray = gson.fromJson(fallbackJson, JsonArray.class);
                        for (var server : serversArray) {
                            servers.add(server.getAsJsonObject());
                        }
                        log.info("[McpStatus] Fallback marker parse: " + servers.size() + " servers");
                        cachedStatusCwd = cwd;
                        cachedStatusResult = servers;
                        cachedStatusTimestamp = System.currentTimeMillis();
                        return servers;
                    } catch (Exception e) {
                        log.debug("[McpStatus] Fallback marker parse failed: " + e.getMessage());
                    }
                }
            }

            String jsonStr = outputExtractor.extractLastJsonLine(result.fullOutput);
            if (jsonStr != null) {
                try {
                    JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
                    if (jsonResult.has("success") && jsonResult.get("success").getAsBoolean() && jsonResult.has("servers")) {
                        JsonArray serversArray = jsonResult.getAsJsonArray("servers");
                        for (var server : serversArray) {
                            servers.add(server.getAsJsonObject());
                        }
                    }
                } catch (Exception e) {
                    log.debug("[McpStatus] Fallback JSON parse failed: " + e.getMessage());
                }
            }

            if (!servers.isEmpty()) {
                cachedStatusCwd = cwd;
                cachedStatusResult = servers;
                cachedStatusTimestamp = System.currentTimeMillis();
            }
            return servers;
        }, AppExecutorUtil.getAppExecutorService());

        // 完成回调:清在途条目 + 熔断计数回灌 + 空结果置负缓存 + 把结果回填给所有 join 的请求
        query.whenComplete((result, error) -> {
            inFlightStatusQueries.remove(statusKey, newFuture);
            if (error == null) {
                circuitBreaker.onResult(result);
            }
            if (error != null || result == null || result.isEmpty()) {
                lastEmptyResultTimestamp = System.currentTimeMillis();
            }
            if (error != null) {
                newFuture.completeExceptionally(error);
            } else {
                newFuture.complete(result);
            }
        });
        return newFuture;
    }

    CompletableFuture<JsonObject> getMcpServerTools(String serverId, String cwd) {
        String toolsKey = serverId != null ? serverId : "";

        // 失败负缓存:冷却期内直接返回缓存失败,不再 spawn
        Long failedAt = toolsFailureAt.get(toolsKey);
        if (failedAt != null && System.currentTimeMillis() - failedAt < TOOLS_FAILURE_COOLDOWN_MS) {
            log.debug("[McpTools] Failure cooldown active for " + serverId + ", skipping spawn");
            JsonObject cooldownResult = new JsonObject();
            cooldownResult.addProperty("serverId", serverId);
            cooldownResult.addProperty("error", "Tools query failed recently, cooldown active");
            return CompletableFuture.completedFuture(cooldownResult);
        }

        // in-flight 合并:同 server 并发请求共享同一个 future
        CompletableFuture<JsonObject> newFuture = new CompletableFuture<>();
        CompletableFuture<JsonObject> inFlight = inFlightToolsQueries.putIfAbsent(toolsKey, newFuture);
        if (inFlight != null) {
            log.info("[McpTools] Joining in-flight tools query, serverId=" + serverId);
            return inFlight;
        }

        CompletableFuture<JsonObject> query = CompletableFuture.supplyAsync(() -> {
            log.info("[McpTools] Starting getMcpServerTools, serverId=" + serverId);

            JsonObject stdinInput = new JsonObject();
            stdinInput.addProperty("serverId", serverId != null ? serverId : "");
            if (cwd != null && !cwd.isEmpty()) {
                stdinInput.addProperty("cwd", cwd);
            }

            MarkerResult result = executeMarkerQuery(
                    MCP_TOOLS_CHANNEL_ID,
                    "getMcpServerTools",
                    stdinInput,
                    "[MCP_SERVER_TOOLS]",
                    "[McpTools]"
            );

            if (result.markerJson != null && !result.markerJson.isEmpty()) {
                try {
                    JsonObject parsed = gson.fromJson(result.markerJson, JsonObject.class);
                    log.info("[McpTools] Successfully got tools for server " + serverId + " in " + result.elapsedMs + "ms");
                    return parsed;
                } catch (Exception e) {
                    log.warn("[McpTools] Failed to parse MCP tools JSON: " + e.getMessage());
                }
            }

            String jsonStr = outputExtractor.extractLastJsonLine(result.fullOutput);
            if (jsonStr != null) {
                try {
                    JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
                    if (jsonResult.has("success") && jsonResult.get("success").getAsBoolean()) {
                        return jsonResult;
                    }
                } catch (Exception e) {
                    log.debug("[McpTools] Fallback JSON parse failed: " + e.getMessage());
                }
            }

            JsonObject errorResult = new JsonObject();
            errorResult.addProperty("serverId", serverId);
            errorResult.addProperty("error", "Failed to get tools list");
            return errorResult;
        }, AppExecutorUtil.getAppExecutorService());

        // 完成回调:清在途条目 + 失败置负缓存 + 回填给所有 join 的请求
        query.whenComplete((result, error) -> {
            inFlightToolsQueries.remove(toolsKey, newFuture);
            if (error != null || result == null || !result.has("tools")) {
                toolsFailureAt.put(toolsKey, System.currentTimeMillis());
            }
            if (error != null) {
                newFuture.completeExceptionally(error);
            } else {
                newFuture.complete(result);
            }
        });
        return newFuture;
    }

    // ============================================================================
    // Shared process execution template
    // ============================================================================

    /**
     * Execute a Node bridge command and wait for a tagged marker line in stdout.
     * Handles process lifecycle, stdin writing, marker detection via CountDownLatch, and cleanup.
     */
    private MarkerResult executeMarkerQuery(
            String channelIdPrefix,
            String commandName,
            JsonObject stdinInput,
            String markerPrefix,
            String logPrefix
    ) {
        // 每次调用生成唯一 channel ID:并发查询若共用常量 ID,registerProcess 会互相覆盖账本条目,
        // 被覆盖的进程 unregister 时条件移除失败,IDE 退出 cleanupAllProcesses 杀不到 → 孤儿进程。
        String channelId = ProcessManager.newChannelId(channelIdPrefix);
        Process process = null;
        Thread readerThread = null;
        long startTime = System.currentTimeMillis();

        try {
            String node = nodeDetector.findNodeExecutable();
            File bridgeDir = sdkDirSupplier.get();
            if (bridgeDir == null || !bridgeDir.exists()) {
                log.warn(logPrefix + " Bridge directory not ready or invalid");
                log.warn(logPrefix + " This is usually caused by missing node_modules in development environment.");
                log.warn(logPrefix + " Please run: cd ai-bridge && npm install");
                return new MarkerResult(null, "", System.currentTimeMillis() - startTime);
            }

            String scriptPath = new File(bridgeDir, CHANNEL_SCRIPT).getAbsolutePath();
            List<String> command = NodeDetector.buildNodeScriptCommand(node, scriptPath);
            command.add(CommonConstants.PROVIDER_CLAUDE);
            command.add(commandName);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(bridgeDir);
            pb.redirectErrorStream(true);
            envConfigurator.updateProcessEnvironment(pb, node);
            pb.environment().put("CLAUDE_USE_STDIN", "true");

            process = pb.start();
            processManager.registerProcess(channelId, process);
            final Process finalProcess = process;

            ClaudeBridgeUtils.writeStdin(gson.toJson(stdinInput), process, log, logPrefix);

            CountDownLatch markerLatch = new CountDownLatch(1);
            AtomicReference<String> markerJson = new AtomicReference<>(null);
            final StringBuilder output = new StringBuilder();

            readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        if (line.startsWith(markerPrefix)) {
                            markerJson.set(line.substring(markerPrefix.length()).trim());
                            markerLatch.countDown();
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.debug(logPrefix + " Reader thread exception: " + e.getMessage());
                } finally {
                    markerLatch.countDown();
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            markerLatch.await(65, TimeUnit.SECONDS);

            long elapsed = System.currentTimeMillis() - startTime;
            if (process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
            }
            closeProcessStreams(process);
            joinReader(readerThread);

            return new MarkerResult(markerJson.get(), output.toString().trim(), elapsed);
        } catch (Exception e) {
            log.error(logPrefix + " Exception: " + e.getMessage());
            return new MarkerResult(null, "", System.currentTimeMillis() - startTime);
        } finally {
            if (process != null) {
                closeProcessStreams(process);
                try {
                    if (process.isAlive()) {
                        PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
                    }
                    joinReader(readerThread);
                } finally {
                    processManager.unregisterProcess(channelId, process);
                }
            }
        }
    }

    private static void closeProcessStreams(Process process) {
        try {
            process.getOutputStream().close();
        } catch (Exception ignored) {
        }
        try {
            process.getInputStream().close();
        } catch (Exception ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (Exception ignored) {
        }
    }

    private static void joinReader(Thread readerThread) {
        if (readerThread == null || readerThread == Thread.currentThread()) {
            return;
        }
        try {
            readerThread.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class MarkerResult {
        final String markerJson;
        final String fullOutput;
        final long elapsedMs;

        MarkerResult(String markerJson, String fullOutput, long elapsedMs) {
            this.markerJson = markerJson;
            this.fullOutput = fullOutput;
            this.elapsedMs = elapsedMs;
        }
    }
}
