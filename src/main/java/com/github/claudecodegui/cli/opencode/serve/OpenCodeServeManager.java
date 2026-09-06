package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliEnvironmentBuilder;
import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleEventType;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.service.lifecycle.LifecycleProcessKind;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * opencode serve 长驻进程管理器(每 project 一个 serve 进程,MVP)。
 * <p>
 * {@code opencode serve --port <空闲端口> --hostname 127.0.0.1} 无认证 HTTP/SSE 服务,
 * 一个 serve 经 {@code POST /session?directory=<cwd>} 服务多 tab 多目录(session 存
 * ~/.local/share/opencode/opencode.db,重启后仍在,B13 等价重试见 OpenCodeServeSession)。
 * <p>
 * 防失控(总则六,参照 CliPersistentProcessRegistry / KimiAcpWarmPool,但 MVP 不做
 * 空闲 sweeper/LRU——serve 为 project 级单例,无容量压力):
 * <ul>
 *   <li>进程注册 {@link ProcessManager}(项目关闭可确定性终止进程树);</li>
 *   <li>指纹 = CLI 版本 + gateway endpoint + gateway 注入 env 内容;指纹漂移 / 进程崩溃 /
 *       SSE 断线(含半开:client 侧 server.heartbeat 活性看门狗超时关流)→ 摘除旧句柄重建,
 *       当轮由调用方降级 one-shot(对齐 acquire 未命中的静默降级);</li>
 *   <li>连续 spawn 失败 {@value #MAX_CONSECUTIVE_SPAWN_FAILURES} 次熔断,本次运行内不再尝试
 *       (全走 one-shot),防坏环境无限重启;</li>
 *   <li>重建 / terminateServe 的旧句柄 teardown 异步化(平台共享执行器,调用线程不阻塞
 *       waitFor);dispose 同步优雅关闭(client.close → terminateProcess 兜底)。</li>
 * </ul>
 * MVP 显式简化(有意差异,勿视为遗漏):请求级 extraEnv 仅在 spawn 时注入一次,
 * 不进指纹——serve 为 project 级共享进程,per-request env 漂移不触发重建。
 */
@Service(Service.Level.PROJECT)
public final class OpenCodeServeManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(OpenCodeServeManager.class);

    /** serve 就绪轮询上限:spawn 后轮询 TCP 回环端口直到可连接。 */
    private static final long SERVE_READY_TIMEOUT_MS = 5_000L;
    private static final long SERVE_READY_POLL_MS = 100L;
    /** 连续 spawn 失败熔断上限(对齐 KimiAcpWarmPool MAX_CONSECUTIVE_WARM_FAILURES)。 */
    private static final int MAX_CONSECUTIVE_SPAWN_FAILURES = 2;

    /** serve 句柄:进程 + ProcessManager 令牌 + HTTP/SSE 客户端 + spawn 时指纹。 */
    private record ServeHandle(
            Process process,
            String processToken,
            OpenCodeServeClient client,
            String fingerprint
    ) {
    }

    private final Project project;
    private final LifecycleObservabilityService lifecycleService;
    /** 当前句柄(全部访问在 synchronized 块内)。 */
    private ServeHandle handle;
    private int consecutiveSpawnFailures;
    private volatile boolean disposed;

    public OpenCodeServeManager(@NotNull Project project) {
        this(project, LifecycleObservabilityService.getInstance(project));
    }

    /** 测试友好构造:生命周期观测可空。 */
    OpenCodeServeManager(@NotNull Project project, @Nullable LifecycleObservabilityService lifecycleService) {
        this.project = project;
        this.lifecycleService = lifecycleService;
    }

    public static OpenCodeServeManager getInstance(@NotNull Project project) {
        return project.getService(OpenCodeServeManager.class);
    }

    /**
     * 获取可用 serve 客户端。命中(进程存活且指纹匹配)直接返回;未命中(首次 / 指纹漂移 /
     * 崩溃 / SSE 断线后)同步重建一次;重建失败或熔断中返回 null——调用方当轮降级 one-shot。
     */
    public synchronized @Nullable OpenCodeServeClient acquire(String tabId, String cwd,
                                                              Map<String, String> extraEnv) {
        if (disposed) {
            return null;
        }
        if (consecutiveSpawnFailures >= MAX_CONSECUTIVE_SPAWN_FAILURES) {
            return null;
        }
        McpGatewayCliConfig gatewayConfig = buildGatewayConfig(tabId, cwd);
        String fingerprint = computeFingerprint(gatewayConfig);
        ServeHandle existing = handle;
        if (existing != null) {
            if (existing.process().isAlive() && existing.fingerprint().equals(fingerprint)) {
                return existing.client();
            }
            LOG.info("[OpenCodeServeManager] serve handle stale (dead or fingerprint drift), rebuilding: tab=" + tabId);
            // 先摘除再异步关:旧句柄与后续 spawn 无竞争,acquire 线程不阻塞 waitFor
            handle = null;
            closeHandleAsync(existing);
        }
        ServeHandle spawned = spawn(cwd, extraEnv, gatewayConfig, fingerprint);
        if (spawned == null) {
            consecutiveSpawnFailures++;
            if (consecutiveSpawnFailures >= MAX_CONSECUTIVE_SPAWN_FAILURES) {
                LOG.warn("[OpenCodeServeManager] serve spawn circuit breaker opened (consecutiveFailures="
                        + consecutiveSpawnFailures + "), one-shot for the rest of this run");
            }
            return null;
        }
        consecutiveSpawnFailures = 0;
        handle = spawned;
        return spawned.client();
    }

    /**
     * 强杀当前 serve(interrupt 兜底:abort API 超时无回应 / 轮挂死)。句柄摘除,
     * 下次 acquire 重建;进行中的轮由 client 流关闭通知收尾。
     */
    public synchronized void terminateServe(String reason) {
        ServeHandle current = handle;
        handle = null;
        if (current != null) {
            LOG.warn("[OpenCodeServeManager] terminating serve: " + reason);
            closeHandleAsync(current);
        }
    }

    /** SSE 流关闭回调(client 线程):摘除对应句柄,下次 acquire 触发重建。 */
    private synchronized void onStreamClosed(ServeHandle source) {
        if (handle == source) {
            handle = null;
            LOG.info("[OpenCodeServeManager] serve SSE stream closed, handle dropped (next acquire rebuilds)");
        }
    }

    /** 项目关闭:同步快速清理(异步关闭在 IDE 退出时无法保证执行,会留孤儿进程)。 */
    @Override
    public synchronized void dispose() {
        disposed = true;
        ServeHandle current = handle;
        handle = null;
        if (current != null) {
            closeHandle(current);
        }
    }

    // ── spawn / 指纹 ──────────────────────────────────────────────────────────

    private McpGatewayCliConfig buildGatewayConfig(String tabId, String cwd) {
        try {
            return McpGatewayService.getInstance(project)
                    .buildCliConfig(ProviderType.OPENCODE, tabId, cwd);
        } catch (Exception e) {
            LOG.warn("[OpenCodeServeManager] gateway config unavailable: " + e.getMessage());
            return McpGatewayCliConfig.disabled("MCP Gateway service unavailable: " + e.getMessage());
        }
    }

    /**
     * 指纹 = CLI 版本 + gateway endpoint + gateway 注入 env 内容(config + token)。
     * 只用于相等性比较,不打日志(env 内含 token)。
     */
    private String computeFingerprint(McpGatewayCliConfig gatewayConfig) {
        String version = ProviderCliResolver.getCachedVersion(ProviderType.OPENCODE);
        String endpoint = gatewayConfig != null && gatewayConfig.usable() ? gatewayConfig.endpoint() : "(no-gateway)";
        String envContent = gatewayConfig != null && gatewayConfig.usable()
                ? gatewayConfig.environment().toString() : "";
        return (version != null ? version : "(unknown)") + "|" + endpoint + "|" + envContent;
    }

    private ServeHandle spawn(String cwd, Map<String, String> extraEnv,
                              McpGatewayCliConfig gatewayConfig, String fingerprint) {
        ProviderCliResolver resolver =
                new ProviderCliResolver(ProviderType.OPENCODE, CliConstants.OPENCODE_NPM_DIR);
        String executable = resolver.findExecutable();
        int port;
        try {
            port = findFreeLoopbackPort();
        } catch (Exception e) {
            LOG.warn("[OpenCodeServeManager] no free loopback port: " + e.getMessage());
            return null;
        }
        List<String> cmd = List.of(executable,
                CliConstants.OPENCODE_ARG_SERVE,
                CliConstants.OPENCODE_ARG_PORT, String.valueOf(port),
                CliConstants.OPENCODE_ARG_HOSTNAME, CliConstants.OPENCODE_SERVE_HOSTNAME);

        Process process;
        String processToken;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.clear();
            env.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
            env.put(CliConstants.ARG_NO_COLOR, "1");
            CliEnvironmentBuilder.applyExtraEnv(env, extraEnv);
            if (gatewayConfig != null && gatewayConfig.usable()) {
                env.putAll(gatewayConfig.environment());
            }
            pb.directory(resolveSpawnCwd(cwd));
            // serve 不读 stdin;重定向空设备(对齐 one-shot 的可靠 EOF 语义)
            pb.redirectInput(ProcessBuilder.Redirect.from(
                    new File(PlatformUtils.isWindows() ? "NUL" : "/dev/null")));
            process = pb.start();
        } catch (Exception e) {
            LOG.warn("[OpenCodeServeManager] failed to spawn opencode serve", e);
            return null;
        }
        processToken = NodeService.getInstance().getProcessManager().registerAuxiliaryProcess(process);
        if (processToken == null) {
            terminateProcessQuietly(process);
            return null;
        }
        recordLifecycle(LifecycleEventType.SPAWN, process, "opencode serve spawned on port " + port);
        Thread drain = startStdoutDrain(process);

        if (!awaitReady(process, port)) {
            LOG.warn("[OpenCodeServeManager] opencode serve not ready within "
                    + SERVE_READY_TIMEOUT_MS + "ms (alive=" + process.isAlive() + ")");
            NodeService.getInstance().getProcessManager().unregisterAuxiliaryProcess(processToken, process);
            terminateProcessQuietly(process);
            drain.interrupt();
            return null;
        }

        OpenCodeServeClient client =
                new OpenCodeServeClient("http://" + CliConstants.OPENCODE_SERVE_HOSTNAME + ":" + port);
        ServeHandle created = new ServeHandle(process, processToken, client, fingerprint);
        client.setStreamClosedListener(() -> onStreamClosed(created));
        client.startEventStream();
        LOG.info("[OpenCodeServeManager] opencode serve ready: port=" + port + ", pid=" + process.pid());
        return created;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /** spawn cwd:请求目录有效则用之,否则回退 home(对称 one-shot 的 cwd null→home 回退)。 */
    private File resolveSpawnCwd(String cwd) {
        if (cwd != null && !cwd.isBlank()) {
            File dir = new File(cwd);
            if (dir.isDirectory()) {
                return dir;
            }
            LOG.warn("[OpenCodeServeManager] spawn cwd does not exist, falling back to home: " + cwd);
        }
        return new File(PlatformUtils.getHomeDirectory());
    }

    /** 就绪判定:轮询 TCP 回环连接(不依赖 serve 具体 API 路径);进程速死立即失败。 */
    private boolean awaitReady(Process process, int port) {
        long deadline = System.currentTimeMillis() + SERVE_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(CliConstants.OPENCODE_SERVE_HOSTNAME, port), 200);
                return true;
            } catch (Exception ignored) {
                // 未就绪,继续轮询
            }
            try {
                Thread.sleep(SERVE_READY_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** serve stdout 永久 drain 线程(防管道满阻塞,对称 one-shot drain;内容仅 debug 日志)。 */
    private Thread startStdoutDrain(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debug("[OpenCodeServeManager] serve stdout: " + line);
                }
            } catch (Exception ignored) {
                // 进程退出/流关闭即结束
            }
            recordLifecycle(LifecycleEventType.STDOUT_EOF, process, "opencode serve stdout EOF");
        }, "AICG-OpenCode-Serve-Drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 重建 / terminateServe 路径的异步 teardown:句柄已摘除(与后续 spawn 无竞争),
     * 旧句柄关闭(client.close → terminateProcess → waitFor 最长 PROCESS_WAIT_TIMEOUT_MS)
     * 提交平台共享执行器(对齐同仓 AppExecutorUtil 异步清理惯例),调用线程
     * (acquire 派发线程 / interrupt 兜底调度线程)不阻塞。dispose 保持同步,
     * 保证 IDE 退出前资源回收。
     */
    private void closeHandleAsync(ServeHandle target) {
        try {
            AppExecutorUtil.getAppExecutorService().execute(() -> closeHandle(target));
        } catch (RuntimeException e) {
            // 执行器不可用(IDE 关停中):尽力同步收尾,防孤儿进程
            closeHandle(target);
        }
    }

    private void closeHandle(ServeHandle target) {
        try {
            target.client().close();
        } catch (Exception ignored) {
            // 关闭路径异常尽数吞掉
        }
        Process process = target.process();
        if (process.isAlive()) {
            PlatformUtils.terminateProcess(process);
            recordLifecycle(LifecycleEventType.TERMINATE, process, "opencode serve terminated");
            try {
                process.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        NodeService.getInstance().getProcessManager()
                .unregisterAuxiliaryProcess(target.processToken(), process);
    }

    private void recordLifecycle(LifecycleEventType type, Process process, String detail) {
        if (lifecycleService == null) {
            return;
        }
        lifecycleService.record(type,
                lifecycleService.metadata(LifecycleProcessKind.CLI_PERSISTENT, null, null, null),
                process != null ? process.pid() : -1L, detail);
    }

    private static int findFreeLoopbackPort() throws Exception {
        try (ServerSocket socket =
                     new ServerSocket(0, 50, InetAddress.getByName(CliConstants.OPENCODE_SERVE_HOSTNAME))) {
            return socket.getLocalPort();
        }
    }

    private static void terminateProcessQuietly(Process process) {
        try {
            PlatformUtils.terminateProcess(process);
        } catch (Exception ignored) {
            // 尽力而为
        }
    }
}
