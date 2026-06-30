package com.github.claudecodegui.mcp;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the long-lived Node Gateway process and drains its output streams.
 */
public final class McpGatewayProcessHandle {
    private static final Logger LOG = Logger.getInstance(McpGatewayProcessHandle.class);

    private final Process process;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private McpGatewayProcessHandle(Process process) {
        this.process = process;
        drain(process.getInputStream(), false);
        drain(process.getErrorStream(), true);
    }

    public static McpGatewayProcessHandle start(List<String> command) throws java.io.IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        return new McpGatewayProcessHandle(process);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            if (process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to stop process gracefully: " + e.getMessage());
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    private static void drain(InputStream stream, boolean error) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (error) {
                        LOG.warn("[McpGateway][stderr] " + line);
                    } else {
                        LOG.info("[McpGateway] " + line);
                    }
                }
            } catch (Exception e) {
                LOG.debug("[McpGateway] Drain stopped: " + e.getMessage());
            }
        }, "mcp-gateway-drain");
        thread.setDaemon(true);
        thread.start();
    }
}
