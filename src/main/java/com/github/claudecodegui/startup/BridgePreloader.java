package com.github.claudecodegui.startup;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.github.claudecodegui.mcp.McpGatewayFeatureFlags;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

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
        LOG.info("[BridgePreloader] Starting bridge preload for project: " + project.getName());

        // Run extraction on a background thread
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                BridgeDirectoryResolver resolver = getSharedResolver();

                // CLI detector 预热不依赖 ai-bridge 解压,立即并行启动:codex/opencode 的 findExecutable()
                // 首次会 spawn '<cli> --version' 子进程(.cmd 包装冷启动 ~3s),未预热时这 3s 落在用户
                // 首条消息的同步 send 路径(实测 [PERF-FIRST-TURN] java-prep codex≈3.2s/opencode≈3.6s)。
                // 后台预热填 cachedExecutable 后,首条消息命中缓存秒回(第二条 java-prep≈50ms 即证)。
                // Deliberately on ForkJoinPool.commonPool: the enclosing thread is an
                // AppExecutorUtil pooled thread and prewarmCliResolvers() joins below,
                // so submitting onto the shared app pool here would risk pool self-wait
                // under saturation. Two one-shot probes on commonPool are harmless.
                CompletableFuture<Void> cliPrewarm = CompletableFuture.runAsync(
                        BridgePreloader::prewarmCliResolvers);

                // Trigger extraction (non-blocking on this pooled thread)
                resolver.findBridgeDir();

                // ai-bridge 解压完成后,后台预热 MCP Gateway(若 isGatewayActive)。这是"插件启动预热":
                // 比打开工具窗口更早,让用户打开 AICG 窗口/发首条消息时 gateway 进程已起、各 MCP server
                // 已加载 → 首次 buildCliConfig 因 configHash 相同而 skip(秒回)。
                // WebviewInitializer 的预热保留作双保险;applySnapshot 的 configHash 幂等保证不重复推送。
                prewarmMcpGateway(project);

                // detector 预热(并行 ~3s)通常已先于 gateway postSnapshot(>10s)完成,此处仅兜底等待,
                // 确保即便用户立刻发首条消息,detector 缓存也已就绪。
                cliPrewarm.join();

                LOG.info("[BridgePreloader] Bridge preload completed for project: " + project.getName());
            } catch (Exception e) {
                LOG.warn("[BridgePreloader] Bridge preload failed: " + e.getMessage(), e);
            }
        });

        return Unit.INSTANCE;
    }

    /**
     * 项目打开时后台预热 MCP Gateway。在 ai-bridge 解压完成后的同一个 pooled 线程内执行,
     * 不阻塞 EDT;{@link McpGatewayService#refreshConfig} 内部会 ensureStarted(最多等一个
     * cold-start)+ applySnapshot(同步等各 MCP server initialize/listTools,首次较慢但用户无感)。
     * {@code isGatewayActive} 守卫:gateway 整体禁用时 no-op,避免空跑。
     */
    private static void prewarmMcpGateway(@NotNull Project project) {
        if (project.isDisposed() || !McpGatewayFeatureFlags.isGatewayActive()) {
            return;
        }
        try {
            McpGatewayService.getInstance(project).refreshConfig(project.getBasePath());
            LOG.info("[BridgePreloader] MCP Gateway prewarmed for project: " + project.getName());
        } catch (Exception e) {
            LOG.warn("[BridgePreloader] MCP Gateway prewarm failed: " + e.getMessage(), e);
        }
    }

    /**
     * 项目打开时后台并行预热 codex/opencode CLI resolver 缓存。两者 {@code findExecutable()} 首次会
     * spawn {@code <cli> --version} 子进程验证可执行性 + 取版本(经 .cmd 包装冷启动 ~3s),未预热时
     * 这段时间落在用户首条消息的同步 send 路径。预热后首条消息命中 {@code cachedExecutable} 秒回。
     * <p>只预热 codex/opencode:claude detector 冷启动仅 ~227ms(已够快),且其 {@code detectionAttempted}
     * 失败永久置位有"预热失败致永久不可用"风险,收益不足风险故不预热。
     * <p>两 resolver 只缓存成功路径(不缓存失败),故预热失败无副作用——首条消息时正常重试检测。
     */
    private static void prewarmCliResolvers() {
        CompletableFuture.allOf(
                CompletableFuture.runAsync(BridgePreloader::prewarmCodexCli),
                CompletableFuture.runAsync(BridgePreloader::prewarmOpenCodeCli)
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
}
