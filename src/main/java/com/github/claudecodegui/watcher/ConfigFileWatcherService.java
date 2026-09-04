package com.github.claudecodegui.watcher;

import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 监听 {@code ~/.codemoss/config.json} 的外部修改(cc-switch 切 provider/模型),debounce 后
 * 主动把最新 {@code MODEL_REGISTRY} 下行广播到所有打开项目的前端。
 *
 * <p><b>定位(§B4 / §0.3 S3-3)</b>:功能——主动感知外部修改 + 下行推送,<b>不是</b>性能缓存。
 * {@link CodemossSettingsService} 刻意不加缓存(配置即时性优先于 ~20ms IO),本服务只做
 * 「检测 + 通知」,<b>绝不写 config</b>(避免与 {@code ConfigRepository} 的 write-time CAS 交互)。
 *
 * <p><b>底座</b>:IntelliJ {@link VirtualFileManager} + {@link BulkFileListener}(
 * 与 {@link PromptFileWatcher} 保持一致), debounce 调度器(生产用 {@link Alarm} SWING_THREAD;
 * 测试可注入不依赖 Application 的实现)合并 atomic-replace / 重复事件抖动。
 *
 * <p><b>生命周期</b>:applicationService + {@link Disposable},IDE 关闭时由容器级联 dispose。
 *
 * <p><b>§861 坑对照</b>:atomic replace 与重复事件由 debounce 合并;delete/recreate 命中
 * VFileDeleteEvent/VFileCreateEvent(fresh read 返空则跳过广播);dispose/IDE shutdown 由
 * Disposable 兜底;不信任事件 payload,真相靠 {@link #broadcastModelRegistryToAllProjects} 的 fresh read。
 */
public class ConfigFileWatcherService implements Disposable, BulkFileListener {

    private static final Logger LOG = Logger.getInstance(ConfigFileWatcherService.class);
    /** 默认 debounce(毫秒):合并 atomic-replace / 重复事件抖动。包级可见供测试引用。 */
    static final long DEFAULT_DEBOUNCE_MS = 200;

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

    private volatile boolean disposed = false;
    private volatile boolean started = false;
    private MessageBusConnection connection;
    private String watchedConfigPath;

    /**
     * 容器构造(applicationService):默认 debounce + Alarm 调度 + 生产广播回调。<b>不启动</b>——
     * 等 {@link #ensureStarted(String)} 传入 configDirPath(由 {@link CodemossSettingsService} 构造期注入,
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
     * 幂等启动:订阅 VFS 变更事件。已启动/已 dispose 则跳过。
     *
     * @param configDirPath 监听的配置目录路径(如 ~/.codemoss)
     */
    public synchronized void ensureStarted(String configDirPath) {
        if (started || disposed) {
            return;
        }

        this.watchedConfigPath = configDirPath + "/" + "config.json";

        // 在应用级消息总线上订阅 VFS 变更事件
        connection = ApplicationManager.getApplication().getMessageBus().connect(parentDisposable);
        connection.subscribe(VirtualFileManager.VFS_CHANGES, this);

        started = true;
        LOG.debug("[ConfigFileWatcher] Started watching " + watchedConfigPath + " via VFS API");
    }

    /**
     * BulkFileListener 回调:处理 VFS 文件变更事件。
     * 过滤 config.json 的创建/修改/删除事件,debounce 后触发刷新。
     */
    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        if (disposed || watchedConfigPath == null) {
            return;
        }

        boolean configChanged = false;
        for (VFileEvent event : events) {
            VirtualFile file = event.getFile();
            if (file == null) {
                continue;
            }

            String filePath = file.getPath();
            if (!watchedConfigPath.equals(filePath)) {
                continue;
            }

            // 处理所有类型的文件变更事件
            if (event instanceof VFileContentChangeEvent ||
                event instanceof VFileCreateEvent ||
                event instanceof VFileDeleteEvent) {
                configChanged = true;
                LOG.debug("[ConfigFileWatcher] Detected config change: " + event.getClass().getSimpleName());
                break;
            }
        }

        if (configChanged) {
            scheduleRefresh();
        }
    }

    /**
     * debounce 调度:经注入的 scheduler 做 trailing-edge debounce(合并连续抖动),delay 后在调度线程
     * 触发 onChangeCallback(生产=Alarm→EDT)。dispose 竞态由 catch 兜底。
     * 包级可见供测试调用。
     */
    void scheduleRefresh() {
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
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            try {
                ClaudeChatToolWindow.broadcastModelRegistry(
                        project, DownstreamEvent.MODEL_REGISTRY.value(), registryJson);
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
        started = false;

        // 断开 VFS 消息总线连接
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
                // 已释放,安全
            }
            connection = null;
        }

        try {
            Disposer.dispose(parentDisposable); // 级联释放 Alarm
        } catch (Exception ignored) {
            // 已释放(如容器级联),安全。
        }

        LOG.debug("[ConfigFileWatcher] Disposed");
    }
}
