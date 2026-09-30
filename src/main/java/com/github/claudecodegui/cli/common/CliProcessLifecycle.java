package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.util.PlatformUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * one-shot CLI 子进程的公共生命周期：异步 drain、绝对超时、终止与有界收尾。
 */
public final class CliProcessLifecycle {

    private CliProcessLifecycle() {
    }

    @FunctionalInterface
    public interface OutputDrainer {
        void drain() throws Exception;
    }

    public record Outcome(int exitCode, boolean timedOut) {
    }

    private static final long PROCESS_POLL_SLICE_MS = 500L;

    public static CompletableFuture<Void> drainAsync(Process process, OutputDrainer drainer) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                drainer.drain();
            } catch (Exception error) {
                throw new OutputDrainException(error);
            }
        }, CliSessionExecutor.executor());
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                terminate(process);
            }
        });
        return future;
    }

    public static Outcome await(Process process, CompletableFuture<Void> drainFuture) throws Exception {
        return await(process, drainFuture, CliConstants.CLI_REQUEST_TIMEOUT_MS);
    }

    /**
     * 空闲感知等待(对称上游 32a2ad50):{@code lastOutputNanos} 由 stdout drain 循环在
     * 每次读到输出时更新;只有连续无输出超过 {@code idleTimeoutMs} 才判定卡死并终止进程树。
     * 有活动输出的请求不受任何总时长上限约束(长重构/完整测试套件不再中途被杀)。
     * 退出码与 timedOut 语义与 {@link #await(Process, CompletableFuture, long)} 一致。
     */
    public static Outcome awaitIdleAware(
            Process process,
            CompletableFuture<Void> drainFuture,
            AtomicLong lastOutputNanos,
            long idleTimeoutMs
    ) throws Exception {
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs);
        while (true) {
            if (process.waitFor(PROCESS_POLL_SLICE_MS, TimeUnit.MILLISECONDS)) {
                break;
            }
            long last = lastOutputNanos.get();
            if (System.nanoTime() - last > idleNanos) {
                terminate(process);
                if (!process.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                awaitDrainBestEffort(drainFuture);
                int exitCode = process.isAlive() ? -1 : process.exitValue();
                return new Outcome(exitCode, true);
            }
        }
        awaitDrain(drainFuture);
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return new Outcome(exitCode, false);
    }

    public static Outcome await(
            Process process,
            CompletableFuture<Void> drainFuture,
            long requestTimeoutMs
    ) throws Exception {
        boolean exited = process.waitFor(requestTimeoutMs, TimeUnit.MILLISECONDS);
        boolean timedOut = !exited;
        if (timedOut) {
            terminate(process);
            if (!process.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            awaitDrainBestEffort(drainFuture);
        } else {
            awaitDrain(drainFuture);
        }
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return new Outcome(exitCode, timedOut);
    }

    public static void terminate(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        PlatformUtils.terminateProcess(process);
    }

    private static void awaitDrain(CompletableFuture<Void> drainFuture) throws Exception {
        try {
            drainFuture.get(CliConstants.OUTPUT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            drainFuture.cancel(true);
            throw new IllegalStateException("CLI stdout drain timed out", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof OutputDrainException wrapper && wrapper.getCause() instanceof Exception nested) {
                throw nested;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException("CLI stdout drain failed", cause);
        }
    }

    private static void awaitDrainBestEffort(CompletableFuture<Void> drainFuture) throws InterruptedException {
        try {
            awaitDrain(drainFuture);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception ignored) {
            // The request timeout is the authoritative terminal error. A parser/drain
            // failure caused by terminating the process must not replace it.
        }
    }

    private static final class OutputDrainException extends RuntimeException {
        private OutputDrainException(Throwable cause) {
            super(cause);
        }
    }
}
