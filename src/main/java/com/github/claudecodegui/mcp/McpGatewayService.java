package com.github.claudecodegui.mcp;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.service.lifecycle.LifecycleEventType;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.service.lifecycle.LifecycleProcessKind;
import com.github.claudecodegui.startup.BridgePreloader;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project-scoped facade for the CLI MCP Gateway.
 */
@Service(Service.Level.PROJECT)
public final class McpGatewayService implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpGatewayService.class);

    /** Immutable project-scoped diagnostics snapshot for support tooling. */
    public record Diagnostics(
            String lifecycleState,
            String lastFailure,
            long processGeneration,
            int activeProcessCount,
            boolean refreshInFlight,
            long restartCount,
            long lastColdStartDurationMs,
            long lastCatalogReadyDurationMs,
            long directDegradedCount
    ) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * 复用判定窗口:gateway 进程活着但 /status 可能尚未 ready(预热线程正在 cold-start 的竞态)
     * 时,给后续调用方足够时间等它 ready 再复用。Opt3 由 10s 放宽到 60s(与 SNAPSHOT_TIMEOUT 同量级):
     * 防预热线程 cold-start 期被 {@code stopExistingProcess} 误杀活进程——gateway 启动握手 +
     * MCP server 探测 + config 写入 cold path 总耗时可达 10s+,旧 10s 窗口在慢机/多 MCP server
     * 场景把活进程误判为 stale 重建,与 onExit 自愈叠加放大重启。{@code processHandle.isAlive()}
     * 短路保证进程已死时立即重建、不会死等 60s(死进程 waitUntilReady 立即返 false)。
     */
    private static final Duration REUSE_PROBE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration COLD_START_TIMEOUT = Duration.ofSeconds(10);
    /** 首条 CLI turn 等待 Gateway 的预算；超时后立即走 provider 原生 MCP 配置。 */
    private static final Duration SEND_READY_TIMEOUT = Duration.ofSeconds(2);

    private final Project project;
    private final Object lock = new Object();
    private final Object lifecycleLock = new Object();
    private final McpGatewayConfigCollector collector;
    private final McpGatewayConfigWriter configWriter;
    private final Path gatewayDir;
    private final Path stateFile;
    private final String token;
    private final LifecycleObservabilityService lifecycleService;

    private volatile McpGatewayProcessHandle processHandle;
    /** 启动已开始但尚未提交到 processHandle 时也必须可被 stop/dispose 终止。 */
    private volatile McpGatewayProcessHandle startingProcessHandle;
    private long startingProcessGeneration;
    private volatile McpGatewayBridgeClient bridgeClient;
    private McpGatewayConfigSnapshot currentSnapshot;
    private long currentRevision;
    private long processGeneration;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private volatile Future<?> selfHealFuture;
    /** 同一项目的 Gateway 启动 + catalog 刷新只允许一个后台 flight。 */
    private volatile CompletableFuture<Void> refreshFlight;
    /** CompletableFuture.cancel 不保证中断 supplier，保留底层任务句柄显式取消。 */
    private volatile Future<?> refreshTask;
    private String refreshProjectPath;
    private boolean refreshPending;
    private String pendingRefreshProjectPath;
    /** 使 stop/reload/dispose 之后尚未结束的后台操作失效。 */
    private long operationGeneration;
    private volatile McpGatewayLifecycleState lifecycleState = McpGatewayLifecycleState.STOPPED;
    private volatile String lastFailure;
    /**
     * 最近一次 ensureStarted 的 projectPath,供 onExit 自愈回调重建时复用。
     * 重建时复用——gateway 崩溃后无调用上下文,需记住上次用的 path 才能 ensureStarted 自愈。
     */
    private volatile String lastKnownProjectPath;
    /** 最近 N 次 gateway 进程意外退出时间戳(epoch ms),供 {@link McpGatewayProcessHandle#isRestartStorm} 判风暴。 */
    private final Deque<Long> exitTimestamps = new ArrayDeque<>();
    /** Successful process starts after the initial generation. */
    private long restartCount;
    /** Latest successful process cold-start duration, or -1 before the first success. */
    private long lastColdStartDurationMs = -1L;
    /** Latest successful catalog collection/publication duration, or -1 before the first success. */
    private long lastCatalogReadyDurationMs = -1L;
    /** Accepted direct-config degradation events during this project lifecycle. */
    private long directDegradedCount;

    public McpGatewayService(@NotNull Project project) {
        this.project = project;
        this.lifecycleService = LifecycleObservabilityService.getInstance(project);
        this.collector = new McpGatewayConfigCollector(CodemossSettingsService.getInstance());
        this.gatewayDir = new ConfigPathManager().getConfigDir()
                .resolve(McpGatewayConstants.DIRECTORY_NAME)
                .resolve(safeProjectPart(project.getBasePath()));
        this.stateFile = gatewayDir.resolve(McpGatewayConstants.STATE_FILE_NAME);
        this.configWriter = new McpGatewayConfigWriter(gatewayDir.resolve(McpGatewayConstants.CONFIG_DIRECTORY_NAME));
        this.token = generateToken();
    }

    /**
     * 测试专用构造器:注入 collector 与 bridgeClient,绕过 Project/平台依赖,使 {@link #applySnapshot}
     * 的"提交顺序"逻辑可单测(生产路径用 {@link #McpGatewayService(Project)})。其余字段置空——
     * applySnapshot 只用到 collector/bridgeClient/currentSnapshot/currentRevision/lock。
     */
    McpGatewayService(McpGatewayConfigCollector collector, McpGatewayBridgeClient bridgeClient) {
        this.project = null;
        this.lifecycleService = null;
        this.collector = collector;
        this.configWriter = null;
        this.gatewayDir = null;
        this.stateFile = null;
        this.token = "";
        this.bridgeClient = bridgeClient;
    }

    public static McpGatewayService getInstance(@NotNull Project project) {
        return project.getService(McpGatewayService.class);
    }

    public McpGatewayCliConfig buildCliConfig(ProviderType provider, String tabId, String projectPath) {
        if (!McpGatewayFeatureFlags.isCliEnabled()) {
            return McpGatewayCliConfig.disabled("MCP Gateway CLI feature disabled");
        }
        if (isLifecycleDisposed()) {
            return McpGatewayCliConfig.disabled("MCP Gateway project lifecycle disposed");
        }
        // 无注入机制的 provider 先短路，避免为注定 disabled 的 provider 白付冷启动成本。
        if (configWriter != null && !configWriter.supports(provider)) {
            return McpGatewayCliConfig.disabled(
                    "MCP gateway injection not configured for provider: " + provider.value());
        }

        long startNanos = System.nanoTime();
        long refreshGeneration = currentOperationGeneration();
        CompletableFuture<Void> flight = startOrJoinRefresh(projectPath, false);
        try {
            flight.get(SEND_READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            markDegradedDirect(flight, refreshGeneration, "MCP Gateway is still loading; using direct MCP config");
            LOG.info("[McpGateway] bounded send wait expired; continuing with direct MCP config");
            return McpGatewayCliConfig.disabled("MCP Gateway catalog loading exceeded send budget");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markDegradedDirect(flight, refreshGeneration, "MCP Gateway wait interrupted; using direct MCP config");
            return McpGatewayCliConfig.disabled("MCP Gateway wait interrupted");
        } catch (CancellationException | ExecutionException e) {
            String diagnostic = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage() : "MCP Gateway refresh failed";
            markDegradedDirect(flight, refreshGeneration, diagnostic);
            LOG.warn("[McpGateway] Falling back to direct MCP config: " + diagnostic);
            return McpGatewayCliConfig.disabled(diagnostic);
        }

        try {
            throwIfLifecycleDisposed();
            // Streamable HTTP 直连(2026-09 改造):CLI 以 url 直连 gateway /mcp 端点,
            // 不再经 bridgeDir 下的 stdio 代理脚本;端口现读 state file,不可得即 direct 降级。
            String endpoint = bridgeClient != null ? bridgeClient.mcpEndpointUrl() : null;
            throwIfLifecycleDisposed();
            if (endpoint == null) {
                return directFallback("MCP Gateway endpoint unavailable");
            }
            McpGatewayConfigSnapshot snapshot;
            long revision;
            synchronized (lock) {
                snapshot = currentSnapshot;
                revision = currentRevision;
            }
            List<String> serverIds = realServerIds(snapshot, provider);
            LOG.info("[McpGatewayPerf] buildCliConfig: provider=" + provider.value() + ", tabId=" + tabId
                    + ", totalMs=" + elapsedMillis(startNanos, System.nanoTime())
                    + ", servers=" + serverIds.size() + ", revision=" + revision);
            McpGatewayCliConfig config = configWriter.write(provider, tabId, revision, endpoint, token, serverIds);
            if (config.usable()) {
                setLifecycleState(McpGatewayLifecycleState.READY, null);
            }
            return config;
        } catch (Exception e) {
            return directFallback(e.getMessage());
        }
    }

    private McpGatewayCliConfig directFallback(String diagnostic) {
        String message = diagnostic == null || diagnostic.isBlank()
                ? "MCP Gateway unavailable; using direct MCP config" : diagnostic;
        markDegradedDirect(null, currentOperationGeneration(), message);
        LOG.warn("[McpGateway] Falling back to direct MCP config: " + message);
        return McpGatewayCliConfig.disabled(message);
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000;
    }

    public void refreshConfig(String projectPath) {
        if (!McpGatewayFeatureFlags.isGatewayActive() || isLifecycleDisposed()) {
            return;
        }
        try {
            startOrJoinRefresh(projectPath, true);
        } catch (IllegalStateException e) {
            // dispose may win between the fast-path check and the synchronized admission gate.
            if (!isLifecycleDisposed()) {
                throw e;
            }
        }
    }

    /** 启动或加入项目级 single-flight；慢速启动、catalog 收集和 POST 全部在锁外执行。 */
    private CompletableFuture<Void> startOrJoinRefresh(String projectPath, boolean forceRefresh) {
        throwIfLifecycleDisposed();
        synchronized (lock) {
            if (isLifecycleDisposed()) {
                throw new IllegalStateException("MCP Gateway project lifecycle disposed");
            }
            CompletableFuture<Void> existing = refreshFlight;
            if (existing != null && !existing.isDone()) {
                // force refresh cannot be silently swallowed: run one more pass after the current
                // pass, and use the newest project path if callers changed it meanwhile.
                if (forceRefresh || !Objects.equals(refreshProjectPath, projectPath)) {
                    refreshPending = true;
                    pendingRefreshProjectPath = projectPath;
                }
                return existing;
            }
            if (!forceRefresh && lifecycleState == McpGatewayLifecycleState.READY
                    && currentSnapshot != null && bridgeClient != null
                    && processHandle != null && processHandle.isAlive()) {
                return CompletableFuture.completedFuture(null);
            }
            long generation = operationGeneration;
            CompletableFuture<Void> created = new CompletableFuture<>();
            refreshFlight = created;
            refreshProjectPath = projectPath;
            refreshPending = false;
            pendingRefreshProjectPath = null;
            try {
                refreshTask = AppExecutorUtil.getAppExecutorService().submit(
                        () -> runRefreshFlight(created, projectPath, generation));
            } catch (RuntimeException e) {
                refreshFlight = null;
                refreshTask = null;
                created.completeExceptionally(e);
            }
            return created;
        }
    }

    private void runRefreshFlight(CompletableFuture<Void> flight, String initialProjectPath,
                                  long expectedOperationGeneration) {
        String projectPath = initialProjectPath;
        Throwable failure = null;
        try {
            while (!flight.isCancelled()) {
                failure = null;
                try {
                    runRefresh(projectPath, expectedOperationGeneration);
                } catch (Exception e) {
                    failure = unwrapCompletionFailure(e);
                    if (!isLifecycleDisposed() && isOperationCurrent(expectedOperationGeneration)) {
                        setLifecycleState(McpGatewayLifecycleState.FAILED, failure.getMessage());
                        LOG.warn("[McpGateway] Failed to refresh Gateway config: " + failure.getMessage(), failure);
                    }
                }
                synchronized (lock) {
                    if (flight.isCancelled() || refreshFlight != flight
                            || !isOperationCurrent(expectedOperationGeneration)) {
                        return;
                    }
                    if (refreshPending) {
                        projectPath = pendingRefreshProjectPath != null
                                ? pendingRefreshProjectPath : projectPath;
                        refreshProjectPath = projectPath;
                        refreshPending = false;
                        pendingRefreshProjectPath = null;
                        continue;
                    }
                    refreshFlight = null;
                    refreshTask = null;
                    if (failure == null) {
                        flight.complete(null);
                    } else {
                        flight.completeExceptionally(failure);
                    }
                    return;
                }
            }
        } catch (Throwable e) {
            synchronized (lock) {
                if (refreshFlight == flight) {
                    refreshFlight = null;
                    refreshTask = null;
                }
            }
            if (!flight.isDone()) {
                flight.completeExceptionally(unwrapCompletionFailure(e));
            }
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void runRefresh(String projectPath, long expectedOperationGeneration) throws Exception {
        throwIfOperationInvalid(expectedOperationGeneration);
        ensureStarted(projectPath, expectedOperationGeneration);
        throwIfOperationInvalid(expectedOperationGeneration);
        long catalogStartNanos = System.nanoTime();
        setLifecycleState(McpGatewayLifecycleState.CATALOG_LOADING, null);
        applySnapshot(projectPath, expectedOperationGeneration);
        throwIfOperationInvalid(expectedOperationGeneration);
        synchronized (lock) {
            lastCatalogReadyDurationMs = elapsedMillis(catalogStartNanos, System.nanoTime());
            setLifecycleState(McpGatewayLifecycleState.READY, null);
        }
    }
    /** Slow IO is deliberately outside {@link #lock}; only candidate publication is serialized. */
    void applySnapshot(String projectPath) throws Exception {
        applySnapshot(projectPath, currentOperationGeneration());
    }

    private void applySnapshot(String projectPath, long expectedOperationGeneration) throws Exception {
        throwIfOperationInvalid(expectedOperationGeneration);
        long startNanos = System.nanoTime();
        long candidateRevision;
        McpGatewayBridgeClient targetClient;
        McpGatewayProcessHandle targetHandle;
        long targetProcessGeneration;
        synchronized (lock) {
            candidateRevision = currentRevision == 0L ? 1L : currentRevision + 1L;
            targetClient = bridgeClient;
            targetHandle = processHandle;
            targetProcessGeneration = processGeneration;
        }
        if (targetClient == null) {
            throw new IllegalStateException("MCP Gateway bridge is unavailable");
        }
        McpGatewayConfigSnapshot candidate = collector.collect(candidateRevision, projectPath);
        throwIfOperationInvalid(expectedOperationGeneration);
        long collectEndNanos = System.nanoTime();
        synchronized (lock) {
            if (currentSnapshot != null && currentSnapshot.configHash().equals(candidate.configHash())) {
                LOG.info("[McpGatewayPerf] applySnapshot skipped (configHash unchanged): collectMs="
                        + elapsedMillis(startNanos, collectEndNanos) + ", revision=" + candidateRevision);
                return;
            }
        }
        // Node 侧会在这里同步 initialize/listTools，不能持有 service 全局锁。
        targetClient.postSnapshot(candidate);
        throwIfOperationInvalid(expectedOperationGeneration);
        synchronized (lock) {
            if (targetClient != bridgeClient || targetHandle != processHandle
                    || targetProcessGeneration != processGeneration) {
                throw new IllegalStateException("MCP Gateway resource changed during snapshot publication");
            }
            if (currentRevision >= candidateRevision) {
                return;
            }
            currentRevision = candidateRevision;
            currentSnapshot = candidate;
        }
        LOG.info("[McpGatewayPerf] applySnapshot committed: collectMs="
                + elapsedMillis(startNanos, collectEndNanos)
                + ", postMs=" + elapsedMillis(collectEndNanos, System.nanoTime())
                + ", revision=" + candidateRevision);
    }

    private long currentOperationGeneration() {
        synchronized (lock) {
            return operationGeneration;
        }
    }

    private boolean isOperationCurrent(long expectedOperationGeneration) {
        synchronized (lock) {
            return operationGeneration == expectedOperationGeneration;
        }
    }

    private void throwIfOperationInvalid(long expectedOperationGeneration) {
        throwIfLifecycleDisposed();
        if (!isOperationCurrent(expectedOperationGeneration)) {
            throw new IllegalStateException("MCP Gateway operation superseded");
        }
    }

    private void setLifecycleState(McpGatewayLifecycleState state, String failure) {
        synchronized (lock) {
            if (isLifecycleDisposed()) {
                return;
            }
            lifecycleState = state;
            if (failure != null && !failure.isBlank()) {
                lastFailure = failure;
            } else if (state == McpGatewayLifecycleState.IPC_READY || state == McpGatewayLifecycleState.READY) {
                lastFailure = null;
            }
        }
    }

    private void markDegradedDirect(CompletableFuture<?> flight, long expectedOperationGeneration, String diagnostic) {
        synchronized (lock) {
            if (isLifecycleDisposed() || lifecycleState == McpGatewayLifecycleState.STOPPED
                    || operationGeneration != expectedOperationGeneration) {
                return;
            }
            // A successful refresh that races the timeout is authoritative. An independent
            // direct-config failure (flight == null), however, must still expose degradation.
            boolean refreshCompletedSuccessfully = flight != null
                    && flight.isDone()
                    && !flight.isCompletedExceptionally()
                    && !flight.isCancelled();
            if (refreshCompletedSuccessfully && lifecycleState == McpGatewayLifecycleState.READY) {
                return;
            }
            lifecycleState = McpGatewayLifecycleState.DEGRADED_DIRECT;
            directDegradedCount++;
            recordLifecycle(LifecycleEventType.DEGRADED, processHandle,
                    processGeneration, "DEGRADED_DIRECT: " + diagnostic);
            if (diagnostic != null && !diagnostic.isBlank()) {
                lastFailure = diagnostic;
            }
        }
    }
    public McpGatewayLifecycleState lifecycleState() {
        return lifecycleState;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public Diagnostics diagnostics() {
        synchronized (lock) {
            int activeProcessCount = 0;
            if (processHandle != null && processHandle.isAlive()) {
                activeProcessCount++;
            }
            if (startingProcessHandle != null && startingProcessHandle.isAlive()
                    && startingProcessHandle != processHandle) {
                activeProcessCount++;
            }
            return new Diagnostics(
                    lifecycleState.value(),
                    lastFailure,
                    processGeneration,
                    activeProcessCount,
                    refreshFlight != null && !refreshFlight.isDone(),
                    restartCount,
                    lastColdStartDurationMs,
                    lastCatalogReadyDurationMs,
                    directDegradedCount);
        }
    }

    public String statusJson() {
        if (isLifecycleDisposed()) {
            return "{}";
        }
        McpGatewayBridgeClient client;
        McpGatewayLifecycleState state;
        String failure;
        long generation;
        synchronized (lock) {
            if (isLifecycleDisposed()) {
                return "{}";
            }
            client = bridgeClient;
            state = lifecycleState;
            failure = lastFailure;
            generation = processGeneration;
        }
        if (client == null) {
            return localStatusJson(state, failure);
        }
        try {
            JsonObject status = client.status();
            synchronized (lock) {
                if (isLifecycleDisposed()) {
                    return "{}";
                }
                if (client != bridgeClient || generation != processGeneration) {
                    return localStatusJson(lifecycleState, lastFailure);
                }
                status.addProperty("lifecycleState", lifecycleState.value());
                if (lastFailure != null && !lastFailure.isBlank()) {
                    status.addProperty("lastFailure", lastFailure);
                }
                return status.toString();
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to query status: " + e.getMessage());
            synchronized (lock) {
                if (isLifecycleDisposed()) {
                    return "{}";
                }
                return localStatusJson(lifecycleState, lastFailure);
            }
        }
    }

    private static String localStatusJson(McpGatewayLifecycleState state, String failure) {
        JsonObject status = new JsonObject();
        status.addProperty("lifecycleState", state.value());
        if (failure != null && !failure.isBlank()) {
            status.addProperty("lastFailure", failure);
        }
        return status.toString();
    }
    private void ensureStarted(String projectPath) throws Exception {
        ensureStarted(projectPath, currentOperationGeneration());
    }

    private void ensureStarted(String projectPath, long expectedOperationGeneration) throws Exception {
        throwIfOperationInvalid(expectedOperationGeneration);
        if (projectPath != null) {
            lastKnownProjectPath = projectPath;
        }
        McpGatewayProcessHandle currentHandle = processHandle;
        McpGatewayBridgeClient currentClient = bridgeClient;
        if (currentHandle != null && currentHandle.isAlive() && currentClient != null) {
            boolean ready = currentClient.waitUntilReady(REUSE_PROBE_TIMEOUT);
            throwIfOperationInvalid(expectedOperationGeneration);
            if (ready) {
                setLifecycleState(McpGatewayLifecycleState.IPC_READY, null);
                return;
            }
        }

        // 进入重建分支时先使旧 generation 失效并清理旧句柄。dispose 会在等待同一把锁前先
        // 翻转不可逆 disposed 闸门,所以下面的每个昂贵 IO/探测边界都必须复核生命周期。
        List<DetachedProcess> staleHandles;
        synchronized (lock) {
            if (operationGeneration != expectedOperationGeneration) {
                throw new IllegalStateException("MCP Gateway operation superseded");
            }
            staleHandles = detachProcesses();
            setLifecycleState(McpGatewayLifecycleState.PROCESS_STARTING, null);
        }
        stopDetachedProcesses(staleHandles);
        throwIfOperationInvalid(expectedOperationGeneration);
        Files.createDirectories(gatewayDir);
        throwIfLifecycleDisposed();
        cleanupStaleGatewayFromPreviousRun();
        throwIfOperationInvalid(expectedOperationGeneration);
        Files.deleteIfExists(stateFile);
        throwIfOperationInvalid(expectedOperationGeneration);

        File bridgeDir = BridgePreloader.getSharedResolver().findBridgeDir();
        throwIfLifecycleDisposed();
        if (bridgeDir == null) {
            throw new IllegalStateException("ai-bridge directory unavailable");
        }
        String node = NodeDetector.getInstance().findNodeExecutable();
        throwIfOperationInvalid(expectedOperationGeneration);
        Path serverScript = bridgeDir.toPath().resolve(McpGatewayConstants.SERVER_SCRIPT_NAME);
        List<String> command = new java.util.ArrayList<>(NodeDetector.buildNodeScriptCommand(node, serverScript.toString()));
        command.add(McpGatewayConstants.ARG_STATE_FILE);
        command.add(stateFile.toAbsolutePath().toString());
        command.add(McpGatewayConstants.ARG_TOKEN);
        command.add(token);
        command.add(McpGatewayConstants.ARG_PROJECT_PATH);
        command.add(projectPath != null ? projectPath : "");

        long coldStartStartNanos = System.nanoTime();
        McpGatewayProcessHandle startedHandle = null;
        boolean committed = false;
        long generation = 0L;
        try {
            // Keep process creation and publication of the pending handle in one critical section.
            // This closes the narrow window where dispose/stop could otherwise miss a just-spawned process.
            synchronized (lock) {
                if (operationGeneration != expectedOperationGeneration || isLifecycleDisposed()) {
                    throw new IllegalStateException("MCP Gateway operation superseded");
                }
                long previousGeneration = processGeneration;
                startedHandle = McpGatewayProcessHandle.start(command);
                generation = ++processGeneration;
                if (previousGeneration > 0L) {
                    restartCount++;
                }
                startingProcessHandle = startedHandle;
                startingProcessGeneration = generation;
            }
            recordLifecycle(LifecycleEventType.SPAWN, startedHandle, generation,
                    "MCP Gateway process spawned");
            if (generation > 1L) {
                recordLifecycle(LifecycleEventType.REBUILD, startedHandle, generation,
                        "MCP Gateway process rebuilt");
            }
            McpGatewayProcessHandle callbackHandle = startedHandle;
            final long startedGeneration = generation;
            startedHandle.setOnExitCallback(() -> {
                recordLifecycle(LifecycleEventType.EXIT, callbackHandle, startedGeneration,
                        "MCP Gateway process exited");
                onGatewayProcessExit(callbackHandle, startedGeneration);
            });
            throwIfOperationInvalid(expectedOperationGeneration);

            McpGatewayBridgeClient startedClient = new McpGatewayBridgeClient(stateFile, token);
            boolean ready = startedClient.waitUntilReady(COLD_START_TIMEOUT);
            throwIfOperationInvalid(expectedOperationGeneration);
            if (!ready) {
                throw new IllegalStateException("MCP Gateway did not become ready");
            }

            // 只有启动、ready 与最后一次生命周期复核全部成功后才发布新资源。dispose 后不会再写回
            // processHandle/bridgeClient,失败的局部句柄在 finally 中确定性终止。
            synchronized (lock) {
                if (operationGeneration != expectedOperationGeneration || isLifecycleDisposed()) {
                    throw new IllegalStateException("MCP Gateway operation superseded");
                }
                processHandle = startedHandle;
                startingProcessHandle = null;
                bridgeClient = startedClient;
                lastColdStartDurationMs = elapsedMillis(coldStartStartNanos, System.nanoTime());
                setLifecycleState(McpGatewayLifecycleState.IPC_READY, null);
                committed = true;
            }
        } finally {
            synchronized (lock) {
                if (startingProcessHandle == startedHandle) {
                    startingProcessHandle = null;
                    startingProcessGeneration = 0L;
                }
            }
            if (!committed && startedHandle != null) {
                recordLifecycle(LifecycleEventType.TERMINATE, startedHandle, generation,
                        "MCP Gateway startup failed; process tree terminated");
                startedHandle.setOnExitCallback(null);
                startedHandle.stop();
            }
        }
    }

    /**
     * gateway 进程意外退出时的自愈回调。handle + generation 构成资源身份,旧进程延迟到达的
     * onExit 不得重启或覆盖新 generation。自愈 Future 被保存,dispose 可主动取消尚未执行的任务。
     */
    private void onGatewayProcessExit(McpGatewayProcessHandle expectedHandle, long expectedGeneration) {
        if (isLifecycleDisposed()) {
            return;
        }
        synchronized (lock) {
            if (isLifecycleDisposed() || !isCurrentProcess(expectedHandle, expectedGeneration)) {
                return;
            }
            Future<?> previous = selfHealFuture;
            if (previous != null && !previous.isDone()) {
                previous.cancel(true);
            }
            selfHealFuture = AppExecutorUtil.getAppExecutorService().submit(
                    () -> runSelfHeal(expectedHandle, expectedGeneration));
        }
    }

    private void runSelfHeal(McpGatewayProcessHandle expectedHandle, long expectedGeneration) {
        if (isLifecycleDisposed() || Thread.currentThread().isInterrupted()) {
            return;
        }
        String projectPath;
        synchronized (lock) {
            if (isLifecycleDisposed() || Thread.currentThread().isInterrupted()
                    || !isCurrentProcess(expectedHandle, expectedGeneration)) {
                return;
            }
            long now = System.currentTimeMillis();
            exitTimestamps.addLast(now);
            while (!exitTimestamps.isEmpty() && exitTimestamps.peekFirst() < now - 30_000L) {
                exitTimestamps.pollFirst();
            }
            if (McpGatewayProcessHandle.isRestartStorm(
                    new java.util.ArrayList<>(exitTimestamps), now, 3, 30_000L)) {
                LOG.error("[McpGateway] restart storm detected (>3 unexpected exits in 30s); giving up self-heal. "
                        + "Likely config error (port conflict/script path); next buildCliConfig/refreshConfig resets counter.");
                setLifecycleState(McpGatewayLifecycleState.FAILED, "Gateway restart storm detected");
                return;
            }
            projectPath = lastKnownProjectPath;
        }
        try {
            LOG.info("[McpGateway] gateway process exited unexpectedly; attempting self-heal...");
            // Reuse the same single-flight as prewarm/send. No slow startup or catalog IO under lock.
            CompletableFuture<Void> flight = startOrJoinRefresh(projectPath, true);
            flight.get();
            LOG.info("[McpGateway] self-healed after unexpected exit");
        } catch (Exception e) {
            if (!isLifecycleDisposed()) {
                LOG.warn("[McpGateway] self-heal failed: " + e.getMessage()
                        + " (next send will cold-start a fresh gateway)");
            }
        }
    }

    private boolean isCurrentProcess(McpGatewayProcessHandle expectedHandle, long expectedGeneration) {
        return processHandle == expectedHandle && processGeneration == expectedGeneration;
    }

    private void invalidateRefreshFlight() {
        CompletableFuture<Void> pending;
        Future<?> task;
        synchronized (lock) {
            operationGeneration++;
            pending = refreshFlight;
            task = refreshTask;
            refreshFlight = null;
            refreshTask = null;
            refreshProjectPath = null;
            refreshPending = false;
            pendingRefreshProjectPath = null;
        }
        // CompletableFuture.cancel(true) does not reliably interrupt the supplier created by
        // runAsync; cancel the executor Future as well so collector/HTTP waits receive an interrupt.
        if (pending != null && !pending.isDone()) {
            pending.cancel(true);
        }
        if (task != null && !task.isDone()) {
            task.cancel(true);
        }
    }

    private void cancelSelfHeal() {
        Future<?> pending = selfHealFuture;
        selfHealFuture = null;
        if (pending != null && !pending.isDone()) {
            pending.cancel(true);
        }
    }

    private record DetachedProcess(McpGatewayProcessHandle handle, long generation) {
    }

    private List<DetachedProcess> detachProcesses() {
        McpGatewayProcessHandle activeHandle = processHandle;
        McpGatewayProcessHandle pendingHandle = startingProcessHandle;
        long activeGeneration = processGeneration;
        long pendingGeneration = startingProcessGeneration;
        processHandle = null;
        startingProcessHandle = null;
        startingProcessGeneration = 0L;
        bridgeClient = null;
        processGeneration++;
        if (activeHandle == null) {
            return pendingHandle == null ? List.of() : List.of(new DetachedProcess(pendingHandle, pendingGeneration));
        }
        if (pendingHandle == null || pendingHandle == activeHandle) {
            return List.of(new DetachedProcess(activeHandle, activeGeneration));
        }
        return List.of(new DetachedProcess(activeHandle, activeGeneration),
                new DetachedProcess(pendingHandle, pendingGeneration));
    }

    private void stopDetachedProcesses(List<DetachedProcess> staleHandles) {
        if (staleHandles == null) {
            return;
        }
        for (DetachedProcess detached : staleHandles) {
            McpGatewayProcessHandle staleHandle = detached.handle();
            if (staleHandle == null) {
                continue;
            }
            try {
                recordLifecycle(LifecycleEventType.TERMINATE, staleHandle, detached.generation(),
                        "MCP Gateway process tree terminated");
                staleHandle.setOnExitCallback(null);
                staleHandle.stop();
            } catch (Exception e) {
                LOG.debug("[McpGateway] Failed to stop stale process handle on rebuild: " + e.getMessage());
            }
        }
    }

    /**
     * 清场上次 JVM 异常退出(崩溃/强杀)遗留的孤儿 gateway。
     * <p>
     * gateway 是 JVM 的分离子进程,JVM 死后无人回收;重启后的新 JVM 也看不到它(孤儿面板按
     * parentPid 归属校验只认本 JVM)。旧 state file 里存着上次 gateway 的 pid——正常退出路径
     * (JS 侧 SIGINT/SIGTERM、Java 侧 dispose)都会删 state file,所以「state file 存在 + 其中
     * pid 对应进程还活着 + 父进程已死」基本等价于上次崩溃遗留。此时按 pid 杀整棵树
     * (gateway + 其 MCP server 子进程),防孤儿随崩溃次数累积滚雪球。
     * <p>
     * 双重防误杀:①pid 可能已被系统复用,校验进程可执行名必须含 node(gateway 恒为 node 进程);
     * ②父进程还活着说明是另一个 IDE 窗口正在管理的活 gateway(同项目双开共享 per-project
     * state file 路径),不越权击杀——只清「父进程已死」的真孤儿(与 Unix 孤儿定义一致)。
     * 任何失败只记 debug 日志,不阻塞 ensureStarted 主流程。
     */
    private void cleanupStaleGatewayFromPreviousRun() {
        if (stateFile == null || !Files.exists(stateFile)) {
            return;
        }
        try {
            JsonObject state = GsonHolder.GSON.fromJson(Files.readString(stateFile), JsonObject.class);
            if (state == null || !state.has("pid")) {
                return;
            }
            long pid = state.get("pid").getAsLong();
            if (pid <= 0) {
                return;
            }
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty()) {
                return; // 进程已死,残留的 state file 由随后的 deleteIfExists 收尾
            }
            ProcessHandle gateway = handle.get();
            String command = gateway.info().command().orElse("");
            String exeName = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1).toLowerCase();
            if (!exeName.contains("node")) {
                LOG.info("[McpGateway] Stale state file points at non-node pid " + pid + " (" + command + "), skipping cleanup");
                return;
            }
            if (gateway.parent().map(ProcessHandle::isAlive).orElse(false)) {
                LOG.info("[McpGateway] Gateway pid " + pid + " still has a live parent (another IDE instance?), leaving it alone");
                return;
            }
            LOG.warn("[McpGateway] Killing orphaned gateway process tree from previous session, pid=" + pid);
            PlatformUtils.terminateProcessTree(pid);
        } catch (Exception e) {
            LOG.debug("[McpGateway] Stale gateway cleanup skipped: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        synchronized (lifecycleLock) {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
        }
        cancelSelfHeal();
        invalidateRefreshFlight();
        McpGatewayBridgeClient client;
        List<DetachedProcess> staleHandles;
        synchronized (lock) {
            // 覆盖 onExit 在首次 cancel 与获得主锁之间提交 self-heal 的窄竞态。
            cancelSelfHeal();
            client = bridgeClient;
            staleHandles = detachProcesses();
            // disposed is already true, so setLifecycleState intentionally rejects updates;
            // publish the terminal state directly while holding the publication lock.
            lifecycleState = McpGatewayLifecycleState.STOPPED;
        }
        try {
            if (client != null) {
                client.stop();
            }
        } catch (Exception e) {
            LOG.debug("[McpGateway] Stop API failed: " + e.getMessage());
        }
        stopDetachedProcesses(staleHandles);
        try {
            if (stateFile != null) {
                Files.deleteIfExists(stateFile);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 用户在行为菜单关闭 gateway 开关时调用:停止常驻 Node 进程并清空内部状态,
     * 以便下次 {@link #ensureStarted} 从零重建。区别于 {@link #dispose} 的不可逆生命周期闸门，
     * 本方法只释放当前资源，后续仍允许重启；{@code currentRevision} 重置为 0，
     * 使 {@link #applySnapshot} 下次启动产出全新快照。关后下一条消息起走直连 MCP。
     */
    public void stopGateway() {
        if (isLifecycleDisposed()) {
            return;
        }
        cancelSelfHeal();
        invalidateRefreshFlight();
        McpGatewayBridgeClient client;
        List<DetachedProcess> staleHandles;
        synchronized (lock) {
            if (isLifecycleDisposed()) {
                return;
            }
            client = bridgeClient;
            staleHandles = detachProcesses();
            resetSnapshotState();
            setLifecycleState(McpGatewayLifecycleState.STOPPED, null);
        }
        try {
            if (client != null) {
                client.stop();
            }
        } catch (Exception e) {
            LOG.debug("[McpGateway] Stop API failed on user toggle: " + e.getMessage());
        }
        stopDetachedProcesses(staleHandles);
        try {
            if (stateFile != null) {
                Files.deleteIfExists(stateFile);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 重置 snapshot 状态(纯内存,无 IO):清 currentSnapshot / currentRevision / exitTimestamps。
     * 提取自 {@link #stopGateway} 供 {@link #reloadGateway} 复用,使"重载强制重推"语义可单测
     * (reset 后 applySnapshot 因 currentSnapshot==null 不 skip → 重新 post)。
     */
    void resetSnapshotState() {
        synchronized (lock) {
            currentSnapshot = null;
            currentRevision = 0L;
            exitTimestamps.clear();
        }
    }

    /**
     * 手动硬重载(用户在 MCP 面板点"重载 Gateway"):停旧进程 + 清状态 + 重建进程 + 强制重推 snapshot。
     * 用于自动加载失败的恢复——{@code onExit} 风暴保护放弃重建、配置错反复崩溃、snapshot 空载等场景。
     * <p>语义 = {@link #stopGateway} + {@link #ensureStarted} + {@link #applySnapshot}:stopGateway 停进程
     * 并 revision 归零,ensureStarted 起新进程,applySnapshot 因 currentSnapshot==null 强制 post(不 skip)。
     * 不复用 {@link #refreshConfig}(它内部吞异常,失败时无法准确反馈给调用方)——此处直接调 throws 版方法,
     * ensureStarted 失败(gateway 起不来)向上抛,由 handler 发失败 toast。并发 buildCliConfig 由同一把 lock 串行化。
     *
     * @throws Exception ensureStarted / applySnapshot 失败(端口冲突、脚本路径错、postSnapshot 超时等)
     */
    public void reloadGateway(String projectPath) throws Exception {
        throwIfLifecycleDisposed();
        if (!McpGatewayFeatureFlags.isGatewayActive()) {
            throw new IllegalStateException("MCP Gateway is disabled");
        }
        stopGateway();
        throwIfLifecycleDisposed();
        CompletableFuture<Void> flight = startOrJoinRefresh(projectPath, true);
        try {
            flight.get();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private boolean isLifecycleDisposed() {
        return disposed.get() || (project != null && project.isDisposed());
    }

    private void throwIfLifecycleDisposed() {
        if (isLifecycleDisposed()) {
            throw new IllegalStateException("MCP Gateway project lifecycle disposed");
        }
    }

    private void recordLifecycle(LifecycleEventType type,
                                 McpGatewayProcessHandle handle,
                                 long generation,
                                 String detail) {
        if (lifecycleService == null) {
            return;
        }
        lifecycleService.record(
                type,
                lifecycleService.metadata(
                        LifecycleProcessKind.MCP_GATEWAY,
                        null,
                        null,
                        generation
                ),
                handle != null ? handle.pid() : -1L,
                detail
        );
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 从当前快照提取指定 provider 的真实 mcp server id 列表(过滤 melon_gateway 自身)。
     * 供 Codex {@code -c}/OpenCode {@code OPENCODE_CONFIG_CONTENT} 注入逐个禁用真实 server
     * (合并语义:不禁则真实 server 仍直连=慢)。sourceProvider 与 {@link ProviderType#value()} 对齐。
     */
    private static List<String> realServerIds(McpGatewayConfigSnapshot snapshot, ProviderType provider) {
        if (snapshot == null) {
            return List.of();
        }
        String target = provider.value();
        List<String> ids = new java.util.ArrayList<>();
        for (McpGatewayServerSpec spec : snapshot.servers()) {
            if (target.equals(spec.sourceProvider())
                    && !McpGatewayConstants.GATEWAY_SERVER_ID.equals(spec.serverId())) {
                ids.add(spec.serverId());
            }
        }
        return ids;
    }

    private static String safeProjectPart(String projectPath) {
        String raw = projectPath == null || projectPath.isBlank()
                ? "default"
                : Integer.toHexString(projectPath.hashCode());
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}