package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.cli.common.UserPathResolver;
import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.github.claudecodegui.mcp.McpGatewayCliConfig;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;
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

    /** serve 是每 project 一个 daemon 进程(McpGatewayService project-scoped),用稳定 tabId 定位 temp config 目录。 */
    private static final String SERVE_TAB_ID = "serve";

    private final Logger log;
    /** §gateway:SDK gateway 服务(可为 null,如非 project 上下文的测试/历史构造)。null→serve 不带 gateway env。 */
    private final McpGatewayService mcpGatewayService;
    /** §gateway:project 根路径,buildSdkServeConfig 经此定位 gateway 进程 + MCP 收集。 */
    private final String projectPath;
    private final AtomicReference<OpenCodeServerInstance> serverInstance = new AtomicReference<>();
    private volatile long daemonRetryAfter = 0;
    private final Object daemonLock = new Object();

    OpenCodeDaemonCoordinator(Logger log, McpGatewayService mcpGatewayService, String projectPath) {
        this.log = log;
        this.mcpGatewayService = mcpGatewayService;
        this.projectPath = projectPath;
    }

    /**
     * serve 应固化的 gateway revision 维度(纯函数,static 便于无 Platform 上下文单测)。
     * <p>
     * 与 Claude/Codex 的 per-query revision 防漂移对称:OpenCode serve 是长驻进程,在启动期固化
     * MCP(gateway env),revision 变化(MCP 设置增删/改动)时需重启 serve 加载新工具集。
     * 不可用(功能关闭/未就绪/无 configPath/入参 null)→ -1:serve 不带 gateway,回退自身
     * opencode.json 的真实 MCP(与 CLI 关闭时的行为一致)。
     *
     * @param cfg buildSdkServeConfig 产出的 OpenCode serve gateway 配置;null 视为不可用
     * @return 可用时返回其 revision;否则 -1
     */
    static long serveRevisionOf(McpGatewayCliConfig cfg) {
        if (cfg == null || !cfg.usable()) {
            return -1L;
        }
        return cfg.revision();
    }

    /** §gateway:为 serve 构建 SDK env 配置(OPENCODE);service 为 null 或功能关闭→disabled(revision -1)。 */
    private McpGatewayCliConfig buildServeGatewayConfig() {
        if (mcpGatewayService == null) {
            return McpGatewayCliConfig.disabled("No MCP Gateway service");
        }
        return mcpGatewayService.buildSdkServeConfig(ProviderType.OPENCODE, SERVE_TAB_ID, projectPath);
    }

    /**
     * Get or start the OpenCode server. Returns the server URL if available.
     */
    String getServerUrl() {
        // §gateway:每次解析都重建 serve gateway 配置(含 applySnapshot→revision 可能 bump),
        // 用于检测 MCP 设置漂移:健康且 servedRevision 匹配则复用 serve,否则重启以固化新 gateway env。
        McpGatewayCliConfig gatewayConfig = buildServeGatewayConfig();
        long gatewayRevision = serveRevisionOf(gatewayConfig);

        OpenCodeServerInstance current = serverInstance.get();
        if (current != null && current.isHealthy()
                && current.servedRevision() == gatewayRevision) {
            return current.url();
        }
        if (System.currentTimeMillis() < daemonRetryAfter) {
            return null;
        }

        synchronized (daemonLock) {
            current = serverInstance.get();
            if (current != null && current.isHealthy()
                    && current.servedRevision() == gatewayRevision) {
                return current.url();
            }

            daemonRetryAfter = System.currentTimeMillis() + DAEMON_RETRY_DELAY_MS;
            try {
                if (current != null) {
                    current.stop();
                }

                OpenCodeServerInstance newInstance = startServer(gatewayConfig, gatewayRevision);
                if (newInstance != null) {
                    serverInstance.set(newInstance);
                    daemonRetryAfter = 0;
                    log.info("[OpenCodeDaemonCoordinator] Server started at " + newInstance.url()
                            + " | gatewayRevision=" + gatewayRevision);
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

    private OpenCodeServerInstance startServer(McpGatewayCliConfig gatewayConfig, long gatewayRevision) {
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
            // §gateway:SDK gateway 开启时,注入 buildSdkServeConfig 产出的 env(2026-07-02 重构后为
            // OPENCODE_CONFIG_CONTENT inline JSON,运行时与真实 opencode.json 合并:注入 melon_gateway 聚合
            // 入口 + 逐个禁真实 server)。HOME/XDG 保持真实(零临时 home),serve 启动期经合并后的配置固化 MCP,
            // 聚合后的 melon_gateway 成为唯一 MCP 工具集。gateway 关闭(gatewayRevision=-1,gatewayConfig 不可用)
            // 时不注入,serve 用真实 ~/.config/opencode/opencode.json 的 MCP(与 CLI 关闭路径行为一致)。
            if (gatewayConfig != null && gatewayConfig.usable()) {
                gatewayConfig.environment().forEach((k, v) -> pb.environment().put(k, v));
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
                return new OpenCodeServerInstance(process, url, gatewayRevision);
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

    /**
     * §gateway:加 {@code servedRevision} 第 3 参——serve 启动期固化的 gateway revision 快照。
     * getServerUrl 用它与最新 gatewayRevision 比对检测漂移(与 Claude/Codex 的 per-query
     * revision 防漂移对称,只是 OpenCode 的"重建"对象是 serve 进程而非 per-query 实例)。
     */
    record OpenCodeServerInstance(Process process, String url, long servedRevision) {
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
