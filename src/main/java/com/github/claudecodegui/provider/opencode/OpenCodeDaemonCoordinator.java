package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns OpenCode daemon lifecycle (opencode serve) and HTTP client access.
 * <p>
 * Unlike Claude/Codex which use NDJSON over stdin/stdout, OpenCode uses HTTP REST API.
 * This coordinator manages the `opencode serve` process and provides the server URL
 * for HTTP-based communication.
 */
class OpenCodeDaemonCoordinator {

    private static final long DAEMON_RETRY_DELAY_MS = 60_000;
    private static final long HEALTH_CHECK_TIMEOUT_MS = 5_000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 50;
    private static final int DEFAULT_PORT = 4096;

    private final Logger log;
    private final AtomicReference<OpenCodeServerInstance> serverInstance = new AtomicReference<>();
    private volatile long daemonRetryAfter = 0;
    private final Object daemonLock = new Object();

    OpenCodeDaemonCoordinator(Logger log) {
        this.log = log;
    }

    /**
     * Get or start the OpenCode server. Returns the server URL if available.
     */
    String getServerUrl() {
        OpenCodeServerInstance current = serverInstance.get();
        if (current != null && current.isHealthy()) {
            return current.url();
        }
        if (System.currentTimeMillis() < daemonRetryAfter) {
            return null;
        }

        synchronized (daemonLock) {
            current = serverInstance.get();
            if (current != null && current.isHealthy()) {
                return current.url();
            }

            daemonRetryAfter = System.currentTimeMillis() + DAEMON_RETRY_DELAY_MS;
            try {
                if (current != null) {
                    current.stop();
                }

                OpenCodeServerInstance newInstance = startServer();
                if (newInstance != null) {
                    serverInstance.set(newInstance);
                    daemonRetryAfter = 0;
                    log.info("[OpenCodeDaemonCoordinator] Server started at " + newInstance.url());
                    return newInstance.url();
                }
                log.warn("[OpenCodeDaemonCoordinator] Failed to start server, using per-process mode");
            } catch (Exception e) {
                log.debug("[OpenCodeDaemonCoordinator] Server init failed: " + e.getMessage());
            }
            return null;
        }
    }

    void shutdownServer() {
        OpenCodeServerInstance current = serverInstance.getAndSet(null);
        if (current != null) {
            current.stop();
            daemonRetryAfter = 0;
        }
    }

    private OpenCodeServerInstance startServer() {
        try {
            String executable = OpenCodeCliResolver.findExecutable();
            ProcessBuilder pb = new ProcessBuilder(
                    executable,
                    "serve",
                    "--hostname", "127.0.0.1",
                    "--port", String.valueOf(DEFAULT_PORT)
            );
            pb.redirectErrorStream(false);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();
            String url = "http://127.0.0.1:" + DEFAULT_PORT;

            // Wait for server to be ready
            if (waitForHealthCheck(url)) {
                return new OpenCodeServerInstance(process, url);
            }

            process.destroyForcibly();
            return null;
        } catch (Exception e) {
            log.debug("[OpenCodeDaemonCoordinator] Failed to start server: " + e.getMessage());
            return null;
        }
    }

    private boolean waitForHealthCheck(String url) {
        long deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url + "/api/health").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    record OpenCodeServerInstance(Process process, String url) {
        boolean isHealthy() {
            if (process == null || !process.isAlive()) {
                return false;
            }
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url + "/api/health").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                conn.disconnect();
                return code == 200;
            } catch (Exception e) {
                return false;
            }
        }

        void stop() {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
