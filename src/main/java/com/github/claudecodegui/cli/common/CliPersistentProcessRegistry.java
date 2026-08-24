package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.cli.CliSessionExecutor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 长驻 CLI 进程注册表(设计文档 §4.4/§4.5,Phase 1: claude)。
 *
 * <p>槽位模型:tab = 进程的物理隔离——{@code (tabId, provider)} 二级键(与
 * {@code CliSessionManager.sessions} 结构对齐)各至多一个 {@link CliPersistentProcess}。
 * 无跨会话队列:同 tab 内消息由上层 per-tab inFlight 链天然串行,跨 tab 完全并行。
 *
 * <p>acquire 语义(§3.2 静默加载策略):
 * <ul>
 *   <li>槽位命中且可用且指纹匹配 → 返回进程(次轮 0.2s);</li>
 *   <li>tab 首条消息(无槽位无历史)→ 同步 spawn 一次性 ~3.4s,与 one-shot 现状相同不劣化;</li>
 *   <li>其余未命中(指纹漂移/进程崩溃/空闲回收后/超限/冷却)→ 返回 null,上层当前消息走
 *       one-shot 并调 {@link #rebuildInBackground} 静默重建,下条消息恢复长驻。超限时先尝试
 *       LRU 逐出最久未用的空闲槽位(§6.5 规则 5);连续 spawn 失败达上限的键进入冷却窗口,
 *       窗口内不再尝试 spawn(§6.15 坏槽位不无限重启)。</li>
 * </ul>
 *
 * <p>空闲回收:{@link CliConstants#CLI_PERSISTENT_SWEEP_INTERVAL_MS} 周期扫描,idle 超过
 * {@link CliConstants#CLI_PERSISTENT_IDLE_TIMEOUT_MS} 的进程静默优雅关闭;进程已死则移除槽位。
 * 项目关闭({@link #dispose})同步快速清理(异步关闭有孤儿残留风险)。
 */
@Service(Service.Level.PROJECT)
public final class CliPersistentProcessRegistry implements Disposable {

    private static final Logger LOG = Logger.getInstance(CliPersistentProcessRegistry.class);

    /** 槽位键:与 CliSessionManager 的 tabId → provider 二级结构对齐。 */
    private record SlotKey(String tabId, String provider) {
    }

    private static final class Slot {
        final CliPersistentProcess process;
        final String fingerprint;

        Slot(CliPersistentProcess process, String fingerprint) {
            this.process = process;
            this.fingerprint = fingerprint;
        }
    }

    private final ConcurrentHashMap<SlotKey, Slot> slots = new ConcurrentHashMap<>();

    /** 曾创建过槽位的键集合:区分「tab 首条消息(同步 spawn)」与「回收/崩溃后再来(one-shot+后台重建)」。 */
    private final Set<SlotKey> everCreated = ConcurrentHashMap.newKeySet();

    /** 同键并发重建防抖(每键至多一个重建任务在跑)。 */
    private final ConcurrentHashMap<SlotKey, Long> pendingRebuilds = new ConcurrentHashMap<>();

    /** 每键生命周期代数:release/回收后使已排队的后台任务失效,避免关闭 tab 后重新拉起 CLI。 */
    private final ConcurrentHashMap<SlotKey, Long> generations = new ConcurrentHashMap<>();

    /** 注册表生命周期代数:dispose 后使所有未完成后台任务失效。 */
    private final AtomicLong lifecycleEpoch = new AtomicLong();

    /** release/dispose 与后台任务提交槽位之间的提交闸门。 */
    private final Object lifecycleLock = new Object();

    private volatile boolean disposed;

    /** 每键 spawn 健康度:连续失败计数 + 冷却截止时刻(§6.15 坏槽位不无限重启)。 */
    private final ConcurrentHashMap<SlotKey, RebuildHealth> rebuildHealth = new ConcurrentHashMap<>();

    /** 可变健康度状态(经 {@code rebuildHealth.compute} 原子更新)。 */
    private static final class RebuildHealth {
        int consecutiveFailures;
        long cooldownUntilMs;
    }

    private final ScheduledExecutorService sweeper = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService();
    private final ScheduledFuture<?> sweeperFuture;

    public static CliPersistentProcessRegistry getInstance(@NotNull Project project) {
        return project.getService(CliPersistentProcessRegistry.class);
    }

    public CliPersistentProcessRegistry() {
        sweeperFuture = sweeper.scheduleWithFixedDelay(this::sweepIdleProcesses,
                CliConstants.CLI_PERSISTENT_SWEEP_INTERVAL_MS,
                CliConstants.CLI_PERSISTENT_SWEEP_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * 获取 tab+provider 的可用长驻进程。命中返回进程;首条消息同步 spawn;
     * 其余未命中(指纹漂移/不可用/回收后/超限/门禁关)返回 null,上层走 one-shot + rebuildInBackground。
     */
    public CliPersistentProcess acquire(String tabId, String provider, CliProcessSpec spec) {
        SlotKey key = new SlotKey(tabId, provider);
        if (disposed) {
            return null;
        }
        Slot slot = slots.get(key);
        if (slot != null) {
            if (slot.process.isUsable() && slot.fingerprint.equals(spec.fingerprint())) {
                return slot.process;
            }
            // miss 原因细分(§6.16-4 可观测性):指纹漂移 vs 槽位不可用,便于事后归因
            if (slot.process.isUsable()) {
                LOG.info("[CliPersistentProcessRegistry] fingerprint drift, one-shot + rebuild: tab="
                        + tabId + ", provider=" + provider
                        + ", old=" + slot.fingerprint + ", new=" + spec.fingerprint());
            } else {
                LOG.info("[CliPersistentProcessRegistry] slot unusable (crashed/dirty/closed), one-shot + rebuild: tab="
                        + tabId + ", provider=" + provider);
            }
            // 指纹漂移或进程不可用(崩溃/dirty):当前消息走 one-shot,重建交给上层调 rebuildInBackground。
            return null;
        }
        // 坏槽位冷却期(§6.15):连续 spawn 失败达上限后,窗口内不再尝试,消息直接 one-shot。
        if (isCoolingDown(key)) {
            LOG.info("[CliPersistentProcessRegistry] rebuild cooling down, degrading to one-shot: tab="
                    + tabId + ", provider=" + provider);
            return null;
        }
        // 空闲回收/崩溃后再次使用:走 one-shot + 后台重建(§3.2),不在此同步 spawn。
        if (everCreated.contains(key)) {
            return null;
        }
        if (!ensureCapacity(tabId, provider)) {
            return null;
        }
        // tab 首条消息:同步 spawn(一次性 ~3.4s,与 one-shot 相同不劣化,§3.2)。
        long epoch = lifecycleEpoch.get();
        long generation = currentGeneration(key);
        CliPersistentProcess process = spawnTracked(key, tabId, provider, spec, epoch, generation);
        if (process == null) {
            return null;
        }
        Slot existing;
        boolean closeProcess;
        synchronized (lifecycleLock) {
            if (!isCurrent(key, epoch, generation)) {
                existing = null;
                closeProcess = true;
            } else {
                existing = slots.putIfAbsent(key, new Slot(process, spec.fingerprint()));
                closeProcess = existing != null;
                if (!closeProcess) {
                    everCreated.add(key);
                }
            }
        }
        if (closeProcess) {
            process.closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
            if (existing != null && existing.process.isUsable()
                    && existing.fingerprint.equals(spec.fingerprint())) {
                return existing.process;
            }
            return null;
        }
        return process;
    }

    /**
     * 后台静默重建:关闭旧进程(指纹漂移/不可用)、按新 spec spawn 入槽。
     * 当前消息不被阻塞(已走 one-shot);并发重建经 pendingRebuilds 防抖。
     */
    public void rebuildInBackground(String tabId, String provider, CliProcessSpec spec) {
        SlotKey key = new SlotKey(tabId, provider);
        final long epoch;
        final long generation;
        synchronized (lifecycleLock) {
            if (disposed || isCoolingDown(key)) {
                return;
            }
            epoch = lifecycleEpoch.get();
            generation = currentGeneration(key);
            if (pendingRebuilds.putIfAbsent(key, generation) != null) {
                return;
            }
        }
        try {
            CliSessionExecutor.runAsync(() -> {
                CliPersistentProcess oldProcess = null;
            CliPersistentProcess replacedProcess = null;
            try {
                if (!isCurrent(key, epoch, generation)) {
                    return;
                }
                Slot existing = slots.get(key);
                if (existing != null
                        && existing.process.isUsable()
                        && existing.fingerprint.equals(spec.fingerprint())) {
                    return;
                }
                if (isCoolingDown(key)) {
                    return;
                }
                if (!isCurrent(key, epoch, generation) || !ensureCapacity(tabId, provider)) {
                    return;
                }
                if (existing != null) {
                    synchronized (lifecycleLock) {
                        if (!isCurrent(key, epoch, generation)) {
                            return;
                        }
                        if (slots.remove(key, existing)) {
                            oldProcess = existing.process;
                        }
                    }
                    if (oldProcess != null) {
                        oldProcess.closeGracefully();
                        oldProcess = null;
                    }
                }
                CliPersistentProcess process = spawnTracked(key, tabId, provider, spec, epoch, generation);
                if (process != null) {
                    boolean closeSpawned = false;
                    synchronized (lifecycleLock) {
                        if (!isCurrent(key, epoch, generation)) {
                            closeSpawned = true;
                        } else {
                            Slot replaced = slots.put(key, new Slot(process, spec.fingerprint()));
                            if (replaced != null && replaced.process != process) {
                                replacedProcess = replaced.process;
                            }
                            everCreated.add(key);
                        }
                    }
                    if (closeSpawned) {
                        process.closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
                    }
                    if (replacedProcess != null) {
                        replacedProcess.closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
                    }
                }
                } finally {
                    pendingRebuilds.remove(key, generation);
                }
            });
        } catch (RuntimeException e) {
            pendingRebuilds.remove(key, generation);
            LOG.warn("[CliPersistentProcessRegistry] failed to schedule background rebuild: tab="
                    + tabId + ", provider=" + provider, e);
        }
    }

    /** tab 关闭(CliSession.dispose 链路):关闭并移除该 tab+provider 的长驻进程。 */
    public void release(String tabId, String provider) {
        SlotKey key = new SlotKey(tabId, provider);
        Slot slot;
        synchronized (lifecycleLock) {
            advanceGeneration(key);
            slot = slots.remove(key);
            everCreated.remove(key);
            pendingRebuilds.remove(key);
            rebuildHealth.remove(key);
        }
        if (slot != null) {
            slot.process.closeGracefully();
        }
    }

    /** 进程面板元数据(§5.1,NodeProcessRegistry.snapshot 合并用)。 */
    public List<CliPersistentProcess.PersistentProcessInfo> describeAll() {
        List<CliPersistentProcess.PersistentProcessInfo> infos = new ArrayList<>();
        for (Slot slot : slots.values()) {
            infos.add(slot.process.describe());
        }
        return infos;
    }

    /**
     * 立即回收所有 IDLE 长驻进程(§7 行为开关关闭副作用)。
     * STREAMING 轮不打断——进行中的回复自然收尾后由周期空闲扫描兜底回收。
     * 开关只影响新消息路由:门禁关后 acquire 不再命中,已死/已回收槽位不重建。
     */
    public void reclaimIdleProcessesNow() {
        List<CliPersistentProcess> toClose = new ArrayList<>();
        synchronized (lifecycleLock) {
            for (Map.Entry<SlotKey, Slot> entry : slots.entrySet()) {
                Slot slot = entry.getValue();
                if (slot.process.describe().state() != CliPersistentProcess.State.IDLE
                        || !slots.remove(entry.getKey(), slot)) {
                    continue;
                }
                advanceGeneration(entry.getKey());
                rebuildHealth.remove(entry.getKey());
                toClose.add(slot.process);
                LOG.info("[CliPersistentProcessRegistry] reclaiming IDLE process on toggle-off: tab="
                        + entry.getKey().tabId() + ", provider=" + entry.getKey().provider());
            }
            for (SlotKey key : pendingRebuilds.keySet()) {
                advanceGeneration(key);
                rebuildHealth.remove(key);
            }
        }
        for (CliPersistentProcess process : toClose) {
            process.closeGracefully();
        }
    }

    // ── Disposable ─────────────────────────────────────────────────────────────

    /**
     * 项目关闭:停扫描器,全部短等待优雅关闭 + terminateProcess 兜底。
     * 必须同步完成——异步关闭在 IDE 退出时无法保证执行,会留下孤儿 CLI 进程。
     */
    @Override
    public void dispose() {
        List<Slot> toClose;
        synchronized (lifecycleLock) {
            disposed = true;
            lifecycleEpoch.incrementAndGet();
            // The sweeper runs on the shared app-wide scheduler which must never
            // be shut down; cancel the periodic task itself instead.
            sweeperFuture.cancel(true);
            toClose = new ArrayList<>(slots.values());
            slots.clear();
            everCreated.clear();
            pendingRebuilds.clear();
            rebuildHealth.clear();
            generations.clear();
        }
        for (Slot slot : toClose) {
            if (slot != null) {
                slot.process.closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
            }
        }
    }

    // ── private ────────────────────────────────────────────────────────────────

    private int countUsableSlots() {
        int count = 0;
        for (Slot slot : slots.values()) {
            if (slot.process.isUsable()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 容量保障(§6.5 规则 5):未超限直接放行;超限时先尝试 LRU 逐出最久未用的空闲槽位
     * 再复查;无可回收候选(全部 STREAMING/不可用)才返回 false(上层降级 one-shot)。
     */
    private boolean ensureCapacity(String tabId, String provider) {
        if (countUsableSlots() < CliConstants.CLI_PERSISTENT_MAX_PROCESSES) {
            return true;
        }
        if (evictOldestIdleSlot() && countUsableSlots() < CliConstants.CLI_PERSISTENT_MAX_PROCESSES) {
            return true;
        }
        LOG.info("[CliPersistentProcessRegistry] max persistent processes reached ("
                + CliConstants.CLI_PERSISTENT_MAX_PROCESSES + "), degrading to one-shot: tab="
                + tabId + ", provider=" + provider);
        return false;
    }

    /**
     * 超限 LRU 逐出:回收 lastActiveAtMs 最旧且 IDLE 的可用槽位。STREAMING 轮进行中的
     * 进程绝不打断;无可回收候选返回 false。关闭用短等待(被逐出的是空闲进程,
     * stdin EOF 后 CLI 毫秒级退出,1s 上限几乎不触及)。
     */
    private boolean evictOldestIdleSlot() {
        Map.Entry<SlotKey, Slot> oldest = null;
        for (Map.Entry<SlotKey, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (!slot.process.isUsable()) {
                continue;
            }
            if (slot.process.describe().state() != CliPersistentProcess.State.IDLE) {
                continue;
            }
            if (oldest == null
                    || slot.process.lastActiveAtMs() < oldest.getValue().process.lastActiveAtMs()) {
                oldest = entry;
            }
        }
        if (oldest == null) {
            return false;
        }
        boolean removed;
        synchronized (lifecycleLock) {
            removed = slots.remove(oldest.getKey(), oldest.getValue());
            if (removed) {
                advanceGeneration(oldest.getKey());
                rebuildHealth.remove(oldest.getKey());
            }
        }
        if (removed) {
            LOG.info("[CliPersistentProcessRegistry] evicting least-recently-used IDLE process (capacity pressure): tab="
                    + oldest.getKey().tabId() + ", provider=" + oldest.getKey().provider());
            oldest.getValue().process.closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
            return true;
        }
        return false;
    }

    /** 该键是否处于重建冷却窗口(§6.15)。 */
    private boolean isCoolingDown(SlotKey key) {
        RebuildHealth health = rebuildHealth.get(key);
        if (health == null) {
            return false;
        }
        if (health.cooldownUntilMs <= 0L) {
            return false;
        }
        if (health.cooldownUntilMs > System.currentTimeMillis()) {
            return true;
        }
        rebuildHealth.remove(key, health);
        return false;
    }

    /** 测试观测钩子:指定 tab+provider 是否处于重建冷却窗口。 */
    boolean isRebuildCoolingDown(String tabId, String provider) {
        return isCoolingDown(new SlotKey(tabId, provider));
    }

    /** 带健康度记账的 spawn:成功清零失败计数,失败累计并在达上限时开启冷却窗口。 */
    private CliPersistentProcess spawnTracked(
            SlotKey key,
            String tabId,
            String provider,
            CliProcessSpec spec,
            long epoch,
            long generation
    ) {
        CliPersistentProcess process = spawn(tabId, provider, spec);
        if (process != null) {
            boolean stale;
            synchronized (lifecycleLock) {
                stale = !isCurrent(key, epoch, generation);
                if (!stale) {
                    rebuildHealth.remove(key);
                }
            }
            if (stale) {
                process.closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
                return null;
            }
        } else {
            synchronized (lifecycleLock) {
                if (!isCurrent(key, epoch, generation)) {
                    return null;
                }
                rebuildHealth.compute(key, (k, health) -> {
                    RebuildHealth h = health != null ? health : new RebuildHealth();
                    h.consecutiveFailures++;
                    if (h.consecutiveFailures >= CliConstants.CLI_PERSISTENT_REBUILD_MAX_FAILURES) {
                        h.cooldownUntilMs = System.currentTimeMillis()
                                + CliConstants.CLI_PERSISTENT_REBUILD_COOLDOWN_MS;
                        LOG.warn("[CliPersistentProcessRegistry] rebuild cooldown activated (consecutiveFailures="
                                + h.consecutiveFailures + ", cooldownMs=" + CliConstants.CLI_PERSISTENT_REBUILD_COOLDOWN_MS
                                + ", one-shot until expiry): tab=" + tabId + ", provider=" + provider);
                    }
                    return h;
                });
            }
        }
        return process;
    }

    private long currentGeneration(SlotKey key) {
        return generations.getOrDefault(key, 0L);
    }

    private boolean isCurrent(SlotKey key, long epoch, long generation) {
        return !disposed
                && lifecycleEpoch.get() == epoch
                && currentGeneration(key) == generation;
    }

    private void advanceGeneration(SlotKey key) {
        generations.merge(key, 1L, Long::sum);
    }

    private CliPersistentProcess spawn(String tabId, String provider, CliProcessSpec spec) {
        CliPersistentProcess process = new CliPersistentProcess(provider, tabId);
        process.bindInterruptSupplier(spec.interruptLineSupplier());
        boolean started = process.start(spec.command(), spec.env(), spec.cwd(),
                CliConstants.CLI_PERSISTENT_READY_WINDOW_MS);
        if (!started) {
            LOG.warn("[CliPersistentProcessRegistry] persistent process failed to start: tab="
                    + tabId + ", provider=" + provider);
            return null;
        }
        return process;
    }

    /**
     * 空闲回收扫描(§4.5):进程已死 → 移除槽位;idle 超阈值 → 静默优雅关闭。
     * 与 acquire 的竞态无害:回收关掉的进程若恰被 acquire 返回,轮协议写入/读取失败
     * 会异常收尾,上层自愈走 one-shot(§3.2 崩溃行)。
     */
    private void sweepIdleProcesses() {
        try {
            long now = System.currentTimeMillis();
            List<CliPersistentProcess> toClose = new ArrayList<>();
            for (Map.Entry<SlotKey, Slot> entry : slots.entrySet()) {
                Slot slot = entry.getValue();
                if (!slot.process.isAlive()) {
                    boolean removed;
                    synchronized (lifecycleLock) {
                        removed = slots.remove(entry.getKey(), slot);
                        if (removed) {
                            advanceGeneration(entry.getKey());
                            rebuildHealth.remove(entry.getKey());
                        }
                    }
                    if (removed) {
                        LOG.info("[CliPersistentProcessRegistry] removed dead persistent process: tab="
                                + entry.getKey().tabId() + ", provider=" + entry.getKey().provider());
                    }
                    continue;
                }
                long idleMs = now - slot.process.lastActiveAtMs();
                if (idleMs > CliConstants.CLI_PERSISTENT_IDLE_TIMEOUT_MS) {
                    boolean removed;
                    synchronized (lifecycleLock) {
                        removed = slots.remove(entry.getKey(), slot);
                        if (removed) {
                            advanceGeneration(entry.getKey());
                            rebuildHealth.remove(entry.getKey());
                        }
                    }
                    if (!removed) {
                        continue;
                    }
                    LOG.info("[CliPersistentProcessRegistry] reclaiming idle persistent process (idleMs="
                            + idleMs + "): tab=" + entry.getKey().tabId()
                            + ", provider=" + entry.getKey().provider());
                    toClose.add(slot.process);
                }
            }
            for (CliPersistentProcess process : toClose) {
                process.closeGracefully();
            }
        } catch (Exception e) {
            // 周期任务永不因单次异常终止(scheduleWithFixedDelay 语义),此处防御性兜底。
            LOG.warn("[CliPersistentProcessRegistry] idle sweep failed", e);
        }
    }
}
