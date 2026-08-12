package com.github.claudecodegui.mcp;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.startup.BridgePreloader;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
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
import java.util.concurrent.CompletableFuture;

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
    private final McpGatewayConfigCollector collector;
    private final McpGatewayConfigWriter configWriter;
    private final Path gatewayDir;
    private final Path stateFile;
    private final String token;

    private McpGatewayProcessHandle processHandle;
    private McpGatewayBridgeClient bridgeClient;
    private McpGatewayConfigSnapshot currentSnapshot;
    private long currentRevision;
    /**
     * 最近一次 ensureStarted 的 projectPath,供 onExit 自愈回调 {@link #onGatewayProcessExit}
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
        synchronized (lock) {
            try {
                ensureStarted(projectPath);
                refreshConfig(projectPath);
                File bridgeDir = BridgePreloader.getSharedResolver().findSdkDir();
                if (bridgeDir == null) {
                    return McpGatewayCliConfig.disabled("ai-bridge directory unavailable");
                }
                String node = NodeDetector.getInstance().findNodeExecutable();
                Path stdioClient = bridgeDir.toPath().resolve(McpGatewayConstants.STDIO_CLIENT_SCRIPT_PATH);
                List<String> command = NodeDetector.buildNodeScriptCommand(node, stdioClient.toString());
                return configWriter.write(provider, tabId, currentRevision, stateFile, command,
                        realServerIds(currentSnapshot, provider));
            } catch (Exception e) {
                LOG.warn("[McpGateway] Falling back to direct MCP config: " + e.getMessage(), e);
                return McpGatewayCliConfig.disabled(e.getMessage());
            }
        }
    }

    public void refreshConfig(String projectPath) {
        // gate 用 isGatewayActive(cli||sdk)而非 isCliEnabled:预热(CLI/SDK 运行时都受益)与 MCP
        // 增删停重载(Claude/Codex handler)都不分运行时路径,纯 SDK 模式(cli.enabled=false)用户
        // 改 MCP 也需同步到 gateway,否则 SDK 调用会用到过期 snapshot。
        if (!McpGatewayFeatureFlags.isGatewayActive()) {
            return;
        }
        synchronized (lock) {
            try {
                ensureStarted(projectPath);
                applySnapshot(projectPath);
            } catch (Exception e) {
                LOG.warn("[McpGateway] Failed to refresh Gateway config: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Collects the latest snapshot and pushes it to the Gateway process, bumping the
     * revision only when the config hash actually changes. Shared by the CLI refresh
     * path (CLI-gated) and the SDK binding path (SDK-gated) so both runtimes see the
     * same fixed revision for a given turn.
     */
    void applySnapshot(String projectPath) throws Exception {
        long candidateRevision = currentRevision == 0L ? 1L : currentRevision + 1L;
        McpGatewayConfigSnapshot candidate = collector.collect(candidateRevision, projectPath);
        if (currentSnapshot != null && currentSnapshot.configHash().equals(candidate.configHash())) {
            return;
        }
        // 必须先 post 成功再提交本地 currentSnapshot/currentRevision:首次 /snapshot 会触发 Node 侧
        // applySnapshot 同步等所有 MCP server 的 initialize+listTools(首屏冷加载往往远超秒级)。若
        // 先提交再 post,post 超时/失败时本地已"假成功",后续 applySnapshot 因 configHash 相同而 skip、
        // 永不重推,gateway 实际空载、CLI 拿不到 MCP 工具。先 post 失败则抛异常、字段不变,下次
        // applySnapshot 自动重推(复现见 idea.log 2026-07-02 BridgePreloader.prewarmMcpGateway
        // → postSnapshot HttpTimeoutException)。
        bridgeClient.postSnapshot(candidate);
        currentRevision = candidateRevision;
        currentSnapshot = candidate;
    }

    public String statusJson() {
        synchronized (lock) {
            try {
                if (bridgeClient == null) {
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
        if (projectPath != null) {
            lastKnownProjectPath = projectPath;
        }
        if (processHandle != null && processHandle.isAlive() && bridgeClient != null
                && bridgeClient.waitUntilReady(REUSE_PROBE_TIMEOUT)) {
            return;
        }
        // 进入重建分支:先停掉可能残留的旧进程句柄,避免孤儿 Node 进程 + 端口泄漏。
        // 触发场景:预热线程正在 cold-start(进程已 spawn 但 /status 尚未 ready)时另一线程
        // 进入此方法——旧 handle 活着但探测窗口内探不到,若直接覆盖字段,旧进程成孤儿、
        // 端口仍占用。REUSE_PROBE_TIMEOUT 放宽到与 cold-start 同量级已大幅缓解此竞态,
        // 此处作兜底:真走到重建时确保旧进程被显式停止。
        stopExistingProcess();
        Files.createDirectories(gatewayDir);
        Files.deleteIfExists(stateFile);

        File bridgeDir = BridgePreloader.getSharedResolver().findSdkDir();
        if (bridgeDir == null) {
            throw new IllegalStateException("ai-bridge directory unavailable");
        }
        String node = NodeDetector.getInstance().findNodeExecutable();
        Path serverScript = bridgeDir.toPath().resolve(McpGatewayConstants.SERVER_SCRIPT_NAME);
        List<String> command = new java.util.ArrayList<>(NodeDetector.buildNodeScriptCommand(node, serverScript.toString()));
        command.add(McpGatewayConstants.ARG_STATE_FILE);
        command.add(stateFile.toAbsolutePath().toString());
        command.add(McpGatewayConstants.ARG_TOKEN);
        command.add(token);
        command.add(McpGatewayConstants.ARG_PROJECT_PATH);
        command.add(projectPath != null ? projectPath : "");

        processHandle = McpGatewayProcessHandle.start(command);
        // Opt3:注入 onExit 自愈回调——gateway 进程意外退出(崩溃/OOM/被外部 kill)时 Java 侧
        // process.onExit() 感知并异步重建,根治"gateway 崩溃 → Java 零感知 → 下一轮 send 等满
        // Opt2 的 5s 超时窗口"。回调内 synchronized(lock) 与现有调用方互斥,入口 processHandle==null
        // 早退防 stopGateway 后误触发,isRestartStorm 防配置错时反复崩溃拖垮 commonPool。
        processHandle.setOnExitCallback(this::onGatewayProcessExit);
        bridgeClient = new McpGatewayBridgeClient(stateFile, token);
        if (!bridgeClient.waitUntilReady(COLD_START_TIMEOUT)) {
            throw new IllegalStateException("MCP Gateway did not become ready");
        }
    }

    /**
     * gateway 进程意外退出时的自愈回调(由 {@code McpGatewayProcessHandle.onProcessExit} 在 commonPool 触发)。
     * <p>异步化(CompletableFuture.runAsync)避免阻塞 onExit 的 commonPool 线程;内部 synchronized(lock)
     * 与现有调用方互斥。{@code setOnExitCallback(null)} 是首选屏障,此处 {@code processHandle==null}
     * 早退是竞态兜底——onExit 已在飞时读到的 callback 仍可能非 null,而 stopGateway 已置 processHandle=null。
     */
    private void onGatewayProcessExit() {
        CompletableFuture.runAsync(() -> {
            // MCP-01:dispose() 后 project 已销毁,自愈无意义(ensureStarted 会触碰已释放的平台资源)。
            // processHandle==null 早退是主屏障,此处 project.isDisposed() 是竞态双保险。
            if (project != null && project.isDisposed()) {
                return;
            }
            synchronized (lock) {
                if (processHandle == null) {
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
                    LOG.warn("[McpGateway] self-heal failed: " + e.getMessage()
                            + " (next send will cold-start a fresh gateway)");
                }
            }
        });
    }

    private void stopExistingProcess() {
        if (processHandle != null) {
            try {
                // stop 前清回调:防 stop() 触发 onExit 误自愈(handle.stop 内也双重清,这里协同)。
                processHandle.setOnExitCallback(null);
                processHandle.stop();
            } catch (Exception e) {
                LOG.debug("[McpGateway] Failed to stop stale process handle on rebuild: " + e.getMessage());
            }
        }
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            try {
                if (bridgeClient != null) {
                    bridgeClient.stop();
                }
            } catch (Exception e) {
                LOG.debug("[McpGateway] Stop API failed: " + e.getMessage());
            }
            if (processHandle != null) {
                processHandle.setOnExitCallback(null);
                processHandle.stop();
            }
            try {
                Files.deleteIfExists(stateFile);
            } catch (Exception ignored) {
            }
            // MCP-01:与 stopGateway() 对齐显式置空字段。dispose 后在飞的 onGatewayProcessExit 自愈
            // 回调等到锁时 processHandle==null 早退生效,杜绝「dispose 已停进程→自愈仍在 disposed
            // service 上 ensureStarted 重启 Node→孤儿进程 + 端口占用」竞态。
            processHandle = null;
            bridgeClient = null;
        }
    }

    /**
     * 用户在行为菜单关闭 gateway 开关时调用:停止常驻 Node 进程并清空内部状态,
     * 以便下次 {@link #ensureStarted} 从零重建。区别于 {@link #dispose}(项目销毁时一次性清理、
     * 不置空字段),这里把句柄置 null 才允许重启;{@code currentRevision} 重置为 0,
     * 使 {@link #applySnapshot} 下次启动产出全新快照。关后下一条消息起走直连 MCP。
     */
    public void stopGateway() {
        synchronized (lock) {
            try {
                if (bridgeClient != null) {
                    bridgeClient.stop();
                }
            } catch (Exception e) {
                LOG.debug("[McpGateway] Stop API failed on user toggle: " + e.getMessage());
            }
            if (processHandle != null) {
                processHandle.setOnExitCallback(null);
                processHandle.stop();
            }
            try {
                Files.deleteIfExists(stateFile);
            } catch (Exception ignored) {
            }
            processHandle = null;
            bridgeClient = null;
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
        if (!McpGatewayFeatureFlags.isGatewayActive()) {
            throw new IllegalStateException("MCP Gateway is disabled");
        }
        synchronized (lock) {
            stopGateway();
            ensureStarted(projectPath);
            applySnapshot(projectPath);
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
