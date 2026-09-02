package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.common.CliProcessHandle;
import com.github.claudecodegui.cli.common.CliProcessLifecycle;
import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Kimi ACP 暖连接池(每项目容量 1):启动预热时提前 spawn {@code kimi acp} 并完成
 * initialize 握手后停泊;tab 首发消息经 {@link #take()} 取走直接 session/new,
 * 把 node 冷启 + 握手移出发送链(0.38.0 实测 spawn+initialize ≈0.7-1s,IDE 首发
 * 场景受进程环境/调度影响可放大到秒级)。
 *
 * <p><b>只暖到「已 initialize、未建 session」</b>:session/new 绑定当轮 cwd,且预建
 * 会在用户 kimi 历史里留下空会话;session/new(当前 mcpServers 为空)实测仅 ~0.2s,
 * 不值得预建。暖连接未建 session,resume 会话也可在其上 session/load,故 {@link #take()}
 * 无需按 sessionId/cwd 过滤。</p>
 *
 * <p><b>防失控(总则六)</b>:进程注册 {@link com.github.claudecodegui.bridge.ProcessManager}
 * (项目关闭可确定性终止进程树);取走后所有权移交 tab 会话(成为其 persistentConn,
 * 由会话生命周期管理,池不再触碰);未取走的停泊连接 {@value #IDLE_CLOSE_MS} 后自动关闭;
 * 项目 dispose 时关闭。池取空不自动补暖(下次启动预热再补),避免失控重建。</p>
 */
@Service(Service.Level.PROJECT)
public final class KimiAcpWarmPool implements Disposable {

    private static final Logger LOG = Logger.getInstance(KimiAcpWarmPool.class);
    /** 停泊连接的空闲存活上限:超时未取走则关闭,避免无用长驻进程。 */
    private static final long IDLE_CLOSE_MS = 10 * 60_000L;

    private final Project project;
    private final AtomicReference<WarmConnection> parked = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> idleCloseTask = new AtomicReference<>();

    public KimiAcpWarmPool(@NotNull Project project) {
        this.project = project;
    }

    public static KimiAcpWarmPool getInstance(@NotNull Project project) {
        return project.getService(KimiAcpWarmPool.class);
    }

    /**
     * 预热:spawn + initialize 后停泊。幂等——已有存活停泊连接时直接返回。
     * 任何失败都只记日志(首发走冷启动/legacy,行为不变),绝不抛穿预热链。
     */
    public void warm(@Nullable BooleanSupplier cancelled) {
        WarmConnection existing = parked.get();
        if (existing != null) {
            if (existing.connection().isAlive()) {
                return;
            }
            if (parked.compareAndSet(existing, null)) {
                existing.closeQuietly();
            }
        }
        String executable = new ProviderCliResolver(ProviderType.KIMI, ProviderType.KIMI.cliCommand())
                .findExecutable();
        if (executable == null || executable.isBlank() || isCancelled(cancelled)) {
            return;
        }
        McpGatewayCliConfig gatewayConfig = null;
        try {
            gatewayConfig = McpGatewayService.getInstance(project)
                    .buildCliConfig(ProviderType.KIMI, "prewarm", project.getBasePath());
        } catch (Exception e) {
            // gateway 配置失败不阻塞预热(发送链会再构建;当前 kimi mcpServers 为空,env 无差异)
            LOG.warn("[KimiAcpWarmPool] gateway config unavailable during warm-up: " + e.getMessage());
        }
        if (isCancelled(cancelled) || project.isDisposed()) {
            return;
        }
        Process process;
        String processToken;
        try {
            ProcessBuilder pb = KimiAcpCliSession.buildAcpProcessBuilder(
                    executable, gatewayConfig, project.getBasePath(), null);
            process = pb.start();
            processToken = NodeService.getInstance().getProcessManager().registerAuxiliaryProcess(process);
            if (processToken == null) {
                CliProcessLifecycle.terminate(process);
                return;
            }
        } catch (Exception e) {
            LOG.warn("[KimiAcpWarmPool] failed to spawn kimi acp for warm-up: " + e.getMessage());
            return;
        }
        KimiAcpConnection conn = new KimiAcpConnection(process, null, (method, params) -> null, null);
        try {
            conn.start();
            conn.request(KimiAcpProtocol.METHOD_INITIALIZE, KimiAcpCliSession.buildInitializeParams(),
                    KimiAcpCliSession.HANDSHAKE_TIMEOUT_MS);
        } catch (Exception e) {
            LOG.warn("[KimiAcpWarmPool] warm-up initialize failed: " + e.getMessage());
            closeSpawned(conn, processToken, process);
            return;
        }
        if (isCancelled(cancelled) || project.isDisposed()) {
            closeSpawned(conn, processToken, process);
            return;
        }
        WarmConnection warm = new WarmConnection(conn,
                new CliProcessHandle(process, "kimi-acp-warm-pool"), processToken);
        if (!parked.compareAndSet(null, warm)) {
            // 并发 take/warm 竞态:泊位非空,关闭本次暖连接
            closeSpawned(conn, processToken, process);
            return;
        }
        scheduleIdleClose();
        LOG.info("[KimiAcpWarmPool] ACP warm connection parked (initialized, sessionless)");
    }

    /**
     * 取走停泊连接(所有权移交调用方,此后进程生命周期由调用方管理)。
     * 无停泊或连接已死 → 关闭并返回 null(调用方走冷启动)。
     */
    @Nullable
    public WarmConnection take() {
        WarmConnection warm = parked.getAndSet(null);
        cancelIdleClose();
        if (warm == null) {
            return null;
        }
        if (!warm.connection().isAlive()) {
            warm.closeQuietly();
            return null;
        }
        LOG.debug("[KimiAcpWarmPool] warm connection taken");
        return warm;
    }

    @Override
    public void dispose() {
        cancelIdleClose();
        WarmConnection warm = parked.getAndSet(null);
        if (warm != null) {
            warm.closeQuietly();
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private void scheduleIdleClose() {
        ScheduledFuture<?> future = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(() -> {
                    WarmConnection warm = parked.getAndSet(null);
                    if (warm != null) {
                        LOG.info("[KimiAcpWarmPool] idle warm connection closed");
                        warm.closeQuietly();
                    }
                }, IDLE_CLOSE_MS, TimeUnit.MILLISECONDS);
        idleCloseTask.set(future);
    }

    private void cancelIdleClose() {
        ScheduledFuture<?> future = idleCloseTask.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static void closeSpawned(KimiAcpConnection conn, String processToken, Process process) {
        try {
            conn.close();
        } catch (Exception ignored) {
            // 关闭路径异常尽数吞掉,不污染预热主流程
        }
        NodeService.getInstance().getProcessManager().unregisterAuxiliaryProcess(processToken, process);
    }

    private static boolean isCancelled(@Nullable BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean());
    }

    /**
     * 暖连接句柄:连接 + 进程句柄 + ProcessManager 注册令牌。
     * {@link #take()} 后所有权归调用方;池侧仅对未取走的连接调用 {@link #closeQuietly()}。
     */
    public record WarmConnection(KimiAcpConnection connection, CliProcessHandle handle, String processToken) {

        void closeQuietly() {
            try {
                connection.close();
            } catch (Exception ignored) {
                // 关闭路径异常尽数吞掉
            }
            if (processToken != null) {
                NodeService.getInstance().getProcessManager()
                        .unregisterAuxiliaryProcess(processToken, handle.process());
            }
        }
    }
}
