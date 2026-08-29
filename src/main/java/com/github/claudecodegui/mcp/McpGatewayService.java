package com.github.claudecodegui.mcp;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.session.runtime.ProviderType;
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
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project-scoped facade for the CLI MCP Gateway.
 */
@Service(Service.Level.PROJECT)
public final class McpGatewayService implements Disposable {
    private static final Logger LOG = Logger.getInstance(McpGatewayService.class);
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

    private final Project project;
    private final Object lock = new Object();
    private final Object lifecycleLock = new Object();
    private final McpGatewayConfigCollector collector;
    private final McpGatewayConfigWriter configWriter;
    private final Path gatewayDir;
    private final Path stateFile;
    private final String token;

    private McpGatewayProcessHandle processHandle;
    private McpGatewayBridgeClient bridgeClient;
    private McpGatewayConfigSnapshot currentSnapshot;
    private long currentRevision;
    private long processGeneration;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private volatile Future<?> selfHealFuture;
    /**
     * 最近一次 ensureStarted 的 projectPath,供 onExit 自愈回调重建时复用。
     * 重建时复用——gateway 崩溃后无调用上下文,需记住上次用的 path 才能 ensureStarted 自愈。
     */
    private volatile String lastKnownProjectPath;
    /** 最近 N 次 gateway 进程意外退出时间戳(epoch ms),供 {@link McpGatewayProcessHandle#isRestartStorm} 判风暴。 */
    private final Deque<Long> exitTimestamps = new ArrayDeque<>();

    public McpGatewayService(@NotNull Project project) {
        this.project = project;
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
        // 无注入机制的 provider 先短路(锁与 ensureStarted 之前):否则 kimi 这类已接线
        // gatewayService 但 writer 恒 disabled 的 provider 每轮 send 都白付冷启动/锁成本。
        if (configWriter != null && !configWriter.supports(provider)) {
            return McpGatewayCliConfig.disabled(
                    "MCP gateway injection not configured for provider: " + provider.value());
        }
        synchronized (lock) {
            long startNanos = System.nanoTime();
            try {
                throwIfLifecycleDisposed();
                ensureStarted(projectPath);
                long afterEnsureNanos = System.nanoTime();
                refreshConfig(projectPath);
                throwIfLifecycleDisposed();
                long afterRefreshNanos = System.nanoTime();
                File bridgeDir = BridgePreloader.getSharedResolver().findBridgeDir();
                throwIfLifecycleDisposed();
                if (bridgeDir == null) {
                    return McpGatewayCliConfig.disabled("ai-bridge directory unavailable");
                }
                String node = NodeDetector.getInstance().findNodeExecutable();
                throwIfLifecycleDisposed();
                Path stdioClient = bridgeDir.toPath().resolve(McpGatewayConstants.STDIO_CLIENT_SCRIPT_PATH);
                List<String> command = NodeDetector.buildNodeScriptCommand(node, stdioClient.toString());
                List<String> serverIds = realServerIds(currentSnapshot, provider);
                // Phase 0 埋点(gateway_*):区分 gateway 就绪等待与 snapshot 刷新等待,
                // 使"每轮是否重付 MCP 初始化成本"可直接从日志归因。
                LOG.info("[McpGatewayPerf] buildCliConfig: provider=" + provider.value() + ", tabId=" + tabId
                        + ", ensureMs=" + elapsedMillis(startNanos, afterEnsureNanos)
                        + ", refreshMs=" + elapsedMillis(afterEnsureNanos, afterRefreshNanos)
                        + ", totalMs=" + elapsedMillis(startNanos, afterRefreshNanos)
                        + ", servers=" + serverIds.size() + ", revision=" + currentRevision);
                return configWriter.write(provider, tabId, currentRevision, stateFile, command, serverIds);
            } catch (Exception e) {
                LOG.warn("[McpGateway] Falling back to direct MCP config: " + e.getMessage(), e);
                return McpGatewayCliConfig.disabled(e.getMessage());
            }
        }
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000;
    }

    public void refreshConfig(String projectPath) {
        // gate 用 isGatewayActive(等价 isCliEnabled,SDK 模式移除后仅剩 CLI 路径):
        // 预热与 MCP 增删停重载(Claude/Codex handler)都不分 provider,任何启用 gateway
        // 的配置下改 MCP 都需同步到 gateway,否则 CLI 调用会用到过期 snapshot。
        if (!McpGatewayFeatureFlags.isGatewayActive() || isLifecycleDisposed()) {
            return;
        }
        synchronized (lock) {
            try {
                throwIfLifecycleDisposed();
                ensureStarted(projectPath);
                applySnapshot(projectPath);
            } catch (Exception e) {
                if (!isLifecycleDisposed()) {
                    LOG.warn("[McpGateway] Failed to refresh Gateway config: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Collects the latest snapshot and pushes it to the Gateway process, bumping the
     * revision only when the config hash actually changes. Shared by all refresh
     * entry points so every CLI turn sees the same fixed revision.
     */
    void applySnapshot(String projectPath) throws Exception {
        throwIfLifecycleDisposed();
        long startNanos = System.nanoTime();
        long candidateRevision = currentRevision == 0L ? 1L : currentRevision + 1L;
        McpGatewayConfigSnapshot candidate = collector.collect(candidateRevision, projectPath);
        throwIfLifecycleDisposed();
        long collectEndNanos = System.nanoTime();
        if (currentSnapshot != null && currentSnapshot.configHash().equals(candidate.configHash())) {
            LOG.info("[McpGatewayPerf] applySnapshot skipped (configHash unchanged): collectMs="
                    + elapsedMillis(startNanos, collectEndNanos) + ", revision=" + candidateRevision);
            return;
        }
        // 必须先 post 成功再提交本地 currentSnapshot/currentRevision:首次 /snapshot 会触发 Node 侧
        // applySnapshot 同步等所有 MCP server 的 initialize+listTools(首屏冷加载往往远超秒级)。若
        // 先提交再 post,post 超时/失败时本地已"假成功",后续 applySnapshot 因 configHash 相同而 skip、
        // 永不重推,gateway 实际空载、CLI 拿不到 MCP 工具。先 post 失败则抛异常、字段不变,下次
        // applySnapshot 自动重推(复现见 idea.log 2026-07-02 BridgePreloader.prewarmMcpGateway
        // → postSnapshot HttpTimeoutException)。
        throwIfLifecycleDisposed();
        bridgeClient.postSnapshot(candidate);
        throwIfLifecycleDisposed();
        currentRevision = candidateRevision;
        currentSnapshot = candidate;
        LOG.info("[McpGatewayPerf] applySnapshot committed: collectMs="
                + elapsedMillis(startNanos, collectEndNanos)
                + ", postMs=" + elapsedMillis(collectEndNanos, System.nanoTime())
                + ", revision=" + candidateRevision);
    }

    public String statusJson() {
        if (isLifecycleDisposed()) {
            return "{}";
        }
        synchronized (lock) {
            try {
                if (isLifecycleDisposed() || bridgeClient == null) {
                    return "{}";
                }
                return bridgeClient.status().toString();
            } catch (Exception e) {
                LOG.warn("[McpGateway] Failed to query status: " + e.getMessage());
                return "{}";
            }
        }
    }

    private void ensureStarted(String projectPath) throws Exception {
        throwIfLifecycleDisposed();
        if (projectPath != null) {
            lastKnownProjectPath = projectPath;
        }
        McpGatewayProcessHandle currentHandle = processHandle;
        McpGatewayBridgeClient currentClient = bridgeClient;
        if (currentHandle != null && currentHandle.isAlive() && currentClient != null) {
            boolean ready = currentClient.waitUntilReady(REUSE_PROBE_TIMEOUT);
            throwIfLifecycleDisposed();
            if (ready) {
                return;
            }
        }

        // 进入重建分支时先使旧 generation 失效并清理旧句柄。dispose 会在等待同一把锁前先
        // 翻转不可逆 disposed 闸门,所以下面的每个昂贵 IO/探测边界都必须复核生命周期。
        stopExistingProcess();
        throwIfLifecycleDisposed();
        Files.createDirectories(gatewayDir);
        throwIfLifecycleDisposed();
        cleanupStaleGatewayFromPreviousRun();
        throwIfLifecycleDisposed();
        Files.deleteIfExists(stateFile);
        throwIfLifecycleDisposed();

        File bridgeDir = BridgePreloader.getSharedResolver().findBridgeDir();
        throwIfLifecycleDisposed();
        if (bridgeDir == null) {
            throw new IllegalStateException("ai-bridge directory unavailable");
        }
        String node = NodeDetector.getInstance().findNodeExecutable();
        throwIfLifecycleDisposed();
        Path serverScript = bridgeDir.toPath().resolve(McpGatewayConstants.SERVER_SCRIPT_NAME);
        List<String> command = new java.util.ArrayList<>(NodeDetector.buildNodeScriptCommand(node, serverScript.toString()));
        command.add(McpGatewayConstants.ARG_STATE_FILE);
        command.add(stateFile.toAbsolutePath().toString());
        command.add(McpGatewayConstants.ARG_TOKEN);
        command.add(token);
        command.add(McpGatewayConstants.ARG_PROJECT_PATH);
        command.add(projectPath != null ? projectPath : "");

        McpGatewayProcessHandle startedHandle = null;
        boolean committed = false;
        long generation = ++processGeneration;
        try {
            throwIfLifecycleDisposed();
            startedHandle = McpGatewayProcessHandle.start(command);
            McpGatewayProcessHandle callbackHandle = startedHandle;
            startedHandle.setOnExitCallback(() -> onGatewayProcessExit(callbackHandle, generation));
            throwIfLifecycleDisposed();

            McpGatewayBridgeClient startedClient = new McpGatewayBridgeClient(stateFile, token);
            boolean ready = startedClient.waitUntilReady(COLD_START_TIMEOUT);
            throwIfLifecycleDisposed();
            if (!ready) {
                throw new IllegalStateException("MCP Gateway did not become ready");
            }

            // 只有启动、ready 与最后一次生命周期复核全部成功后才发布新资源。dispose 后不会再写回
            // processHandle/bridgeClient,失败的局部句柄在 finally 中确定性终止。
            processHandle = startedHandle;
            bridgeClient = startedClient;
            committed = true;
        } finally {
            if (!committed && startedHandle != null) {
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
                return;
            }
            try {
                LOG.info("[McpGateway] gateway process exited unexpectedly; attempting self-heal...");
                ensureStarted(lastKnownProjectPath);
                if (currentSnapshot != null) {
                    applySnapshot(lastKnownProjectPath);
                }
                LOG.info("[McpGateway] self-healed after unexpected exit");
            } catch (Exception e) {
                if (!isLifecycleDisposed()) {
                    LOG.warn("[McpGateway] self-heal failed: " + e.getMessage()
                            + " (next send will cold-start a fresh gateway)");
                }
            }
        }
    }

    private boolean isCurrentProcess(McpGatewayProcessHandle expectedHandle, long expectedGeneration) {
        return processHandle == expectedHandle && processGeneration == expectedGeneration;
    }

    private void cancelSelfHeal() {
        Future<?> pending = selfHealFuture;
        selfHealFuture = null;
        if (pending != null && !pending.isDone()) {
            pending.cancel(true);
        }
    }

    private void stopExistingProcess() {
        McpGatewayProcessHandle staleHandle = processHandle;
        processHandle = null;
        bridgeClient = null;
        processGeneration++;
        if (staleHandle != null) {
            try {
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
        synchronized (lock) {
            // 覆盖 onExit 在首次 cancel 与获得主锁之间提交 self-heal 的窄竞态。
            cancelSelfHeal();
            try {
                if (bridgeClient != null) {
                    bridgeClient.stop();
                }
            } catch (Exception e) {
                LOG.debug("[McpGateway] Stop API failed: " + e.getMessage());
            }
            stopExistingProcess();
            try {
                if (stateFile != null) {
                    Files.deleteIfExists(stateFile);
                }
            } catch (Exception ignored) {
            }
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
        synchronized (lock) {
            if (isLifecycleDisposed()) {
                return;
            }
            try {
                if (bridgeClient != null) {
                    bridgeClient.stop();
                }
            } catch (Exception e) {
                LOG.debug("[McpGateway] Stop API failed on user toggle: " + e.getMessage());
            }
            stopExistingProcess();
            try {
                Files.deleteIfExists(stateFile);
            } catch (Exception ignored) {
            }
            resetSnapshotState();
        }
    }

    /**
     * 重置 snapshot 状态(纯内存,无 IO):清 currentSnapshot / currentRevision / exitTimestamps。
     * 提取自 {@link #stopGateway} 供 {@link #reloadGateway} 复用,使"重载强制重推"语义可单测
     * (reset 后 applySnapshot 因 currentSnapshot==null 不 skip → 重新 post)。
     */
    void resetSnapshotState() {
        currentSnapshot = null;
        currentRevision = 0L;
        exitTimestamps.clear();
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
        synchronized (lock) {
            throwIfLifecycleDisposed();
            stopGateway();
            throwIfLifecycleDisposed();
            ensureStarted(projectPath);
            applySnapshot(projectPath);
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
