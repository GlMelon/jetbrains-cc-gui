package com.github.claudecodegui.util;

import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Shell command execution utility.
 * Encapsulates common logic for process execution, timeout handling, and output filtering.
 */
public final class ShellExecutor {

    private static final Logger LOG = Logger.getInstance(ShellExecutor.class);

    /**
     * Default process timeout in seconds.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private ShellExecutor() {
        // Utility class, do not instantiate
    }

    /**
     * Execution result.
     */
    public static class ExecutionResult {
        private final boolean success;
        private final String output;
        private final List<String> allLines;
        private final List<String> filteredLines;

        private ExecutionResult(boolean success, String output, List<String> allLines, List<String> filteredLines) {
            this.success = success;
            this.output = output;
            this.allLines = allLines;
            this.filteredLines = filteredLines;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public static ExecutionResult success(String output, List<String> allLines, List<String> filteredLines) {
            return new ExecutionResult(true, output, allLines, filteredLines);
        }

        public static ExecutionResult failure() {
            return new ExecutionResult(false, null, List.of(), List.of());
        }

        public static ExecutionResult timeout() {
            return new ExecutionResult(false, null, List.of(), List.of());
        }
    }

    /**
     * Execute a shell command and return the first valid output line.
     *
     * @param command    the command as a list of arguments
     * @param lineFilter line filter; returns true if the line is valid
     * @param logPrefix  prefix for log messages
     * @return the execution result
     */
    public static ExecutionResult execute(List<String> command, Predicate<String> lineFilter, String logPrefix) {
        return execute(command, lineFilter, logPrefix, DEFAULT_TIMEOUT_SECONDS, true);
    }

    /**
     * Execute a shell command and return the result.
     *
     * @param command          the command as a list of arguments
     * @param lineFilter       line filter; returns true if the line is valid
     * @param logPrefix        prefix for log messages
     * @param timeoutSeconds   timeout in seconds
     * @param useInteractive   whether to use interactive shell configuration (sets TERM=dumb)
     * @return the execution result
     */
    public static ExecutionResult execute(
            List<String> command,
            Predicate<String> lineFilter,
            String logPrefix,
            int timeoutSeconds,
            boolean useInteractive
    ) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (useInteractive) {
                // Set TERM=dumb to suppress extra output from interactive shells (color codes, prompts, etc.)
                pb.environment().put("TERM", "dumb");
            }
            pb.redirectErrorStream(true);

            process = pb.start();
            // Drain output on a background thread: waiting before reading can deadlock
            // once the child fills the OS pipe buffer (~64KB on Windows).
            OutputReaderHandle reader = startOutputReader(process);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.debug(logPrefix + " command timed out");
                terminateQuietly(process);
                return ExecutionResult.timeout();
            }

            return collect(reader, lineFilter, logPrefix, true);
        } catch (Exception e) {
            LOG.debug(logPrefix + " execution failed: " + e.getMessage());
            // waitFor/collect may throw after start; reap the child so it cannot
            // outlive the probe as a zombie holding the reader thread open.
            terminateQuietly(process);
            return ExecutionResult.failure();
        }
    }

    /**
     * Execute a shell command and return the last valid output line (useful for retrieving environment variables).
     *
     * @param command          the command as a list of arguments
     * @param lineFilter       line filter; returns true if the line is valid
     * @param logPrefix        prefix for log messages
     * @param timeoutSeconds   timeout in seconds
     * @return the execution result
     */
    public static ExecutionResult executeAndGetLast(
            List<String> command,
            Predicate<String> lineFilter,
            String logPrefix,
            int timeoutSeconds
    ) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // Set TERM=dumb to suppress extra output from interactive shells
            pb.environment().put("TERM", "dumb");
            pb.redirectErrorStream(true);

            process = pb.start();
            // Drain output on a background thread (see execute() for rationale)
            OutputReaderHandle reader = startOutputReader(process);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.debug(logPrefix + " command timed out");
                terminateQuietly(process);
                return ExecutionResult.timeout();
            }

            return collect(reader, lineFilter, logPrefix, false);
        } catch (Exception e) {
            LOG.debug(logPrefix + " execution failed: " + e.getMessage());
            // waitFor/collect may throw after start; reap the child so it cannot
            // outlive the probe as a zombie holding the reader thread open.
            terminateQuietly(process);
            return ExecutionResult.failure();
        }
    }

    /**
     * Handle for the background stdout drain thread started by {@link #startOutputReader}.
     */
    private static final class OutputReaderHandle {
        private final Thread thread;
        private final List<String> lines;

        private OutputReaderHandle(Thread thread, List<String> lines) {
            this.thread = thread;
            this.lines = lines;
        }
    }

    /**
     * Best-effort process termination: destroy and wait briefly so the child
     * cannot outlive a failed or timed-out probe as a zombie.
     */
    private static void terminateQuietly(Process process) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
                LOG.debug("process still alive 300ms after destroyForcibly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts a daemon thread that drains the process stdout into a shared list.
     * Must be called before {@code waitFor} so a full OS pipe buffer can never
     * block the child process.
     */
    private static OutputReaderHandle startOutputReader(Process process) {
        // CopyOnWriteArrayList: if the drain thread outlives the join window in
        // collect() (grandchild processes keep the pipe write end open, or a large
        // buffer takes over a second to drain), the reader may still be appending
        // while collect() iterates — a plain ArrayList would corrupt there.
        List<String> lines = new CopyOnWriteArrayList<>();
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line.trim());
                }
            } catch (Exception ignored) {
                // Process destroyed on timeout closes the pipe mid-read; partial output is fine.
            }
        }, "shell-executor-output-reader");
        thread.setDaemon(true);
        thread.start();
        return new OutputReaderHandle(thread, lines);
    }

    /**
     * Waits briefly for the drain thread, then applies the filter to the collected lines.
     *
     * @param firstMatch true to keep the first matching line, false to keep the last
     */
    private static ExecutionResult collect(
            OutputReaderHandle reader,
            Predicate<String> lineFilter,
            String logPrefix,
            boolean firstMatch
    ) {
        try {
            // The process already exited, so the reader hits EOF almost immediately.
            reader.thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<String> filteredLines = new ArrayList<>();
        String validOutput = null;
        for (String trimmed : reader.lines) {
            if (lineFilter.test(trimmed)) {
                validOutput = trimmed;
                if (firstMatch) {
                    break;
                }
            } else if (!trimmed.isEmpty()) {
                // Record filtered non-empty lines for debugging
                filteredLines.add(trimmed);
            }
        }

        // Log filtered lines at DEBUG level
        if (!filteredLines.isEmpty() && LOG.isDebugEnabled()) {
            LOG.debug(logPrefix + " filtered lines: " + filteredLines);
        }

        if (validOutput != null) {
            return ExecutionResult.success(validOutput, List.copyOf(reader.lines), filteredLines);
        }

        return ExecutionResult.failure();
    }

    /**
     * Create a default filter for interactive shell output.
     * Filters out common shell prompts, login messages, etc.
     *
     * @return the line filter
     */
    public static Predicate<String> createShellOutputFilter() {
        return line -> {
            if (line == null || line.isEmpty()) {
                return false;
            }
            // Skip common shell output noise
            return !line.startsWith("[") &&         // Skip MOTD brackets
                   !line.startsWith("%") &&         // Skip zsh prompts
                   !line.startsWith(">") &&         // Skip continuation prompts
                   !line.contains("Last login");    // Skip login messages
        };
    }

    /**
     * Create a filter for Node.js paths.
     *
     * @return the line filter
     */
    public static Predicate<String> createNodePathFilter() {
        return line -> {
            if (line == null || line.isEmpty()) {
                return false;
            }
            // A valid node path should start with /, end with /node, and not contain error messages
            return line.startsWith("/") &&
                   !line.contains("not found") &&
                   line.endsWith("/node");
        };
    }
}
