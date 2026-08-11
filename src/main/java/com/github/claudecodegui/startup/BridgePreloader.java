package com.github.claudecodegui.startup;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.mcp.McpGatewayFeatureFlags;
import com.github.claudecodegui.mcp.McpGatewayService;
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

                // Trigger extraction (non-blocking on this pooled thread)
                resolver.findSdkDir();

                // ai-bridge 解压完成后,后台预热 MCP Gateway(若 isGatewayActive)。这是"插件启动预热":
                // 比打开工具窗口更早,让用户打开 AICG 窗口/发首条消息时 gateway 进程已起、各 MCP server
                // 已加载 → 首次 buildCliConfig 因 configHash 相同而 skip(秒回)。
                // WebviewInitializer 的预热保留作双保险;applySnapshot 的 configHash 幂等保证不重复推送。
                prewarmMcpGateway(project);

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
     * {@code isGatewayActive} 守卫:gateway 整体禁用(CLI/SDK 都关)时 no-op,避免空跑。
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
}
