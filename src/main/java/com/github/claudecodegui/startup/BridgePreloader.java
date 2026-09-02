package com.github.claudecodegui.startup;

import com.github.claudecodegui.bridge.BridgeDirectoryResolver;
import com.github.claudecodegui.mcp.McpGatewayFeatureFlags;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.util.Disposer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Pre-loads the AI Bridge on project startup to avoid EDT freeze
 * when opening the tool window for the first time.
 *
 * <p>Provider startup work is delegated to {@link ProviderPrewarmRegistry};
 * this class owns only project lifecycle and task cancellation.</p>
 */
public class BridgePreloader implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(BridgePreloader.class);
    private static final ProviderPrewarmRegistry PROVIDER_PREWARM_REGISTRY =
            ProviderPrewarmRegistry.defaultRegistry();

    private static volatile BridgeDirectoryResolver sharedResolver;
    private static final Object RESOLVER_LOCK = new Object();

    /** Get or create the shared resolver instance. */
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

    /** Check if bridge extraction is complete (non-blocking). */
    public static boolean isBridgeReady() {
        return getSharedResolver().isExtractionComplete();
    }

    /** Get a future that completes when extraction is done. */
    public static CompletableFuture<Boolean> waitForBridgeAsync() {
        return getSharedResolver().getExtractionFuture();
    }

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (project.isDisposed()) {
            return Unit.INSTANCE;
        }
        LOG.info("[BridgePreloader] Starting bridge preload for project: " + project.getName());

        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<Future<?>> cliPrewarmTasks = new CopyOnWriteArrayList<>();
        Future<?> preloadFuture = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CompletableFuture<Void> cliPrewarm = null;
            try {
                if (shouldStopPreload(project, cancelled)) {
                    return;
                }
                BridgeDirectoryResolver resolver = getSharedResolver();

                // Extract the bridge before channel probes start: OMP/DSH prewarm
                // launches channel-manager.js from the resolved bridge directory.
                resolver.findBridgeDir();
                if (shouldStopPreload(project, cancelled)) {
                    return;
                }

                // CLI resolver caches are global, but every task is owned by this
                // project startup operation and is cancelled with its lifecycle.
                cliPrewarm = CompletableFuture.runAsync(
                        () -> prewarmCliResolvers(project, () -> shouldStopPreload(project, cancelled), cliPrewarmTasks));
                if (shouldStopPreload(project, cancelled)) {
                    return;
                }

                prewarmMcpGateway(project);
                if (shouldStopPreload(project, cancelled)) {
                    return;
                }

                cliPrewarm.join();
                if (!shouldStopPreload(project, cancelled)) {
                    LOG.info("[BridgePreloader] Bridge preload completed for project: " + project.getName());
                }
            } catch (CancellationException e) {
                cancelled.set(true);
            } catch (Exception e) {
                if (!shouldStopPreload(project, cancelled)) {
                    LOG.warn("[BridgePreloader] Bridge preload failed: " + e.getMessage(), e);
                }
            } finally {
                if (shouldStopPreload(project, cancelled)) {
                    cancelled.set(true);
                    cancelTasks(cliPrewarmTasks);
                    if (cliPrewarm != null) {
                        cliPrewarm.cancel(true);
                    }
                }
            }
        });
        Disposable cancelPreload = () -> {
            cancelled.set(true);
            preloadFuture.cancel(true);
            cancelTasks(cliPrewarmTasks);
        };
        if (!Disposer.tryRegister(project, cancelPreload)) {
            cancelPreload.dispose();
        }
        return Unit.INSTANCE;
    }

    private static boolean shouldStopPreload(@NotNull Project project, @NotNull AtomicBoolean cancelled) {
        return cancelled.get() || project.isDisposed() || Thread.currentThread().isInterrupted();
    }

    private static void cancelTasks(List<Future<?>> tasks) {
        for (Future<?> task : tasks) {
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    /**
     * Project startup MCP Gateway prewarm. Slow Gateway work remains outside
     * the preloader's lifecycle lock and is guarded by the project state.
     */
    private static void prewarmMcpGateway(@NotNull Project project) {
        if (project.isDisposed() || Thread.currentThread().isInterrupted()
                || !McpGatewayFeatureFlags.isGatewayActive()) {
            return;
        }
        try {
            McpGatewayService gatewayService = McpGatewayService.getInstance(project);
            if (project.isDisposed() || Thread.currentThread().isInterrupted()) {
                return;
            }
            gatewayService.refreshConfig(project.getBasePath());
            if (!project.isDisposed() && !Thread.currentThread().isInterrupted()) {
                LOG.info("[BridgePreloader] MCP Gateway prewarmed for project: " + project.getName());
            }
        } catch (Exception e) {
            if (!project.isDisposed() && !Thread.currentThread().isInterrupted()) {
                LOG.warn("[BridgePreloader] MCP Gateway prewarm failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Starts one cancellable task per registered Provider strategy. The registry
     * explicitly covers all eight providers; this method contains no provider
     * conditionals and only coordinates task lifecycle.
     *
     * <p>Each strategy gets its own full timeout window anchored at submission time
     * ({@code startNanos + policy.timeout}), not a shared deadline consumed in join
     * order — otherwise a slow resolver ahead in registration order starves later
     * providers (kimi/grok/pi were cancelled before their probe threads could finish,
     * leaving version caches empty on first use).</p>
     */
    private static void prewarmCliResolvers(
            Project project,
            BooleanSupplier cancelled,
            List<Future<?>> tasks
    ) {
        List<ProviderPrewarmStrategy> strategies = PROVIDER_PREWARM_REGISTRY.strategies();
        long startNanos = System.nanoTime();
        for (ProviderPrewarmStrategy strategy : strategies) {
            if (isCancelled(cancelled)) {
                return;
            }
            Future<?> task = ApplicationManager.getApplication().executeOnPooledThread(
                    () -> prewarmProvider(project, strategy, cancelled));
            tasks.add(task);
            if (isCancelled(cancelled)) {
                task.cancel(true);
            }
        }

        for (int i = 0; i < tasks.size(); i++) {
            if (isCancelled(cancelled)) {
                cancelTasks(tasks);
                return;
            }
            Future<?> task = tasks.get(i);
            ProviderPrewarmStrategy strategy = strategies.get(i);
            try {
                long remainingNanos = startNanos + strategy.policy().timeout().toNanos()
                        - System.nanoTime();
                if (remainingNanos <= 0L) {
                    task.cancel(true);
                    LOG.warn("[BridgePreloader] Provider prewarm timed out: " + strategy.provider());
                    continue;
                }
                task.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelTasks(tasks);
                return;
            } catch (TimeoutException e) {
                task.cancel(true);
                LOG.warn("[BridgePreloader] Provider prewarm timed out: " + strategy.provider());
            } catch (ExecutionException e) {
                LOG.warn("[BridgePreloader] Provider prewarm failed: " + strategy.provider(), e.getCause());
            } catch (CancellationException e) {
                if (!isCancelled(cancelled)) {
                    LOG.warn("[BridgePreloader] Provider prewarm cancelled: " + strategy.provider());
                }
            }
        }
    }

    private static void prewarmProvider(Project project, ProviderPrewarmStrategy strategy, BooleanSupplier cancelled) {
        try {
            strategy.prewarm(project, cancelled);
            if (!isCancelled(cancelled)) {
                LOG.info("[BridgePreloader] Provider prewarm completed: " + strategy.provider()
                        + " fallback=" + strategy.policy().fallback());
            }
        } catch (Exception e) {
            if (!isCancelled(cancelled)) {
                LOG.warn("[BridgePreloader] Provider prewarm failed: " + strategy.provider(), e);
            }
        }
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean());
    }
}
