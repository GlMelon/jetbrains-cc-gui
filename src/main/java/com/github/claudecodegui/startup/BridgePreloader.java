package com.github.claudecodegui.startup;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.github.claudecodegui.mcp.McpGatewayFeatureFlags;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Pre-loads the AI Bridge on project startup to avoid EDT freeze
 * when opening the tool window for the first time.
 *
 * This activity runs in the background after the project is opened,
 * triggering the ai-bridge.zip extraction early so it's ready
 * when the user opens the Claude tool window.
 */
public class BridgePreloader implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(BridgePreloader.class);

    // Shared resolver instance for preloading
    private static volatile BridgeDirectoryResolver sharedResolver;
    private static final Object RESOLVER_LOCK = new Object();

    /**
     * Get or create the shared resolver instance.
     * This ensures extraction only happens once across all components.
     */
    public static BridgeDirectoryResolver getSharedResolver() {
        if (sharedResolver == null) {
            synchronized (RESOLVER_LOCK) {
                if (sharedResolver == null) {
                    sharedResolver = new BridgeDirectoryResolver();
                }
            }
        }
        return sharedResolver;
    }

    /**
     * Check if bridge extraction is complete (non-blocking).
     * Returns true if bridge is ready, false if still extracting or not started.
     */
    public static boolean isBridgeReady() {
        BridgeDirectoryResolver resolver = getSharedResolver();
        return resolver.isExtractionComplete();
    }

    /**
     * Get a future that completes when extraction is done.
     * This allows callers to wait asynchronously without blocking EDT.
     */
    public static CompletableFuture<Boolean> waitForBridgeAsync() {
        BridgeDirectoryResolver resolver = getSharedResolver();
        return resolver.getExtractionFuture();
    }

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (project.isDisposed()) {
            return Unit.INSTANCE;
        }
        LOG.info("[BridgePreloader] Starting bridge preload for project: " + project.getName());

        Future<?> preloadFuture = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CompletableFuture<Void> cliPrewarm = null;
            try {
                if (shouldStopPreload(project)) {
                    return;
                }
                BridgeDirectoryResolver resolver = getSharedResolver();

                // CLI resolver 缓存是全局资源,但外层任务持有 Project。项目关闭后停止此轮预热,
                // 避免 project activity 在 dispose 后继续访问 project service。
                cliPrewarm = CompletableFuture.runAsync(BridgePreloader::prewarmCliResolvers);
                if (shouldStopPreload(project)) {
                    return;
                }

                resolver.findBridgeDir();
                if (shouldStopPreload(project)) {
                    return;
                }

                prewarmMcpGateway(project);
                if (shouldStopPreload(project)) {
                    return;
                }

                cliPrewarm.join();
                if (!shouldStopPreload(project)) {
                    LOG.info("[BridgePreloader] Bridge preload completed for project: " + project.getName());
                }
            } catch (Exception e) {
                if (!shouldStopPreload(project)) {
                    LOG.warn("[BridgePreloader] Bridge preload failed: " + e.getMessage(), e);
                }
            } finally {
                if (cliPrewarm != null && shouldStopPreload(project)) {
                    cliPrewarm.cancel(true);
                }
            }
        });
        if (!Disposer.tryRegister(project, () -> preloadFuture.cancel(true))) {
            preloadFuture.cancel(true);
        }
        return Unit.INSTANCE;
    }

    private static boolean shouldStopPreload(@NotNull Project project) {
        return project.isDisposed() || Thread.currentThread().isInterrupted();
    }

    /**
     * 项目打开时后台预热 MCP Gateway。在 ai-bridge 解压完成后的同一个 pooled 线程内执行,
     * 不阻塞 EDT;{@link McpGatewayService#refreshConfig} 内部会 ensureStarted(最多等一个
     * cold-start)+ applySnapshot(同步等各 MCP server initialize/listTools,首次较慢但用户无感)。
     * {@code isGatewayActive} 守卫:gateway 整体禁用时 no-op,避免空跑。
     */
    private static void prewarmMcpGateway(@NotNull Project project) {
        if (shouldStopPreload(project) || !McpGatewayFeatureFlags.isGatewayActive()) {
            return;
        }
        try {
            McpGatewayService gatewayService = McpGatewayService.getInstance(project);
            if (shouldStopPreload(project)) {
                return;
            }
            gatewayService.refreshConfig(project.getBasePath());
            if (!shouldStopPreload(project)) {
                LOG.info("[BridgePreloader] MCP Gateway prewarmed for project: " + project.getName());
            }
        } catch (Exception e) {
            if (!shouldStopPreload(project)) {
                LOG.warn("[BridgePreloader] MCP Gateway prewarm failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 项目打开时后台并行预热 codex/opencode/kimi/grok/pi CLI resolver 缓存。各家 {@code findExecutable()}
     * 首次会 spawn {@code <cli> --version} 子进程验证可执行性 + 取版本(经 .cmd 包装冷启动 ~3s,
     * ProviderCliResolver 还会 shim 探测 + 候选验证各 spawn 一次 ≈ 2~6s),未预热时这段时间落在
     * 用户首条消息的同步 send 路径。预热后首条消息命中 {@code cachedExecutable} 秒回。
     * <p>kimi 额外依赖版本缓存做 ACP 通道门禁({@code KimiAcpChannelGate}:版本未缓存即回退
     * legacy stream-json,无思考区/非流式),预热使其首条消息即可正确进入 ACP 通道。
     * <p>claude 不预热:detector 冷启动仅 ~227ms(已够快),且其 {@code detectionAttempted}
     * 失败永久置位有"预热失败致永久不可用"风险,收益不足风险故不预热。
     * <p>resolver 只缓存成功路径(不缓存失败),故预热失败无副作用——首条消息时正常重试检测;
     * 未安装的 provider 探测失败即静默跳过(仅一次后台子进程开销)。
     */
    private static void prewarmCliResolvers() {
        CompletableFuture.allOf(
                CompletableFuture.runAsync(BridgePreloader::prewarmCodexCli),
                CompletableFuture.runAsync(BridgePreloader::prewarmOpenCodeCli),
                CompletableFuture.runAsync(BridgePreloader::prewarmKimiCli),
                CompletableFuture.runAsync(BridgePreloader::prewarmGrokCli),
                CompletableFuture.runAsync(BridgePreloader::prewarmPiCli)
        ).join();
    }

    private static void prewarmCodexCli() {
        try {
            String path = CodexCliResolver.findExecutable();
            LOG.info("[BridgePreloader] Codex CLI resolver prewarmed: " + (path != null ? path : "(not found)"));
        } catch (Exception e) {
            LOG.warn("[BridgePreloader] Codex CLI resolver prewarm failed: " + e.getMessage(), e);
        }
    }

    private static void prewarmOpenCodeCli() {
        try {
            String path = OpenCodeCliResolver.findExecutable();
            LOG.info("[BridgePreloader] OpenCode CLI resolver prewarmed: " + (path != null ? path : "(not found)"));
        } catch (Exception e) {
            LOG.warn("[BridgePreloader] OpenCode CLI resolver prewarm failed: " + e.getMessage(), e);
        }
    }

    /** kimi 预热:findExecutable 顺带填充版本缓存(KimiAcpChannelGate 门禁依赖,见 prewarmCliResolvers)。 */
    private static void prewarmKimiCli() {
        try {
            String path = new ProviderCliResolver(ProviderType.KIMI, "kimi").findExecutable();
            LOG.info("[BridgePreloader] Kimi CLI resolver prewarmed: " + (path != null ? path : "(not found)"));
        } catch (Exception e) {
            LOG.warn("[BridgePreloader] Kimi CLI resolver prewarm failed: " + e.getMessage(), e);
        }
    }

    /** grok 预热:npmDir 裸名(见 AbstractRunOnceCliSession.npmDir 默认值),见 prewarmCliResolvers。 */
    private static void prewarmGrokCli() {
        try {
            String path = new ProviderCliResolver(ProviderType.GROK, "grok").findExecutable();
            LOG.info("[BridgePreloader] Grok CLI resolver prewarmed: " + (path != null ? path : "(not found)"));
        } catch (Exception e) {
            LOG.warn("[BridgePreloader] Grok CLI resolver prewarm failed: " + e.getMessage(), e);
        }
    }

    /** pi 预热:npmDir 裸名(见 AbstractRunOnceCliSession.npmDir 默认值),见 prewarmCliResolvers。 */
    private static void prewarmPiCli() {
        try {
            String path = new ProviderCliResolver(ProviderType.PI, "pi").findExecutable();
            LOG.info("[BridgePreloader] Pi CLI resolver prewarmed: " + (path != null ? path : "(not found)"));
        } catch (Exception e) {
            LOG.warn("[BridgePreloader] Pi CLI resolver prewarm failed: " + e.getMessage(), e);
        }
    }
}
