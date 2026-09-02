package com.github.claudecodegui.startup;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliEnvironmentBuilder;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.Objects;

/** Lightweight channel-manager probes for providers that do not expose a direct resolver. */
final class ProviderChannelPrewarm {

    static final String COMMAND_LIST_MODELS = "listModels";
    static final String COMMAND_STATUS = "status";
    private static final Logger LOG = Logger.getInstance(ProviderChannelPrewarm.class);
    private static final long POLL_INTERVAL_MS = 100L;
    private static final long DRAIN_JOIN_TIMEOUT_MS = 500L;

    private ProviderChannelPrewarm() {
    }

    static void probe(ProviderType provider, String command, Duration timeout, BooleanSupplier cancelled) {
        if (provider == null || command == null || command.isBlank() || isCancelled(cancelled)) {
            return;
        }
        Duration effectiveTimeout = Objects.requireNonNull(timeout, "timeout");
        Process process = null;
        ProcessManager processManager = null;
        String processToken = null;
        Thread stdoutDrain = null;
        Thread stderrDrain = null;
        try {
            NodeService nodeService = NodeService.getInstance();
            String node = nodeService.getNodeDetector().findNodeExecutable();
            if (node == null || node.isBlank()) {
                LOG.warn("[ProviderChannelPrewarm] Node.js not found: provider=" + provider);
                return;
            }
            File bridgeDir = nodeService.getBridgeDir();
            if (bridgeDir == null) {
                LOG.warn("[ProviderChannelPrewarm] Bridge directory unavailable: provider=" + provider);
                return;
            }
            File channelManager = new File(bridgeDir, "channel-manager.js");
            if (!channelManager.isFile()) {
                LOG.warn("[ProviderChannelPrewarm] channel-manager.js not found: "
                        + channelManager.getAbsolutePath());
                return;
            }

            List<String> commandLine = new ArrayList<>();
            commandLine.add(node);
            commandLine.add(channelManager.getAbsolutePath());
            commandLine.add(provider.value());
            commandLine.add(command);
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            processBuilder.directory(bridgeDir);
            processBuilder.redirectErrorStream(false);
            processBuilder.environment().clear();
            processBuilder.environment().putAll(CliEnvironmentBuilder.buildBaseEnvironment());
            processBuilder.environment().put(CliConstants.ARG_NO_COLOR, "1");
            processBuilder.environment().put(stdinEnvKey(provider), "true");
            nodeService.getEnvConfigurator().updateProcessEnvironment(processBuilder, node);

            process = processBuilder.start();
            processManager = nodeService.getProcessManager();
            processToken = processManager.registerAuxiliaryProcess(process);
            if (processToken == null) {
                return;
            }
            stdoutDrain = startDrain(process.getInputStream(), "stdout", provider);
            stderrDrain = startDrain(process.getErrorStream(), "stderr", provider);
            closeStdin(process);

            long timeoutMs = Math.max(0L, effectiveTimeout.toMillis());
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (process.isAlive()) {
                if (isCancelled(cancelled)) {
                    terminate(process);
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    LOG.warn("[ProviderChannelPrewarm] Probe timed out: provider=" + provider
                            + ", command=" + command);
                    terminate(process);
                    return;
                }
                try {
                    process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    terminate(process);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (process.exitValue() != 0) {
                LOG.warn("[ProviderChannelPrewarm] Probe exited with code " + process.exitValue()
                        + ": provider=" + provider + ", command=" + command);
            }
        } catch (Exception e) {
            if (!isCancelled(cancelled)) {
                LOG.warn("[ProviderChannelPrewarm] Probe failed: provider=" + provider
                        + ", command=" + command, e);
            }
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            joinDrain(stdoutDrain);
            joinDrain(stderrDrain);
            if (processManager != null) {
                processManager.unregisterAuxiliaryProcess(processToken, process);
            }
        }
    }

    private static Thread startDrain(InputStream input, String stream, ProviderType provider) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream streamInput = input) {
                while (streamInput.read(buffer) != -1) {
                    // The probe only needs process readiness; discard bounded chunks after draining.
                }
            } catch (Exception e) {
                LOG.debug("[ProviderChannelPrewarm] " + stream + " drain ended: provider=" + provider, e);
            }
        }, "AICG-Provider-Prewarm-" + provider.value() + "-" + stream);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void closeStdin(Process process) {
        try (OutputStream output = process.getOutputStream()) {
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {
            // The channel command may not need stdin; EOF is still required for stdin-enabled readers.
        }
    }

    private static void terminate(Process process) {
        if (process != null && process.isAlive()) {
            PlatformUtils.terminateProcessAndWait(process, 2, TimeUnit.SECONDS);
        }
    }

    private static void joinDrain(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(DRAIN_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    private static String stdinEnvKey(ProviderType provider) {
        return provider.value().toUpperCase(java.util.Locale.ROOT) + "_USE_STDIN";
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean());
    }
}
