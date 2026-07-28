package com.github.claudecodegui.watcher;

import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.github.claudecodegui.util.JsUtils;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.Alarm;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.BiConsumer;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * 监听 {@code ~/.codemoss/config.json} 的外部修改(cc-switch 切 provider/模型),debounce 后
 * 主动把最新 {@code MODEL_REGISTRY} 下行广播到所有打开项目的前端。
 *
 * <p><b>定位(§B4 / §0.3 S3-3)</b>:功能——主动感知外部修改 + 下行推送,<b>不是</b>性能缓存。
 * {@link CodemossSettingsService} 刻意不加缓存(配置即时性优先于 ~20ms IO),本服务只做
 * 「检测 + 通知」,<b>绝不写 config</b>(避免与 {@code ConfigRepository} 的 write-time CAS 交互)。
 *
 * <p><b>底座</b>:nio {@link WatchService}(抄 {@code PermissionRequestWatcher};home 目录 VFS
 * 覆盖不可靠)+ debounce 调度器(生产用 {@link Alarm} SWING_THREAD;测试可注入不依赖 Application
 * 的实现)合并 atomic-replace / 重复事件 / OVERFLOW 抖动。
 *
 * <p><b>生命周期</b>:applicationService + {@link Disposable},IDE 关闭时由容器级联 dispose。
 *
 * <p><b>§861 坑对照</b>:atomic replace 与重复事件由 debounce 合并;OVERFLOW 强制全量刷新;
 * delete/recreate 命中 ENTRY_DELETE/CREATE(fresh read 返空则跳过广播);dispose/IDE shutdown 由
 * Disposable 兜底;不信任事件 payload,真相靠 {@link #broadcastModelRegistryToAllProjects} 的 fresh read。
 */
public class ConfigFileWatcherService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ConfigFileWatcherService.class);
    private static final String CONFIG_FILE_NAME = "config.json";
    /** 默认 debounce(毫秒):合并 atomic-replace / 重复事件抖动。包级可见供测试引用。 */
    static final long DEFAULT_DEBOUNCE_MS = 200;
    private static final int ERROR_RETRY_DELAY_MS = 1000;
    private static final long THREAD_JOIN_MS = 1000;

    /** 应用级单例(applicationService)。 */
    public static ConfigFileWatcherService getInstance() {
        return ApplicationManager.getApplication().getService(ConfigFileWatcherService.class);
    }

    private final Disposable parentDisposable;
    /** 生产 debounce 调度器所用的 Alarm(测试为 null,改用注入的 scheduler)。 */
    private final Alarm alarm;
    /** debounce + 线程切换调度器:接收实际回调与 delay,负责 cancel-reschedule。 */
    private final BiConsumer<Runnable, Long> scheduler;
    private final long debounceMs;
    /** config 变更 debounce 后调用(生产=广播 MODEL_REGISTRY;测试=CountDownLatch)。 */
    private final Runnable onChangeCallback;

    private volatile boolean running = false;
    private volatile boolean disposed = false;
    private WatchService watchService;
    private Thread watchThread;

    /**
     * 容器构造(applicationService):默认 debounce + Alarm 调度 + 生产广播回调。<b>不启动</b>——
     * 等 {@link #ensureStarted(Path)} 传入 configDir(由 {@link CodemossSettingsService} 构造期注入,
     * 避免与 CSS 互相 {@code getInstance()} 形成构造期循环)。
     */
    public ConfigFileWatcherService() {
        this.parentDisposable = Disposer.newDisposable("ConfigFileWatcherService");
        Alarm a = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable);
        this.alarm = a;
        this.debounceMs = DEFAULT_DEBOUNCE_MS;
        this.onChangeCallback = this::broadcastModelRegistryToAllProjects;
        this.scheduler = (task, delay) -> {
            a.cancelAllRequests();
            a.addRequest(task, delay);
        };
    }

    /**
     * 测试构造:注入 debounce、回调与调度器(调度器不依赖 Application,便于纯 JUnit 验证)。
     *
     * @param debounceMs      debounce 毫秒(测试用 50ms 加速)
     * @param onChangeCallback config 变更 debounce 后触发的回调(测试用 {@code latch::countDown})
     * @param scheduler       trailing-edge debounce 调度器(测试用 ScheduledExecutorService 实现)
     */
    ConfigFileWatcherService(long debounceMs, Runnable onChangeCallback, BiConsumer<Runnable, Long> scheduler) {
        this.parentDisposable = Disposer.newDisposable("ConfigFileWatcherService");
        this.alarm = null;
        this.debounceMs = debounceMs;
        this.onChangeCallback = onChangeCallback;
        this.scheduler = scheduler;
    }

    /**
     * 幂等启动:注册 configDir 并启动 watch 线程。目录不存在则创建。已运行/已 dispose 则跳过。
     */
    public synchronized void ensureStarted(Path configDir) {
        if (running || disposed) {
            return;
        }
        try {
            Files.createDirectories(configDir);
            watchService = FileSystems.getDefault().newWatchService();
            configDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            LOG.warn("[ConfigFileWatcher] Failed to register WatchService on " + configDir
                    + ": " + e.getMessage(), e);
            return;
        }
        running = true;
        watchThread = new Thread(this::watchLoop, "ConfigFileWatcher");
        watchThread.setDaemon(true);
        watchThread.start();
        LOG.debug("[ConfigFileWatcher] Started watching " + configDir);
    }

    /**
     * watch 线程主循环:阻塞 {@code take()} → drain 事件 → 过滤 config.json / OVERFLOW → debounce。
     * {@link ClosedWatchServiceException} 为 dispose 期间预期退出。
     */
    private void watchLoop() {
        while (running && !disposed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException e) {
                // dispose 期间 watchService.close() 触发,干净退出。
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            boolean configChanged = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == OVERFLOW) {
                    configChanged = true; // 强制全量刷新(config 场景不能漏)
                    continue;
                }
                Object context = event.context();
                if (context != null && CONFIG_FILE_NAME.equals(context.toString())) {
                    configChanged = true;
                }
            }
            key.reset();

            if (configChanged) {
                scheduleRefresh();
            }
        }
    }

    /**
     * nio 线程调用:经注入的 scheduler 做 trailing-edge debounce(合并连续抖动),delay 后在调度线程
     * 触发 onChangeCallback(生产=Alarm→EDT)。dispose 竞态由 catch 兜底。
     */
    private void scheduleRefresh() {
        if (disposed) {
            return;
        }
        try {
            scheduler.accept(onChangeCallback, debounceMs);
        } catch (Exception e) {
            // dispose 竞态:调度器已释放后调用抛异常,安全吞掉。
            LOG.debug("[ConfigFileWatcher] scheduleRefresh skipped: " + e.getMessage());
        }
    }

    /**
     * 生产回调:fresh read registry + 广播到所有打开项目的全部标签。
     * 不缓存——每次都 {@link CodemossSettingsService#getModelRegistryJson()} 现读。
     * config 被删/空时返回 null → 跳过广播(下次插件访问 CSS 会重建 default)。
     */
    private void broadcastModelRegistryToAllProjects() {
        if (disposed) {
            return;
        }
        String registryJson;
        try {
            registryJson = CodemossSettingsService.getInstance().getModelRegistryJson();
        } catch (Exception e) {
            LOG.warn("[ConfigFileWatcher] Failed to read model registry after external config change: "
                    + e.getMessage(), e);
            return;
        }
        if (registryJson == null || registryJson.trim().isEmpty()) {
            return;
        }
        String escaped = JsUtils.escapeJs(registryJson);
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            try {
                ClaudeSDKToolWindow.broadcastModelRegistry(
                        project, DownstreamEvent.MODEL_REGISTRY.value(), escaped);
            } catch (Exception e) {
                LOG.warn("[ConfigFileWatcher] Failed to broadcast model registry to project "
                        + project.getName() + ": " + e.getMessage(), e);
            }
        }
    }

    @Override
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        running = false;
        if (watchService != null) {
            try {
                watchService.close(); // 让阻塞中的 take() 抛 ClosedWatchServiceException 退出
            } catch (IOException e) {
                LOG.warn("[ConfigFileWatcher] Error closing WatchService", e);
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(THREAD_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            Disposer.dispose(parentDisposable); // 级联释放 Alarm
        } catch (Exception ignored) {
            // 已释放(如容器级联),安全。
        }
    }
}
