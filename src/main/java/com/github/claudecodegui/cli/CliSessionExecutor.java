package com.github.claudecodegui.cli;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor for blocking CLI turns.
 * The default CompletableFuture common pool can be constrained inside the IDE,
 * so long-running provider processes must not depend on it for tab concurrency.
 */
public final class CliSessionExecutor implements Disposable {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);
    private static final int MAX_WORKERS = Math.max(4,
            Math.min(32, Runtime.getRuntime().availableProcessors() * 2));
    private static final int MAX_QUEUED_TASKS = 64;
    private static final long KEEP_ALIVE_SECONDS = 30;

    /**
     * Only used by lightweight tests or during very early application startup,
     * before the application service can be resolved. It is deliberately
     * bounded and lets idle threads expire, so this fallback cannot become a
     * permanent thread leak.
     */
    private static final ExecutorService FALLBACK_EXECUTOR = createExecutor();

    private final ExecutorService executor = createExecutor();
    private final AtomicBoolean disposed = new AtomicBoolean();

    private static ExecutorService createExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                MAX_WORKERS,
                MAX_WORKERS,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_TASKS),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "AICG-CLI-Session-" + THREAD_COUNTER.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public CliSessionExecutor() {
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        if (runnable == null) {
            return CompletableFuture.failedFuture(new NullPointerException("runnable"));
        }
        try {
            return CompletableFuture.runAsync(runnable, sharedExecutor());
        } catch (RejectedExecutionException error) {
            // Keep async call sites from leaking a rejected task out of the
            // dispatch chain. The returned failed future also lets callers
            // release their per-request bookkeeping in whenComplete hooks.
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * 暴露内部线程池,供调用方做 future 链式编排(如 CliSessionManager 的 per-tab 串行)。
     */
    public static Executor executor() {
        return sharedExecutor();
    }

    private static ExecutorService sharedExecutor() {
        try {
            CliSessionExecutor service = ApplicationManager.getApplication()
                    .getService(CliSessionExecutor.class);
            if (service != null && !service.disposed.get()) {
                return service.executor;
            }
        } catch (RuntimeException ignored) {
            // ApplicationManager is not available in isolated unit tests or
            // during plugin bootstrap. Use the bounded fallback there.
        }
        return FALLBACK_EXECUTOR;
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
