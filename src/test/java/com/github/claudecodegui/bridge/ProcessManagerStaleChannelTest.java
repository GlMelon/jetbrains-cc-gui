package com.github.claudecodegui.bridge;

import com.github.claudecodegui.session.runtime.RuntimeKey;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 测试 {@link ProcessManager#cleanupStaleChannelProcesses} 兜底清理逻辑。
 *
 * <p>S3 watchdog 核心:自动检测长时间存活(超阈值)的 channel 进程并终止,
 * 防止线程被 kill 等异常场景下进程泄漏;同时摘除已死进程残留的账本条目,
 * 防止 activeChannelProcesses/channelStartTimes 无限增长。</p>
 */
public class ProcessManagerStaleChannelTest {

    @Test
    public void cleanupStaleChannelProcessesRemovesOldAliveProcess() {
        ProcessManager manager = new ProcessManager();
        FakeProcess process = new FakeProcess(true);
        String channelId = "test-channel";
        long now = System.currentTimeMillis();

        manager.registerProcess(channelId, process);
        // 模拟进程已存活超过阈值(PlatformUtils.terminateProcess 在测试中用 taskkill,不设 destroyed 标志,
        // 故只验证 cleaned 计数,不验证 destroyed——PlatformUtils 的终止行为是其自身职责,watchdog 只负责检测+调用)
        int cleaned = manager.cleanupStaleChannelProcesses(100, now + 200);

        assertEquals(1, cleaned);
    }

    @Test
    public void cleanupStaleChannelProcessesSkipsYoungProcess() {
        ProcessManager manager = new ProcessManager();
        FakeProcess process = new FakeProcess(true);
        String channelId = "test-channel";
        long now = System.currentTimeMillis();

        manager.registerProcess(channelId, process);
        // 进程刚注册,未超时
        int cleaned = manager.cleanupStaleChannelProcesses(100_000, now);

        assertEquals(0, cleaned);
    }

    @Test
    public void cleanupStaleChannelProcessesRemovesDeadProcessLedgerEntry() {
        ProcessManager manager = new ProcessManager();
        FakeProcess process = new FakeProcess(false);
        String channelId = "test-channel";

        manager.registerProcess(channelId, process);
        // 已死进程的账本条目也应被 sweeper 摘除(无需等 maxAge),否则 activeChannelProcesses /
        // channelStartTimes 会无限增长
        int cleaned = manager.cleanupStaleChannelProcesses(100, System.currentTimeMillis() + 200);

        assertEquals(1, cleaned);
        // 条目已移除:再次 sweep 不应再找到它
        assertEquals(0, manager.cleanupStaleChannelProcesses(100, System.currentTimeMillis() + 400));
        assertEquals(0, manager.getActiveProcessCount());
    }

    @Test
    public void unregisterProcessRemovesStartTime() {
        ProcessManager manager = new ProcessManager();
        FakeProcess process = new FakeProcess(true);
        String channelId = "test-channel";

        manager.registerProcess(channelId, process);
        manager.unregisterProcess(channelId, process);

        // unregister 后清理不应再找到该进程
        int cleaned = manager.cleanupStaleChannelProcesses(100, System.currentTimeMillis() + 200);
        assertEquals(0, cleaned);
    }

    @Test
    public void failedChannelStartClearsPendingInterrupt() {
        ProcessManager manager = new ProcessManager();
        String channelId = "failed-start";

        manager.beginChannelPreservingInterrupt(channelId);
        manager.interruptChannel(channelId);
        manager.finishChannelStart(channelId, true);

        assertFalse(manager.wasInterrupted(channelId));
    }

    @Test
    public void failedChannelStartDoesNotClearInterruptForRegisteredProcess() {
        ProcessManager manager = new ProcessManager();
        String channelId = "registered-process";
        FakeProcess process = new FakeProcess(true);

        manager.beginChannelPreservingInterrupt(channelId);
        manager.interruptChannel(channelId);
        manager.registerProcess(channelId, process);
        manager.finishChannelStart(channelId, true);

        assertTrue(manager.wasInterrupted(channelId));
        manager.unregisterProcess(channelId, process);
    }

    @Test
    public void cleanupStaleChannelProcessesMultipleChannels() throws InterruptedException {
        ProcessManager manager = new ProcessManager();
        FakeProcess staleProc = new FakeProcess(true);
        FakeProcess freshProc = new FakeProcess(true);

        // stale 早注册 200ms > maxAge(150ms)
        manager.registerProcess("stale", staleProc);
        Thread.sleep(200);

        long now = System.currentTimeMillis();
        // fresh 的 startTime = now,age = (now+100) - now = 100ms < 150ms → 跳过
        manager.registerProcess("fresh", freshProc);

        int cleaned = manager.cleanupStaleChannelProcesses(150, now + 100);

        assertEquals(1, cleaned);
        // PlatformUtils.terminateProcess 在测试中不设 destroyed 标志,故不验证 destroyed
    }

    @Test
    public void reapDeadRuntimeProcessesRemovesOnlyDeadEntries() {
        ProcessManager manager = new ProcessManager();
        RuntimeKey deadKey = new RuntimeKey("claude", "channel-a", "tab-a", "epoch-a");
        RuntimeKey aliveKey = new RuntimeKey("claude", "channel-b", "tab-b", "epoch-b");
        FakeProcess deadProc = new FakeProcess(false);
        FakeProcess aliveProc = new FakeProcess(true);

        manager.registerProcess(deadKey, deadProc);
        manager.registerProcess(aliveKey, aliveProc);
        int sizeBefore = manager.getTrackedStateSizeForTest();

        manager.reapDeadRuntimeProcesses();

        // 已死条目被摘除,存活条目保留(不主动 kill)
        assertNull(manager.getProcess(deadKey));
        assertSame(aliveProc, manager.getProcess(aliveKey));
        assertTrue(aliveProc.isAlive());
        assertEquals(sizeBefore - 1, manager.getTrackedStateSizeForTest());
        // 幂等:再次摘除无变化
        manager.reapDeadRuntimeProcesses();
        assertEquals(sizeBefore - 1, manager.getTrackedStateSizeForTest());
    }

    @Test
    public void reapDeadAuxiliaryProcessesRemovesOnlyDeadEntries() {
        ProcessManager manager = new ProcessManager();
        FakeProcess deadProc = new FakeProcess(false);
        FakeProcess aliveProc = new FakeProcess(true);

        String deadToken = manager.registerAuxiliaryProcess(deadProc);
        String aliveToken = manager.registerAuxiliaryProcess(aliveProc);
        int sizeBefore = manager.getTrackedStateSizeForTest();

        manager.reapDeadAuxiliaryProcesses();

        assertEquals(sizeBefore - 1, manager.getTrackedStateSizeForTest());
        assertTrue(aliveProc.isAlive());
        // 存活条目仍可被正常 unregister(条件 remove 未被兜底误摘)
        manager.unregisterAuxiliaryProcess(aliveToken, aliveProc);
        assertEquals(sizeBefore - 2, manager.getTrackedStateSizeForTest());
    }

    @Test
    public void pruneStaleInterruptMarkersRemovesOrphanedChannelMarker() {
        ProcessManager manager = new ProcessManager();
        String channelId = "orphaned-interrupt";

        manager.beginChannelPreservingInterrupt(channelId);
        manager.interruptChannel(channelId);
        // 启动尚未失败也未注册即结束(startupFailed=false 保留标记)→ 标记成为孤儿
        manager.finishChannelStart(channelId, false);

        manager.pruneStaleInterruptMarkers();

        assertFalse(manager.wasInterrupted(channelId));
    }

    @Test
    public void pruneStaleInterruptMarkersKeepsStartingChannelMarker() {
        ProcessManager manager = new ProcessManager();
        String channelId = "starting-interrupt";

        manager.beginChannelPreservingInterrupt(channelId);
        manager.interruptChannel(channelId);

        // 仍在 startingChannels 待注册窗口内:registerProcess 的预注册取消检查仍可能消费该标记,不摘
        manager.pruneStaleInterruptMarkers();

        assertTrue(manager.wasInterrupted(channelId));
        manager.finishChannelStart(channelId, false);
    }

    @Test
    public void pruneStaleInterruptMarkersKeepsMarkerForRegisteredChannel() {
        ProcessManager manager = new ProcessManager();
        String channelId = "registered-interrupt";
        FakeProcess process = new FakeProcess(true);

        manager.beginChannelPreservingInterrupt(channelId);
        manager.interruptChannel(channelId);
        manager.registerProcess(channelId, process);

        // 账本条目仍在(进程已注册):标记保留,不摘
        manager.pruneStaleInterruptMarkers();

        assertTrue(manager.wasInterrupted(channelId));
        manager.unregisterProcess(channelId, process);
    }

    @Test
    public void pruneStaleInterruptMarkersRemovesOrphanedRuntimeMarker() {
        ProcessManager manager = new ProcessManager();
        RuntimeKey key = new RuntimeKey("claude", "channel-a", "tab-a", "epoch-a");
        FakeProcess process = new FakeProcess(true);

        manager.registerProcess(key, process);
        // interruptRuntime 终止进程并移除账本条目;若 owner 不再调用 cleanupRuntime /
        // wasInterrupted,标记将永久驻留
        manager.interruptRuntime(key);

        manager.pruneStaleInterruptMarkers();

        assertFalse(manager.wasInterrupted(key));
    }

    /** 最小 Process 实现,支持存活/死亡状态模拟。 */
    private static class FakeProcess extends Process {
        private boolean alive;
        public boolean destroyed = false;

        FakeProcess(boolean alive) {
            this.alive = alive;
        }

        @Override
        public java.io.OutputStream getOutputStream() {
            return java.io.OutputStream.nullOutputStream();
        }
        @Override
        public java.io.InputStream getInputStream() {
            return java.io.InputStream.nullInputStream();
        }
        @Override
        public java.io.InputStream getErrorStream() {
            return java.io.InputStream.nullInputStream();
        }
        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }
        @Override
        public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
            alive = false;
            return true;
        }
        @Override
        public int exitValue() {
            return 0;
        }
        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }
        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }
        @Override
        public boolean isAlive() {
            return alive;
        }
        @Override
        public long pid() {
            return 999999999L; // 伪PID,避免PlatformUtils.terminateProcess的taskkill/F误杀当前JVM测试进程
        }
    }
}