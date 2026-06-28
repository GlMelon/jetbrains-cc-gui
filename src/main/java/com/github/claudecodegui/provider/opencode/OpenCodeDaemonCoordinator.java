package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.cli.common.UserPathResolver;
import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.ByteArrayOutputStream;
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
    // §15.7 B18:默认 serve 端口(仅 defaultServerUrl 占位用,startServer 已改用 port 0)。
    static final int DEFAULT_PORT = 4096;

    /** §port0:等待 serve 输出 listening 行的超时(ms)。serve 通常 <1s 输出,留足裕量。 */
    private static final long SERVE_READY_TIMEOUT_MS = 15_000;

    /** §15.7 B18:默认 server URL(buildSendStdinJson 兜底/测试用,不启动 serve)。 */
    static String defaultServerUrl() {
        return "http://127.0.0.1:" + DEFAULT_PORT;
    }

    /** 匹配 serve stdout 中 "http://host:port" 的端口捕获组(根除固定端口冲突的关键)。 */
    private static final java.util.regex.Pattern SERVING_PORT_PATTERN =
            java.util.regex.Pattern.compile("https?://[^:/\\s]+:(\\d{1,5})");

    /**
     * 从 serve stdout 行解析实际监听端口。
     * <p>
     * serve 改用 {@code --port 0}(由系统分配空闲端口)后,实际端口仅出现在 stdout 的
     * "opencode server listening on http://host:port" 行;此纯函数提取其中的端口号,
     * 使 daemon 不再依赖固定端口 4096(端口被占用即 ServeError)。
     *
     * @param line serve stdout 的一行(可能含 ANSI 控制序列 / password 警告前缀)
     * @return 端口号;行不匹配或无法解析时返回 null
     */
    static Integer parseServingPort(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = SERVING_PORT_PATTERN.matcher(line);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
                // B2:此处异常(startServer 内部已 try-catch 返回 null,正常不抛;触发说明出现未预期异常)。
                log.warn("[OpenCodeDaemonCoordinator] Server init failed: " + e.getClass().getSimpleName()
                        + " - " + e.getMessage() + " | resolvedPathLen=" + pathLengthForLog());
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

    /** 解析后用户 PATH 长度(诊断用);null/空返回 -1。 */
    private static int pathLengthForLog() {
        String p = UserPathResolver.resolveUserPath();
        return (p == null || p.isBlank()) ? -1 : p.length();
    }

    private OpenCodeServerInstance startServer() {
        Process process = null;
        String executable = null;
        try {
            executable = OpenCodeCliResolver.findExecutable();
            ProcessBuilder pb = new ProcessBuilder(
                    executable,
                    "serve",
                    "--hostname", "127.0.0.1",
                    "--port", "0"                // §port0:由系统分配空闲端口,根除固定端口 4096 冲突
            );
            pb.redirectErrorStream(true);      // stdout/stderr 合并:实际端口仅出现在 listening 行中
            // 注入用户真实 PATH(IDE PATH + npm/scoop/volta 等 shim):裸 ProcessBuilder 默认继承 IDE 环境,
            // Windows 下经 shim 安装的 opencode 不在 IDE PATH → serve 启动失败(异常此前被吞)。
            // 双 key(PATH + Path)对齐 CliEnvironmentBuilder 的 Windows 大小写兼容写法。
            String userPath = UserPathResolver.resolveUserPath();
            if (userPath != null && !userPath.isBlank()) {
                pb.environment().put("PATH", userPath);
                if (PlatformUtils.isWindows()) {
                    pb.environment().put("Path", userPath);
                }
            }

            process = pb.start();

            // 扫描输出流解析实际端口(port 0 → serve 输出真实端口)
            Integer port = scanServingPort(process, SERVE_READY_TIMEOUT_MS);
            if (port == null) {
                // B2:可观测性——无监听端口通常是 serve 启动失败(二进制找不到 / PATH 缺失),补可执行文件名 + PATH 长度。
                log.warn("[OpenCodeDaemonCoordinator] No serving port detected within "
                        + SERVE_READY_TIMEOUT_MS + "ms | executable=" + executable
                        + " | resolvedPathLen=" + pathLengthForLog());
                process.destroyForcibly();
                return null;
            }
            // 守护线程持续消费 serve 输出,防止 pipe 缓冲满导致 serve 写阻塞
            startOutputDrainer(process);

            String url = "http://127.0.0.1:" + port;
            if (waitForHealthCheck(url)) {
                return new OpenCodeServerInstance(process, url);
            }
            process.destroyForcibly();
            return null;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            // B2:可观测性——serve 启动失败此前被 log.debug 吞掉,IDE 日志不可见,故障表现为静默 fetch 失败。
            // 提升 warn 含可执行文件名 + 解析后 PATH 长度,辅助定位(fnm/volta/scoop shim 缺失 / 二进制未装等)。
            log.warn("[OpenCodeDaemonCoordinator] Failed to start server: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage() + " | executable=" + executable
                    + " | resolvedPathLen=" + pathLengthForLog());
            return null;
        }
    }

    /**
     * 非阻塞轮询 serve 输出,解析首个 listening 行的实际端口。
     * <p>
     * 用 available() 轮询而非 readLine() 阻塞,避免 serve 异常挂起时永久阻塞;
     * 进程提前退出(isAlive=false)时返回已缓冲内容中解析到的端口或 null。
     */
    private Integer scanServingPort(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[256];
        try {
            java.io.InputStream in = process.getInputStream();
            while (System.currentTimeMillis() < deadline && process.isAlive()) {
                int avail = in.available();
                if (avail <= 0) {
                    Thread.sleep(50L);
                    continue;
                }
                int n = in.read(tmp, 0, Math.min(avail, tmp.length));
                if (n <= 0) {
                    break;
                }
                buf.write(tmp, 0, n);
                Integer port = tryParsePort(buf.toString(StandardCharsets.UTF_8));
                if (port != null) {
                    return port;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("[OpenCodeDaemonCoordinator] scanServingPort failed: " + e.getMessage());
        }
        return tryParsePort(buf.toString(StandardCharsets.UTF_8));
    }

    /** 从累积输出按行尝试解析端口,返回首个命中。 */
    private Integer tryParsePort(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        for (String line : content.split("\\r?\\n")) {
            Integer port = parseServingPort(line);
            if (port != null) {
                return port;
            }
        }
        return null;
    }

    /** 守护线程持续读取并丢弃 serve 输出,防止 stdout pipe 缓冲满导致 serve 写阻塞。 */
    private void startOutputDrainer(Process process) {
        Thread drainer = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream()) {
                byte[] discard = new byte[1024];
                while (in.read(discard) != -1) {
                    // discard
                }
            } catch (Exception ignored) {
                // best-effort:进程销毁后自然退出
            }
        }, "opencode-serve-drain");
        drainer.setDaemon(true);
        drainer.start();
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
