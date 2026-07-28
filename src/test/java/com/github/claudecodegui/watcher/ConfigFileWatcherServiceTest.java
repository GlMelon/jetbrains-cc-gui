package com.github.claudecodegui.watcher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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
 * {@link ConfigFileWatcherService} 单测:nio WatchService 检测层 + trailing-edge debounce。
 *
 * <p>注入 {@code tempDir + 50ms debounce + ScheduledExecutorService scheduler}(不依赖 Application,
 * 因 Alarm 在纯 JUnit 无 Application 上下文不调度;IDE 内生产仍用 Alarm)。应用级
 * {@code getOpenProjects} 广播部分依赖 Application 上下文(同 {@code PermissionRequestWatcher.watchLoop}
 * 不测的先例),不在本测覆盖,改由手动验证(runIde)。
 *
 * <p>跨平台 WatchService 事件有延迟,断言用 {@code latch.await(3s)};{@code @Test(timeout=30000)}
 * 兜底防回归挂死。
 */
public class ConfigFileWatcherServiceTest {

    private static final long TEST_DEBOUNCE_MS = 50;

    private Path configDir;
    private ScheduledExecutorService debouncerExec;
    private ConfigFileWatcherService service;

    @Before
    public void setUp() throws Exception {
        configDir = Files.createTempDirectory("configwatcher-test");
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
        if (configDir != null && Files.exists(configDir)) {
            Files.walk(configDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
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

    /** 外部写 config.json → debounce 后回调触发。 */
    @Test(timeout = 30000)
    public void externalWriteTriggersCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        newService(latch::countDown).ensureStarted(configDir);
        Files.writeString(configDir.resolve("config.json"), "{\"provider\":\"claude\"}");
        assertTrue("watcher did not fire on external config write",
                latch.await(3, TimeUnit.SECONDS));
    }

    /** 紧凑多次写同一文件 → debounce 合并成单次回调(trailing edge)。 */
    @Test(timeout = 30000)
    public void debounceMergesMultipleWrites() throws Exception {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch firstFire = new CountDownLatch(1);
        newService(() -> {
            count.incrementAndGet();
            firstFire.countDown();
        }).ensureStarted(configDir);
        Path config = configDir.resolve("config.json");
        for (int i = 0; i < 5; i++) {
            Files.writeString(config, "{\"i\":" + i + "}");
        }
        assertTrue("debounced callback did not fire", firstFire.await(3, TimeUnit.SECONDS));
        // 等 debounce 窗口之外的二次触发过期,确认未重复触发。
        Thread.sleep(TEST_DEBOUNCE_MS + 200);
        assertEquals("expected single debounced callback", 1, count.get());
    }

    /** 写无关文件(other.json)→ 回调不触发。 */
    @Test(timeout = 30000)
    public void unrelatedFileDoesNotTrigger() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        newService(latch::countDown).ensureStarted(configDir);
        Files.writeString(configDir.resolve("other.json"), "{}");
        // > debounce 窗口:若会误触发,此时已 countDown。
        assertFalse("watcher fired on unrelated file",
                latch.await(1, TimeUnit.SECONDS));
    }

    /** dispose 后再写 config.json → 回调不再触发。 */
    @Test(timeout = 30000)
    public void noTriggerAfterDispose() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        newService(latch::countDown).ensureStarted(configDir);
        service.dispose();
        Thread.sleep(100); // 确保 watch 线程已退出 + 调度器已停
        Files.writeString(configDir.resolve("config.json"), "{}");
        assertFalse("watcher fired after dispose",
                latch.await(1, TimeUnit.SECONDS));
    }
}
