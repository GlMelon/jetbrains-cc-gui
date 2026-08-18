package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.cli.CliSessionExecutor;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliEnvironmentBuilder;
import com.github.claudecodegui.cli.common.CliProcessLifecycle;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Executes Claude file checkpoint restoration through the Claude CLI.
 */
class ClaudeRewindQueryService {

    static final long REWIND_TIMEOUT_MS = 60_000L;

    private final Logger log;
    private final Supplier<String> cliExecutableSupplier;
    private final long timeoutMs;

    ClaudeRewindQueryService(Logger log, Supplier<String> cliExecutableSupplier) {
        this(log, cliExecutableSupplier, REWIND_TIMEOUT_MS);
    }

    ClaudeRewindQueryService(Logger log, Supplier<String> cliExecutableSupplier, long timeoutMs) {
        this.log = log;
        this.cliExecutableSupplier = cliExecutableSupplier;
        this.timeoutMs = timeoutMs;
    }

    CompletableFuture<JsonObject> rewindFiles(String sessionId, String userMessageId, String cwd) {
        return CompletableFuture.supplyAsync(
                () -> executeRewind(sessionId, userMessageId, cwd),
                CliSessionExecutor.executor()
        );
    }

    private JsonObject executeRewind(String sessionId, String userMessageId, String cwd) {
        Process process = null;
        try {
            String cliExecutable = cliExecutableSupplier.get();
            if (cliExecutable == null || cliExecutable.isBlank()) {
                return failure("CLI_NOT_FOUND", "Claude CLI not found");
            }

            List<String> command = buildCommand(cliExecutable, sessionId, userMessageId);
            log.info("[Rewind] Starting Claude CLI file rewind for session " + sessionId
                    + " at user message " + userMessageId);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(resolveWorkingDirectory(cwd));
            configureEnvironment(processBuilder.environment(), cwd);

            process = processBuilder.start();
            // --rewind-files is a standalone operation. Close stdin immediately so
            // the CLI never waits for a prompt or an EOF that will not arrive.
            process.getOutputStream().close();

            StringBuilder output = new StringBuilder();
            Process rewindProcess = process;
            CompletableFuture<Void> drainFuture = CliProcessLifecycle.drainAsync(
                    rewindProcess,
                    () -> drainOutput(rewindProcess, output)
            );
            CliProcessLifecycle.Outcome outcome = CliProcessLifecycle.await(
                    rewindProcess,
                    drainFuture,
                    timeoutMs
            );

            String outputText = output.toString().trim();
            if (outcome.timedOut()) {
                return failure("PROCESS_TIMEOUT", "Claude file rewind timed out");
            }
            if (outcome.exitCode() != 0) {
                return failure(
                        classifyError(outputText),
                        outputText.isBlank()
                                ? "Claude CLI exited with code " + outcome.exitCode()
                                : outputText
                );
            }

            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            if (!outputText.isBlank()) {
                result.addProperty("message", outputText);
            }
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure("PROCESS_INTERRUPTED", "Claude file rewind was interrupted");
        } catch (Exception error) {
            log.warn("[Rewind] Claude CLI file rewind failed", error);
            String message = error.getMessage();
            return failure(
                    "PROCESS_FAILED",
                    message == null || message.isBlank() ? "Claude file rewind failed" : message
            );
        } finally {
            CliProcessLifecycle.terminate(process);
        }
    }

    static List<String> buildCommand(String cliExecutable, String sessionId, String userMessageId) {
        List<String> command = new ArrayList<>();
        command.add(cliExecutable);
        command.add(CliConstants.ARG_P);
        command.add(CliConstants.ARG_RESUME);
        command.add(sessionId);
        command.add(CliConstants.ARG_REWIND_FILES);
        command.add(userMessageId);
        return command;
    }

    private static void configureEnvironment(Map<String, String> environment, String cwd) {
        environment.clear();
        environment.putAll(CliEnvironmentBuilder.buildBaseEnvironment());
        environment.putAll(CliSettings.readClaudeCliEnvironment());
        environment.put(
                CliConstants.ENV_CLAUDE_ENABLE_SDK_FILE_CHECKPOINTING,
                CliConstants.ENV_TRUE
        );
        environment.put(CliConstants.ARG_NO_COLOR, CliConstants.ENV_ENABLED);
        CliEnvironmentBuilder.configureProjectPath(environment, cwd);
    }

    private static File resolveWorkingDirectory(String cwd) {
        if (cwd != null && !cwd.isBlank()) {
            File requested = new File(cwd);
            if (requested.isDirectory()) {
                return requested;
            }
        }
        File home = new File(PlatformUtils.getHomeDirectory());
        return home.isDirectory() ? home : new File(System.getProperty("user.dir"));
    }

    private static void drainOutput(Process process, StringBuilder output) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
    }

    private static String classifyError(String output) {
        String normalized = output == null ? "" : output.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("no conversation") || normalized.contains("conversation not found")) {
            return "SESSION_NOT_FOUND";
        }
        if (normalized.contains("not a user message") || normalized.contains("user message uuid")) {
            return "INVALID_USER_MESSAGE_ID";
        }
        if (normalized.contains("checkpoint") || normalized.contains("cannot rewind")
                || normalized.contains("can't rewind")) {
            return "CHECKPOINT_NOT_FOUND";
        }
        if (normalized.contains("not a uuid") || normalized.contains("invalid uuid")) {
            return "INVALID_SESSION_ID";
        }
        return "PROCESS_FAILED";
    }

    private static JsonObject failure(String errorCode, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("errorCode", errorCode);
        result.addProperty("error", message);
        return result;
    }
}
