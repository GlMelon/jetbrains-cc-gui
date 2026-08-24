package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Helper for running a short-lived Node.js child process used by
 * {@link PromptEnhancerHandler} and {@code CommitMessageAiService}.
 *
 * <p>This class exists exclusively to make the lifecycle management testable:
 * the original code path inside {@code callAIForEnhancement} used
 * {@code process.waitFor()} without a timeout, never registered the process
 * with {@link ProcessManager}, and had no {@code finally} cleanup. A Node
 * hang would leak the child for the lifetime of the IDE.
 *
 * <p>The three guarantees this runner enforces:
 * <ol>
 *   <li><b>Register before wait</b> — so {@code cleanupAllProcesses} on
 *       shutdown can terminate the child and the Node Process panel can
 *       observe it.</li>
 *   <li><b>Hard timeout via async reader</b> — a synchronous {@code readLine()}
 *       loop on a stalled stream is itself non-cancellable; we decouple stdout
 *       draining from the wait, so {@code process.waitFor(timeout)} is
 *       actually enforceable.</li>
 *   <li><b>Balanced finally cleanup</b> — unregister and force-kill on any
 *       exit path, including exceptions thrown from
 *       {@link ProcessBuilder#start()} or stdin write.</li>
 * </ol>
 */
public final class PromptEnhancerProcessRunner {

    private static final Logger LOG = Logger.getInstance(PromptEnhancerProcessRunner.class);

    private PromptEnhancerProcessRunner() {
    }

    /**
     * Runs the given pre-configured ProcessBuilder under ProcessManager
     * supervision, writing {@code stdinJson} to the child's stdin and
     * streaming stdout line-by-line through {@code lineHandler}. Uses the
     * default channel id {@code "prompt-enhancer"}.
     *
     * @see #runWithProcessManager(ProcessBuilder, ProcessManager, String, String, long, long, Consumer)
     */
    public static int runWithProcessManager(
            ProcessBuilder pb,
            ProcessManager processManager,
            String stdinJson,
            long timeoutSeconds,
            long readerDrainSeconds,
            Consumer<String> lineHandler
    ) throws IOException, InterruptedException, TimeoutException {
        return runWithProcessManager(pb, processManager, "prompt-enhancer",
                stdinJson, timeoutSeconds, readerDrainSeconds, lineHandler);
    }

    /**
     * Runs the given pre-configured ProcessBuilder under ProcessManager
     * supervision, writing {@code stdinJson} to the child's stdin and
     * streaming stdout line-by-line through {@code lineHandler}.
     *
     * @param pb                  pre-configured Node.js process builder
     * @param processManager      shared registry for cleanup on shutdown
     * @param channelId           channel id under which the process is registered
     *                            (visible in the Node Process panel / logs)
     * @param stdinJson           the JSON payload to feed to stdin
     * @param timeoutSeconds      hard wall-clock timeout for the child
     * @param readerDrainSeconds  grace window for the reader to flush after exit
     * @param lineHandler         called for each stdout line (in reader thread)
     * @return the process exit code
     * @throws TimeoutException if the child did not exit before the timeout
     */
    public static int runWithProcessManager(
            ProcessBuilder pb,
            ProcessManager processManager,
            String channelId,
            String stdinJson,
            long timeoutSeconds,
            long readerDrainSeconds,
            Consumer<String> lineHandler
    ) throws IOException, InterruptedException, TimeoutException {
        return runWithRegisteredChannel(
                pb,
                processManager,
                ProcessManager.newChannelId(channelId),
                stdinJson,
                timeoutSeconds,
                readerDrainSeconds,
                lineHandler);
    }

    /**
     * Same lifecycle runner as the regular overload, but keeps the supplied
     * channel id unchanged so an owner can cancel the exact process while the
     * task is running or still waiting to start.
     */
    public static int runWithRegisteredChannel(
            ProcessBuilder pb,
            ProcessManager processManager,
            String channelId,
            String stdinJson,
            long timeoutSeconds,
            long readerDrainSeconds,
            Consumer<String> lineHandler
    ) throws IOException, InterruptedException, TimeoutException {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId must not be empty");
        }
        processManager.beginChannelPreservingInterrupt(channelId);
        Process process = null;
        CompletableFuture<Void> readerFuture = null;
        try {
            process = pb.start();
            processManager.registerProcess(channelId, process);
            LOG.info("[ProcessRunner] Process started, PID: " + process.pid()
                    + ", channelId: " + channelId);

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(stdinJson);
                writer.flush();
            }

            // Async stdout drain — see class javadoc for rationale.
            // 显式 executor:reader 存活至子进程输出 EOF(上限 timeoutSeconds,CLI 首条消息的
            // AI 标题生成等高频路径),无 executor 会占住 commonPool worker 同一时长。
            final Process finalProcess = process;
            readerFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineHandler.accept(line);
                    }
                } catch (IOException e) {
                    // Expected when the stream is force-closed on timeout kill.
                    // Kept at debug so the happy-path log stays clean.
                    LOG.debug("[ProcessRunner] reader stream closed: " + e.getMessage());
                } catch (RuntimeException e) {
                    // Anything else — lineHandler NPE, charset issue, etc. — is a
                    // real bug we must not swallow. Log with stack trace.
                    LOG.warn("[ProcessRunner] reader thread failed unexpectedly", e);
                }
            }, AppExecutorUtil.getAppExecutorService());

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.warn("[ProcessRunner] Timeout after " + timeoutSeconds
                        + "s, force killing PID " + process.pid());
                PlatformUtils.terminateProcess(process);
                throw new TimeoutException("Process timed out after "
                        + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            try {
                readerFuture.get(readerDrainSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                LOG.warn("[ProcessRunner] Reader didn't drain within "
                        + readerDrainSeconds + "s, continuing with partial output");
            } catch (ExecutionException ee) {
                // The reader thread threw — root cause already logged inside the
                // async block. Surface it at debug so the chain is traceable but
                // not noisy.
                LOG.debug("[ProcessRunner] Reader execution failed: "
                        + (ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage()));
            } catch (CancellationException ce) {
                // We only cancel readerFuture in the finally block below, which
                // runs after this try. A cancellation here would be unexpected.
                LOG.debug("[ProcessRunner] Reader was cancelled unexpectedly");
            }
            return exitCode;

        } finally {
            processManager.finishChannelStart(channelId, process == null);
            if (process != null) {
                if (process.isAlive()) {
                    PlatformUtils.terminateProcess(process);
                }
                processManager.unregisterProcess(channelId, process);
            }
            if (readerFuture != null && !readerFuture.isDone()) {
                readerFuture.cancel(true);
            }
        }
    }
}
