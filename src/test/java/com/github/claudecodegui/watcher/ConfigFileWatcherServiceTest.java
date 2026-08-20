package com.github.claudecodegui.watcher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConfigFileWatcherService} 单测:trailing-edge debounce 逻辑。
 *
 * <p>注入 {@code 50ms debounce + ScheduledExecutorService scheduler}(不依赖 Application,
 * 因 Alarm 在纯 JUnit 无 Application 上下文不调度;IDE 内生产仍用 Alarm)。应用级
 * {@code getOpenProjects} 广播部分依赖 Application 上下文(同 {@code PermissionRequestWatcher.watchLoop}
 * 不测的先例),不在本测覆盖,改由手动验证(runIde)。
 *
 * <p>VFS 事件检测层依赖 IntelliJ 平台上下文,无法在纯 JUnit 中测试,改由集成测试覆盖。
 * 本测只验证 debounce 调度逻辑的正确性。
 *
 * <p>{@code @Test(timeout=30000)} 兜底防回归挂死。
 */
public class ConfigFileWatcherServiceTest {

    private static final long TEST_DEBOUNCE_MS = 50;

    private ScheduledExecutorService debouncerExec;
    private ConfigFileWatcherService service;

    @Before
    public void setUp() throws Exception {
        // No-op: configDir no longer needed for VFS-based implementation
    }

    @After
    public void tearDown() throws Exception {
        if (service != null) {
            service.dispose();
            service = null;
        }
        if (debouncerExec != null) {
            debouncerExec.shutdownNow();
            debouncerExec = null;
        }
    }

    /**
     * 构造 service + 一个不依赖 Application 的 trailing-edge debounce scheduler
     * (ScheduledExecutorService + AtomicReference cancel-reschedule)。
     */
    private ConfigFileWatcherService newService(Runnable onChangeCallback) {
        debouncerExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-config-debouncer");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
        BiConsumer<Runnable, Long> scheduler = (task, delay) -> {
            ScheduledFuture<?> prev = pending.getAndSet(null);
            if (prev != null) {
                prev.cancel(false);
            }
            pending.set(debouncerExec.schedule(task, delay, TimeUnit.MILLISECONDS));
        };
        service = new ConfigFileWatcherService(TEST_DEBOUNCE_MS, onChangeCallback, scheduler);
        return service;
    }

    /** debounce 调度器:连续触发 → 合并成单次回调(trailing edge)。 */
    @Test(timeout = 30000)
    public void debounceMergesMultipleTriggers() throws Exception {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch firstFire = new CountDownLatch(1);
        newService(() -> {
            count.incrementAndGet();
            firstFire.countDown();
        });

        // 模拟多次快速触发(通过 scheduleRefresh 间接调用)
        // 由于 VFS 事件需要平台上下文,这里直接测试 scheduler 的 debounce 行为
        for (int i = 0; i < 5; i++) {
            service.scheduleRefresh();
        }

        assertTrue("debounced callback did not fire", firstFire.await(3, TimeUnit.SECONDS));
        // 等 debounce 窗口之外的二次触发过期,确认未重复触发。
        Thread.sleep(TEST_DEBOUNCE_MS + 200);
        assertEquals("expected single debounced callback", 1, count.get());
    }

    /** dispose 后再触发 → 回调不再触发。 */
    @Test(timeout = 30000)
    public void noTriggerAfterDispose() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        newService(latch::countDown);
        service.dispose();
        Thread.sleep(100); // 确保调度器已停
        service.scheduleRefresh();
        assertFalse("watcher fired after dispose",
                latch.await(1, TimeUnit.SECONDS));
    }
}
