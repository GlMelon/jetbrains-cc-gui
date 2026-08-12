package com.github.claudecodegui.util;

import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * 验证 {@link PlatformUtils#cleanupChildProcesses(long)}:
 * <ol>
 *   <li>父进程存活时,清理其子进程树;</li>
 *   <li>父进程被杀后,仍能按 ParentProcessId 清理它遗留的孤儿子进程
 *       (Windows 子进程 ParentProcessId 在父死后保留 —— 这是修复 MCP gateway
 *       崩溃自愈滚雪球 + CLI {@code .cmd} shim 漏杀的关键机制)。</li>
 * </ol>
 *
 * <p>Windows-only:底层依赖 wmic + taskkill;非 Windows 平台经 {@link Assume#assumeTrue} 跳过。
 */
public class PlatformUtilsCleanupChildProcessesTest {

    /** 启动 {@code cmd /c ping -t},形成 cmd.exe → ping.exe 子树。ping.exe 是 System32 标准组件。 */
    private Process spawnCmdPingTree() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "ping -t 127.0.0.1");
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * 某 PID 的存活直接子进程 PID 列表。
     * 用 ProcessHandle API 独立交叉验证,不依赖被测的 wmic 路径。
     */
    private List<Long> liveChildren(long pid) {
        List<Long> pids = new ArrayList<>();
        ProcessHandle.of(pid).ifPresent(h ->
                h.children().filter(ProcessHandle::isAlive).forEach(c -> pids.add(c.pid())));
        return pids;
    }

    private void awaitUntil(long timeoutMs, BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
    }

    @Test
    public void cleanupChildProcesses_terminatesLiveChildTree() throws Exception {
        Assume.assumeTrue("Windows-only (wmic/taskkill)", PlatformUtils.isWindows());

        Process cmd = spawnCmdPingTree();
        long cmdPid = cmd.pid();
        try {
            // 等 ping 子进程起来
            awaitUntil(3000, () -> !liveChildren(cmdPid).isEmpty());
            assertFalse("cmd should have a live child (ping)", liveChildren(cmdPid).isEmpty());

            PlatformUtils.cleanupChildProcesses(cmdPid);

            // 子进程(ping)应被清理
            awaitUntil(3000, () -> liveChildren(cmdPid).isEmpty());
            assertTrue("child ping should be terminated", liveChildren(cmdPid).isEmpty());
        } finally {
            PlatformUtils.cleanupChildProcesses(cmdPid);
            cmd.destroyForcibly();
        }
    }

    @Test
    public void cleanupChildProcesses_clearsOrphansAfterParentKilled() throws Exception {
        Assume.assumeTrue("Windows-only (wmic/taskkill)", PlatformUtils.isWindows());

        Process cmd = spawnCmdPingTree();
        long cmdPid = cmd.pid();
        awaitUntil(3000, () -> !liveChildren(cmdPid).isEmpty());
        List<Long> children = liveChildren(cmdPid);
        assertFalse("cmd should have a live child (ping)", children.isEmpty());
        long pingPid = children.get(0);

        try {
            // 杀父(cmd),留孤 ping —— 模拟 MCP gateway 崩溃后其 MCP server 子进程成孤儿
            new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(cmdPid))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
            awaitUntil(2000, () -> !alive(cmdPid));

            // ping 仍存活(孤儿)—— 父死后其 ParentProcessId 仍指向 cmdPid
            assertTrue("orphaned ping should still be alive after parent killed", alive(pingPid));

            // 关键断言:对已死的父 PID 调 cleanupChildProcesses,应按 ParentProcessId 清掉孤儿 ping。
            // 这是修复 gateway 自愈滚雪球的核心:父已死时仍可清遗孤。
            PlatformUtils.cleanupChildProcesses(cmdPid);

            awaitUntil(3000, () -> !alive(pingPid));
            assertFalse("orphaned ping should be cleaned up by cleanupChildProcesses(dead parentPid)",
                    alive(pingPid));
        } finally {
            ProcessHandle.of(pingPid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
