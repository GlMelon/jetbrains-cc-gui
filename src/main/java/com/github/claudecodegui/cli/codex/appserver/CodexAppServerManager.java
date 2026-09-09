package com.github.claudecodegui.cli.codex.appserver;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.codex.CodexCliCommandUtils;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliEnvironmentBuilder;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleEventType;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.service.lifecycle.LifecycleProcessKind;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * codex app-server 长驻进程管理器(每 project 一个 app-server 进程,MVP)。
 * <p>
 * {@code codex app-server [-c gateway 覆盖...]} stdio JSON-RPC 服务,一个进程经
 * {@code thread/start} 服务多 tab 多线程(thread 存盘于 CODEX_HOME,跨进程经
 * {@code thread/resume} 续接)。app-server 是 codex 官方唯一带 token 级增量的编程接口
 * ({@code item/agentMessage/delta}、{@code item/reasoning/*Delta});exec --json 无 delta,
 * agent_message 仅在 item.completed 整段到达(2026-09-08 实测,66s 黑盒根因)。
 * <p>
 * 防失控(总则六,逐项对称 {@code OpenCodeServeManager}):
 * <ul>
 *   <li>进程注册 {@link com.github.claudecodegui.bridge.ProcessManager}(项目关闭可确定性终止进程树);</li>
 *   <li>指纹 = CLI 版本 + gateway endpoint + gateway 注入 env 内容 + gateway -c 覆盖参数;
 *       指纹漂移 / 进程崩溃 / stdout EOF → 摘除旧句柄重建,当轮由调用方降级 one-shot
 *       (对齐 acquire 未命中的静默降级);</li>
 *   <li>连续 spawn 失败 {@value #MAX_CONSECUTIVE_SPAWN_FAILURES} 次熔断,本次运行内不再尝试
 *       (全走 one-shot),防坏环境无限重启;</li>
 *   <li>重建 / terminateServe 的旧句柄 teardown 异步化(平台共享执行器);dispose 同步优雅关闭
 *       (client.close → terminateProcess 兜底)。</li>
 * </ul>
 * MVP 显式简化(有意差异,勿视为遗漏,对齐 opencode serve):请求级 extraEnv 仅在 spawn 时注入
 * 一次,不进指纹——app-server 为 project 级共享进程,per-request env 漂移不触发重建。
 */
@Service(Service.Level.PROJECT)
public final class CodexAppServerManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(CodexAppServerManager.class);

    /** 连续 spawn 失败熔断上限(对齐 KimiAcpWarmPool / OpenCodeServeManager)。 */
    private static final int MAX_CONSECUTIVE_SPAWN_FAILURES = 2;

    /** app-server 句柄:进程 + ProcessManager 令牌 + stdio JSON-RPC 客户端 + spawn 时指纹。 */
    private record AppServerHandle(
            Process process,
            String processToken,
            CodexAppServerClient client,
            String fingerprint
    ) {
    }

    private final Project project;
    private final LifecycleObservabilityService lifecycleService;
    /** 当前句柄(全部访问在 synchronized 块内)。 */
    private AppServerHandle handle;
    /** 进行中的 spawn(全部访问在 synchronized 块内):并发 acquire 共享同一 future,spawn 在锁外执行。 */
    private CompletableFuture<AppServerHandle> spawnFuture;
    /** teardown 代际(全部访问在 synchronized 块内):terminate / EOF / dispose 时递增。 */
    private int teardownGeneration;
    private int consecutiveSpawnFailures;
    private volatile boolean disposed;

    public CodexAppServerManager(@NotNull Project project) {
        this(project, LifecycleObservabilityService.getInstance(project));
    }

    /** 测试友好构造:生命周期观测可空。 */
    CodexAppServerManager(@NotNull Project project, @Nullable LifecycleObservabilityService lifecycleService) {
        this.project = project;
        this.lifecycleService = lifecycleService;
    }

    public static CodexAppServerManager getInstance(@NotNull Project project) {
        return project.getService(CodexAppServerManager.class);
    }

    /** app-server 所属 project(权限对话框路由等场景使用)。 */
    public @NotNull Project project() {
        return project;
    }

    /**
     * 获取可用 app-server 客户端。命中(进程存活且指纹匹配)直接返回;未命中(首次 / 指纹漂移 /
     * 崩溃 / EOF 后)重建一次;重建失败或熔断中返回 null——调用方当轮降级 one-shot。
     * 锁外 spawn 对称 OpenCodeServeManager(spawnFuture 共享 + join 锁外)。
     */
    public @Nullable CodexAppServerClient acquire(String tabId, String cwd,
                                                  Map<String, String> extraEnv) {
        McpGatewayCliConfig gatewayConfig = buildGatewayConfig(tabId, cwd);
        String fingerprint = computeFingerprint(gatewayConfig);
        CompletableFuture<AppServerHandle> future;
        synchronized (this) {
            if (disposed) {
                return null;
            }
            if (consecutiveSpawnFailures >= MAX_CONSECUTIVE_SPAWN_FAILURES) {
                return null;
            }
            AppServerHandle existing = handle;
            if (existing != null) {
                if (existing.process().isAlive() && existing.fingerprint().equals(fingerprint)) {
                    return existing.client();
                }
                LOG.info("[CodexAppServerManager] app-server handle stale (dead or fingerprint drift), rebuilding: tab=" + tabId);
                handle = null;
                closeHandleAsync(existing);
            }
            if (spawnFuture == null) {
                final int generation = teardownGeneration;
                CompletableFuture<AppServerHandle> created = CompletableFuture
                        .supplyAsync(() -> spawn(cwd, extraEnv, gatewayConfig, fingerprint),
                                AppExecutorUtil.getAppExecutorService())
                        .exceptionally(e -> {
                            LOG.warn("[CodexAppServerManager] app-server spawn failed unexpectedly", e);
                            return null;
                        });
                spawnFuture = created;
                created.whenComplete((spawned, ignored) -> installSpawned(created, spawned, generation));
            }
            future = spawnFuture;
        }
        future.join();
        synchronized (this) {
            AppServerHandle current = handle;
            if (current != null && current.process().isAlive() && current.fingerprint().equals(fingerprint)) {
                return current.client();
            }
            return null;
        }
    }

    /** spawn 完成回调(锁内):失败 → 熔断计数;disposed / 进程已死 / 代际漂移 → 异步关闭。 */
    private void installSpawned(CompletableFuture<AppServerHandle> source, AppServerHandle spawned, int generation) {
        synchronized (this) {
            if (spawnFuture == source) {
                spawnFuture = null;
            }
            if (spawned == null) {
                consecutiveSpawnFailures++;
                if (consecutiveSpawnFailures >= MAX_CONSECUTIVE_SPAWN_FAILURES) {
                    LOG.warn("[CodexAppServerManager] app-server spawn circuit breaker opened (consecutiveFailures="
                            + consecutiveSpawnFailures + "), one-shot for the rest of this run");
                }
                return;
            }
            if (disposed || generation != teardownGeneration
                    || !spawned.process().isAlive() || handle != null) {
                closeHandleAsync(spawned);
                return;
            }
            consecutiveSpawnFailures = 0;
            handle = spawned;
        }
    }

    /**
     * 强杀当前 app-server(interrupt 兜底:turn/interrupt 超时无回应 / 轮挂死)。句柄摘除,
     * 下次 acquire 重建;进行中的轮由 client 的 EOF 通知收尾。
     */
    public synchronized void terminateAppServer(String reason) {
        AppServerHandle current = handle;
        handle = null;
        teardownGeneration++;
        if (current != null) {
            LOG.warn("[CodexAppServerManager] terminating app-server: " + reason);
            closeHandleAsync(current);
        }
    }

    /** stdout EOF 回调(client 线程):摘除对应句柄,下次 acquire 触发重建。 */
    private synchronized void onProcessDied(AppServerHandle source) {
        if (handle == source) {
            handle = null;
            teardownGeneration++;
            LOG.info("[CodexAppServerManager] app-server stdout EOF, handle dropped (next acquire rebuilds)");
        }
    }

    /** 项目关闭:同步快速清理(异步关闭在 IDE 退出时无法保证执行,会留孤儿进程)。 */
    @Override
    public synchronized void dispose() {
        disposed = true;
        teardownGeneration++;
        AppServerHandle current = handle;
        handle = null;
        if (current != null) {
            closeHandle(current);
        }
    }

    // ── spawn / 指纹 ──────────────────────────────────────────────────────────

    private McpGatewayCliConfig buildGatewayConfig(String tabId, String cwd) {
        try {
            return McpGatewayService.getInstance(project)
                    .buildCliConfig(ProviderType.CODEX, tabId, cwd);
        } catch (Exception e) {
            LOG.warn("[CodexAppServerManager] gateway config unavailable: " + e.getMessage());
            return McpGatewayCliConfig.disabled("MCP Gateway service unavailable: " + e.getMessage());
        }
    }

    /**
     * 指纹 = CLI 版本 + gateway endpoint + gateway 注入 env 内容 + gateway -c 覆盖参数
     * (覆盖参数内嵌 endpoint;token 不在 argv)。只用于相等性比较,不打日志(env 内含 token)。
     */
    private String computeFingerprint(McpGatewayCliConfig gatewayConfig) {
        String version = CodexCliResolver.getCachedVersion();
        boolean usable = gatewayConfig != null && gatewayConfig.usable();
        String endpoint = usable ? gatewayConfig.endpoint() : "(no-gateway)";
        String envContent = usable ? gatewayConfig.environment().toString() : "";
        String overrideArgs = usable ? String.join(",", gatewayConfig.overrideArgs()) : "";
        return (version != null ? version : "(unknown)") + "|" + endpoint + "|" + envContent + "|" + overrideArgs;
    }

    private AppServerHandle spawn(String cwd, Map<String, String> extraEnv,
                                  McpGatewayCliConfig gatewayConfig, String fingerprint) {
        String executable = CodexCliResolver.findExecutable();
        List<String> cmd = new ArrayList<>();
        CodexCliCommandUtils.addCodexExecutable(cmd, executable);
        cmd.add(CliConstants.CODEX_ARG_APP_SERVER);
        // gateway 注入:-c 命令行覆盖(melon_gateway 定义 + 逐个禁真实 server),
        // app-server 子命令支持 -c,argv 直传与 exec 路径同源。
        if (gatewayConfig != null && gatewayConfig.usable()) {
            cmd.addAll(gatewayConfig.overrideArgs());
        }

        Process process;
        String processToken;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // stdout 是 JSON-RPC 协议通道(client 读);stderr 单独 drain,不可合流
            pb.redirectErrorStream(false);
            Map<String, String> env = pb.environment();
            env.clear();
            env.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
            env.putAll(CliSettings.readCodexCliEnvironment());
            env.put(CliConstants.ARG_NO_COLOR, "1");
            CliEnvironmentBuilder.configureProjectPath(env, cwd);
            // CLI mode 必须可达真实 Codex API(对称 one-shot:移除宿主残留的禁网标记)
            env.remove(CliConstants.ENV_CODEX_SANDBOX_NETWORK_DISABLED);
            CliEnvironmentBuilder.applyExtraEnv(env, CodexCliCommandUtils.sanitizeEnv(extraEnv));
            if (gatewayConfig != null && gatewayConfig.usable()) {
                env.putAll(gatewayConfig.environment());
            }
            pb.directory(resolveSpawnCwd(cwd));
            process = pb.start();
        } catch (Exception e) {
            LOG.warn("[CodexAppServerManager] failed to spawn codex app-server", e);
            return null;
        }
        processToken = NodeService.getInstance().getProcessManager().registerAuxiliaryProcess(process);
        if (processToken == null) {
            terminateProcessQuietly(process);
            return null;
        }
        recordLifecycle(LifecycleEventType.SPAWN, process, "codex app-server spawned");
        startStderrDrain(process);

        // 就绪 = initialize 握手成功(stdout JSON-RPC 通道已建立);进程速死立即失败
        CodexAppServerClient client = new CodexAppServerClient(process);
        if (!awaitInitialize(client, process)) {
            LOG.warn("[CodexAppServerManager] codex app-server initialize failed within "
                    + CliConstants.CODEX_APPSERVER_READY_TIMEOUT_MS + "ms (alive=" + process.isAlive() + ")");
            client.close();
            NodeService.getInstance().getProcessManager().unregisterAuxiliaryProcess(processToken, process);
            terminateProcessQuietly(process);
            return null;
        }
        AppServerHandle created = new AppServerHandle(process, processToken, client, fingerprint);
        client.setProcessDiedListener(() -> onProcessDied(created));
        LOG.info("[CodexAppServerManager] codex app-server ready: pid=" + process.pid());
        return created;
    }

    /** initialize → initialized 握手(就绪判定,对称 opencode serve 的 TCP 就绪轮询)。 */
    private boolean awaitInitialize(CodexAppServerClient client, Process process) {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "claude-code-gui");
        clientInfo.addProperty("title", "Claude Code GUI");
        clientInfo.addProperty("version", "1.0.0");
        JsonObject params = new JsonObject();
        params.add("clientInfo", clientInfo);
        try {
            client.request(CliConstants.CODEX_APPSERVER_METHOD_INITIALIZE, params)
                    .get(CliConstants.CODEX_APPSERVER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (Exception e) {
            // 进程速死 / 协议错误 / 执行器中断:一律视为未就绪
            LOG.warn("[CodexAppServerManager] initialize handshake failed: " + e.getMessage());
            return false;
        }
        client.notify(CliConstants.CODEX_APPSERVER_METHOD_INITIALIZED, null);
        return true;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    /** spawn cwd:请求目录有效则用之,否则回退 home(对称 one-shot 的 cwd null→home 回退)。 */
    private File resolveSpawnCwd(String cwd) {
        if (cwd != null && !cwd.isBlank()) {
            File dir = new File(cwd);
            if (dir.isDirectory()) {
                return dir;
            }
            LOG.warn("[CodexAppServerManager] spawn cwd does not exist, falling back to home: " + cwd);
        }
        return new File(PlatformUtils.getHomeDirectory());
    }

    /** app-server stderr 永久 drain 线程(防管道满阻塞;内容仅 debug 日志)。 */
    private Thread startStderrDrain(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debug("[CodexAppServerManager] app-server stderr: " + line);
                }
            } catch (Exception ignored) {
                // 进程退出/流关闭即结束
            }
            recordLifecycle(LifecycleEventType.STDOUT_EOF, process, "codex app-server stderr EOF");
        }, "AICG-Codex-AppServer-Stderr-Drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 重建 / terminate 路径的异步 teardown(对称 OpenCodeServeManager.closeHandleAsync)。 */
    private void closeHandleAsync(AppServerHandle target) {
        try {
            AppExecutorUtil.getAppExecutorService().execute(() -> closeHandle(target));
        } catch (RuntimeException e) {
            // 执行器不可用(IDE 关停中):尽力同步收尾,防孤儿进程
            closeHandle(target);
        }
    }

    private void closeHandle(AppServerHandle target) {
        try {
            target.client().close();
        } catch (Exception ignored) {
            // 关闭路径异常尽数吞掉
        }
        Process process = target.process();
        if (process.isAlive()) {
            PlatformUtils.terminateProcess(process);
            recordLifecycle(LifecycleEventType.TERMINATE, process, "codex app-server terminated");
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

    private static void terminateProcessQuietly(Process process) {
        try {
            PlatformUtils.terminateProcess(process);
        } catch (Exception ignored) {
            // 尽力而为
        }
    }
}
