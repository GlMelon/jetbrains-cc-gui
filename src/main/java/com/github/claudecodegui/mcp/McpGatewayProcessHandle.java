package com.github.claudecodegui.mcp;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the long-lived Node Gateway process and drains its output streams.
 *
 * <p>Opt3:进程意外退出时经 {@code process.onExit()} 感知(JDK 9+ CompletableFuture),触发
 * {@link #exitCallback} 自愈(service 注入 ensureStarted 重建,根治"gateway 崩溃 → Java 零感知 →
 * 下一轮 send 等满 Opt2 的 5s 超时"的窗口)。主动 {@link #stop()} 前须 {@link #setOnExitCallback(null)}
 * 防误触发(与 {@code stop()} 内的双重清空协同)。{@link #isRestartStorm} 纯函数供 service
 * 判定风暴(30s 窗口内 >3 次)放弃自愈,避免配置错时反复崩溃拖垮 commonPool。
 */
public final class McpGatewayProcessHandle {
    private static final Logger LOG = Logger.getInstance(McpGatewayProcessHandle.class);

    private final Process process;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final InputStream stderr;
    private final Thread stdoutDrainThread;
    private final Thread stderrDrainThread;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile Runnable exitCallback;

    private McpGatewayProcessHandle(Process process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        this.stdout = process.getInputStream();
        this.stderr = process.getErrorStream();
        this.stdoutDrainThread = drain(stdout, false);
        this.stderrDrainThread = drain(stderr, true);
        // Opt3:进程退出(正常/异常)时 onExit 触发(commonPool 线程)。onProcessExit 读 exitCallback,
        // 主动 stop 已置 null 则跳过。原实现零 onExit 监听 → gateway 崩溃 Java 不感知。
        process.onExit().whenComplete((p, t) -> onProcessExit());
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

    /**
     * 设置进程意外退出时的自愈回调({@code null} 清除)。
     * <p>service 在 ensureStarted 后注入自愈逻辑;{@code stop()}/{@code stopGateway()}/{@code dispose()}
     * 前须置 {@code null},防止主动停止时 onExit 误触发自愈(与 {@code stop()} 内的双重清空协同,
     * 双重防竞态:onExit 已在飞时读到的 callback 仍可能非 null,由回调内 {@code processHandle==null} 早退兜底)。
     */
    void setOnExitCallback(Runnable callback) {
        this.exitCallback = callback;
    }

    /** 进程退出时触发(commonPool 线程)。读 exitCallback 到局部,null 则跳过;否则调自愈回调。 */
    private void onProcessExit() {
        Runnable cb = exitCallback;
        if (cb == null) {
            return;
        }
        LOG.info("[McpGateway] process exited unexpectedly (pid=" + process.pid() + ")");
        try {
            cb.run();
        } catch (Exception e) {
            LOG.warn("[McpGateway] onExit self-heal callback failed: " + e.getMessage());
        }
    }

    /** 测试钩子:直接触发 onExit 路径,验证 callback 触发/屏蔽(不需真实进程退出)。 */
    void simulateExitForTests() {
        onProcessExit();
    }

    /**
     * 判定最近 exit 时间戳是否构成重启风暴(窗口内次数 &gt; threshold)。
     * <p>纯函数,状态由 service 维护(exitTimestamps 列表)。注入 now 便于单测。
     *
     * @param recentExitEpochMs 最近 exit 时间戳列表(epoch ms)
     * @param nowEpochMs        当前时间(epoch ms)
     * @param threshold         阈值(窗口内次数 &gt; threshold 判为风暴)
     * @param windowMs          统计窗口(ms)
     * @return true 表示风暴,应放弃自愈
     */
    static boolean isRestartStorm(List<Long> recentExitEpochMs, long nowEpochMs, int threshold, long windowMs) {
        if (recentExitEpochMs == null || recentExitEpochMs.isEmpty()) {
            return false;
        }
        long cutoff = nowEpochMs - windowMs;
        int count = 0;
        for (long ts : recentExitEpochMs) {
            if (ts >= cutoff) {
                count++;
            }
        }
        return count > threshold;
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // 主动 stop:清回调防 onExit 误触发自愈(与调用方 setOnExitCallback(null) 双重协同)。
        exitCallback = null;
        try {
            closeQuietly(stdin);
            if (process.isAlive()) {
                PlatformUtils.terminateProcessAndWait(process, 3, TimeUnit.SECONDS);
            } else {
                // 进程已死(崩溃/超时被杀):gateway 死前 spawn 的 MCP server(node.exe)不会随它一起退出,
                // 成为孤儿;自愈 ensureStarted 又起新一批 → node.exe 滚雪球。按 ParentProcessId 清理遗孤
                // (Windows 子进程 ParentProcessId 在父死后仍保留,可查到孤儿)。
                PlatformUtils.cleanupChildProcesses(process.pid());
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to stop process gracefully: " + e.getMessage());
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        } finally {
            // Closing the process pipes is required even when the process has already
            // exited: a drain thread may otherwise remain blocked on read() until the
            // OS reclaims the process handle.
            closeQuietly(stdout);
            closeQuietly(stderr);
            joinQuietly(stdoutDrainThread);
            joinQuietly(stderrDrainThread);
        }
    }

    private static Thread drain(InputStream stream, boolean error) {
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
        return thread;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The process may have closed the pipe already.
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
