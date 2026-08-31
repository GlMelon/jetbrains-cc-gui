package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.provider.common.CliResult;
import com.github.claudecodegui.service.lifecycle.LifecycleEventType;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.service.lifecycle.LifecycleProcessKind;
import com.github.claudecodegui.service.lifecycle.ProcessLifecycleMetadata;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.intellij.util.concurrency.AppExecutorUtil;

/**
 * Provider 无关的长驻 CLI 进程句柄(Phase 1: claude stream-json 交互模式)。
 *
 * <p><b>它不是 daemon</b>:没有 method 分发、没有会话管理、没有跨会话队列。
 * 职责仅三件事:
 * <ol>
 *   <li>持有 Process + 永久读行线程 + stdin writer;</li>
 *   <li>按「轮」(turn)关联 callback——一行进、事件逐行分发出、以 result 行结束本轮;</li>
 *   <li>优雅关闭(stdin EOF → 限时等待 → terminateProcess 兜底)与元数据描述。</li>
 * </ol>
 *
 * <p><b>轮协议</b>:CLI 启动后先等 stdin、init 在首条消息之后才发,
 * 因此 spawn 存活即就绪。轮完成信号从「进程退出」改为「result 事件」——每轮 turn
 * 结束 CLI 输出 result 行但进程不退出。同一进程同一时刻只允许一个活跃轮:上层
 * {@code CliSessionManager} 的 per-tab inFlight 链保证不重叠,此处再以 CAS 断言防御
 * (抛错优于静默交错)。
 *
 * <p><b>abort</b>(V1 实测定稿):经 provider 注入的
 * {@code interruptLineSupplier}(随 {@link CliProcessSpec} 绑定,本类不内置协议格式)取得
 * interrupt 协议行写入 stdin,进程保留;被中断轮以 result subtype=error_during_execution
 * 收尾即中断成功。兜底:interrupt 写入后 {@link CliConstants#CLI_INTERRUPT_FALLBACK_MS}
 * 无 result 回应 → 杀进程 + 槽位不可复用。
 */
public final class CliPersistentProcess {

    private static final Logger LOG = Logger.getInstance(CliPersistentProcess.class);

    /** 轮超时与 interrupt 兜底调度器，交由 IntelliJ Application 生命周期管理。 */
    private static final ScheduledExecutorService SCHEDULER = AppExecutorUtil.getAppScheduledExecutorService();

    // ── 嵌套类型 ───────────────────────────────────────────────────────────────

    /**
     * 长驻进程生命周期状态(进程面板展示用)。spawn 即就绪(CLI 先等 stdin),
     * 故无 STARTING 中间态:槽内进程要么空闲、要么轮进行中、要么已死(待空闲扫描移除)。
     */
    public enum State {IDLE, STREAMING, DEAD}

    /** 进程面板元数据(注册到 NodeProcessRegistry 用)。 */
    public record PersistentProcessInfo(
            long pid,
            String provider,
            String tabId,
            String sessionId,
            State state,
            long startedAtMs,
            long lastActiveAtMs,
            String projectLifecycleId,
            String runtimeSessionEpoch,
            Long responseTurnEpoch,
            Long processGeneration
    ) {
    }

    /**
     * Provider 侧逐行处理器:读行线程对每行 stdout 调用一次。
     * 实现方须同步分发(回归调,无缓冲无排队);仅当该行为本轮结束行(result 事件)时
     * 返回非 null 的 {@link CliResult},否则返回 null 表示轮继续。
     *
     * @param interrupted 本轮是否已被 interruptTurn 标记中断(result 行据此映射中断语义)
     */
    @FunctionalInterface
    public interface TurnLineHandler {
        CliResult onLine(String line, boolean interrupted);
    }

    /** 轮句柄:暴露轮 future、turnId 与中断标记。 */
    public static final class TurnHandle {
        private final CompletableFuture<CliResult> future;
        private final AtomicBoolean interrupted;
        private final String turnId;

        private TurnHandle(CompletableFuture<CliResult> future, AtomicBoolean interrupted, String turnId) {
            this.future = future;
            this.interrupted = interrupted;
            this.turnId = turnId;
        }

        public CompletableFuture<CliResult> future() {
            return future;
        }

        public boolean wasInterrupted() {
            return interrupted.get();
        }

        /** 本轮短标识(日志归因用的 turnId 字段)。 */
        public String turnId() {
            return turnId;
        }
    }

    private static final class ActiveTurn {
        final TurnLineHandler handler;
        final CompletableFuture<CliResult> future = new CompletableFuture<>();
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        /** 轮短标识:日志按轮归因。 */
        final String turnId;
        volatile ScheduledFuture<?> interruptFallback;

        ActiveTurn(TurnLineHandler handler, String turnId) {
            this.handler = handler;
            this.turnId = turnId;
        }
    }

    /** 生成轮短标识(8 位十六进制,仅日志归因,无业务语义)。 */
    private static String newTurnId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ── 状态字段 ───────────────────────────────────────────────────────────────

    private final String provider;
    private final String tabId;
    private final LifecycleObservabilityService lifecycleService;
    private final Long processGeneration;
    private final long startedAtMs = System.currentTimeMillis();
    private volatile long lastActiveAtMs = startedAtMs;
    private volatile String sessionId;
    private volatile String runtimeSessionEpoch;
    private volatile Long responseTurnEpoch;

    private volatile Process process;
    private volatile OutputStreamWriter stdinWriter;
    private volatile Thread readerThread;
    private final AtomicReference<ActiveTurn> currentTurn = new AtomicReference<>();
    /** closeGracefully 已调用(此后不可再开新轮)。 */
    private volatile boolean closed;
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    /** 强杀兜底已触发,槽位不可复用(dirty)。 */
    private volatile boolean dirty;
    /** 轮外协议事件 WARN 限流计数(防协议错配场景刷屏)。 */
    private final AtomicInteger orphanProtocolWarnCount = new AtomicInteger();
    /**
     * provider 中断协议行构造器(经 {@link CliProcessSpec} 注入):本类不内置任何 provider
     * 协议格式(职责边界)。null = 无进程保留式中断,interrupt 直接杀进程兜底。
     */
    private volatile Supplier<String> interruptLineSupplier;

    public CliPersistentProcess(String provider, String tabId) {
        this(provider, tabId, null, null);
    }

    public CliPersistentProcess(String provider, String tabId,
                                LifecycleObservabilityService lifecycleService,
                                Long processGeneration) {
        this.provider = provider;
        this.tabId = tabId;
        this.lifecycleService = lifecycleService;
        this.processGeneration = processGeneration;
    }

    /** 绑定 provider 中断协议行构造器(spawn 前由 Registry 从 spec 注入)。 */
    public void bindInterruptSupplier(Supplier<String> supplier) {
        this.interruptLineSupplier = supplier;
    }

    // ── 生命周期 ───────────────────────────────────────────────────────────────

    /**
     * 启动长驻进程。spawn 即就绪(CLI 先等 stdin,init 在首条消息后才发),
     * {@code readyTimeoutMs} 仅作速死观察窗:CLI 因参数/认证问题立即退出时及时返回 false。
     *
     * @return true 表示进程存活(就绪);false 表示观察窗内已退出
     */
    public boolean start(List<String> cmd, Map<String, String> env, String cwd, long readyTimeoutMs) {
        if (closed) {
            throw new IllegalStateException("Persistent process already closed: tab=" + tabId);
        }
        Process started = null;
        Thread reader = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (env != null && !env.isEmpty()) {
                pb.environment().clear();
                pb.environment().putAll(env);
            }
            if (cwd != null && !cwd.isBlank()) {
                pb.directory(new File(cwd));
            }
            started = pb.start();
            process = started;
            stdinWriter = new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8);
            recordLifecycle(LifecycleEventType.SPAWN, started, "persistent CLI spawned");

            // Start draining stdout before waiting for the ready window. A CLI may emit a
            // large startup banner/diagnostic and block on a full pipe otherwise.
            reader = new Thread(this::runReaderLoop,
                    "AICG-CLI-Persistent-Reader-" + provider + "-" + tabId);
            reader.setDaemon(true);
            readerThread = reader;
            reader.start();

            long timeoutMs = Math.max(0L, readyTimeoutMs);
            if (started.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOG.warn("[CliPersistentProcess] CLI exited during ready window: provider=" + provider
                        + ", tab=" + tabId + ", exitCode=" + safeExitCode(started));
                closeWriterQuietly();
                terminateAndAwait(started);
                joinReader(reader);
                clearProcessReferences(started, reader);
                return false;
            }
            if (closed) {
                closeGracefully(CliConstants.CLI_DISPOSE_CLOSE_TIMEOUT_MS);
                return false;
            }
            LOG.info("[CliPersistentProcess] started: provider=" + provider + ", tab=" + tabId
                    + ", pid=" + started.pid());
            return true;
        } catch (InterruptedException e) {
            closeWriterQuietly();
            if (reader != null) {
                reader.interrupt();
            }
            terminateAndAwait(started != null ? started : process);
            joinReader(reader);
            clearProcessReferences(started != null ? started : process, reader);
            Thread.currentThread().interrupt();
            LOG.warn("[CliPersistentProcess] start interrupted: provider=" + provider + ", tab=" + tabId, e);
            return false;
        } catch (Exception e) {
            closeWriterQuietly();
            if (reader != null) {
                reader.interrupt();
            }
            Process target = started != null ? started : process;
            terminateAndAwait(target);
            joinReader(reader);
            clearProcessReferences(target, reader);
            LOG.warn("[CliPersistentProcess] start failed: provider=" + provider + ", tab=" + tabId, e);
            return false;
        }
    }

    /** 进程是否存活。 */
    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** 槽位是否可复用:进程存活且未被强杀兜底/关闭标记。 */
    public boolean isUsable() {
        return !closed && !dirty && isAlive();
    }

    /**
     * 优雅关闭(默认等待):关闭 stdin(EOF)→ CLI 自然退出(实测)→
     * {@link CliConstants#CLI_GRACEFUL_CLOSE_TIMEOUT_MS} 未退则 terminateProcess 兜底
     * (复用 Windows 父死孤儿清理基建)。可能阻塞至多 ~5s,空闲回收/关闭 tab 须在可阻塞线程调用。
     */
    public void closeGracefully() {
        closeGracefully(CliConstants.CLI_GRACEFUL_CLOSE_TIMEOUT_MS);
    }

    /**
     * 优雅关闭(参数化等待上限):项目 dispose 等须快速返回的场合传较短超时,
     * 到期即 terminateProcess 兜底(异步关闭有孤儿残留风险,故仍同步强杀收尾)。
     */
    public void closeGracefully(long waitTimeoutMs) {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        ActiveTurn turn = currentTurn.get();
        cancelInterruptFallback(turn);
        Process p = process;
        failActiveTurn("persistent process closed: tab=" + tabId);
        closeWriterQuietly();
        Thread reader = readerThread;
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
        if (p == null) {
            joinReader(reader);
            clearProcessReferences(null, reader);
            return;
        }
        long timeoutMs = Math.max(0L, waitTimeoutMs);
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                LOG.info("[CliPersistentProcess] graceful close timed out, terminating: provider="
                        + provider + ", tab=" + tabId + ", pid=" + p.pid());
                PlatformUtils.terminateProcess(p);
                recordLifecycle(LifecycleEventType.TERMINATE, p, "graceful close timeout");
                p.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            PlatformUtils.terminateProcess(p);
            recordLifecycle(LifecycleEventType.TERMINATE, p, "graceful close interrupted");
            Thread.currentThread().interrupt();
        } finally {
            joinReader(reader);
            clearProcessReferences(p, reader);
        }
        LOG.info("[CliPersistentProcess] closed: provider=" + provider + ", tab=" + tabId
                + ", pid=" + p.pid() + ", alive=" + p.isAlive());
    }

    // ── 轮协议 ─────────────────────────────────────────────────────────────────

    /**
     * 开始一轮:向 stdin 写一行 user 消息,后续 stdout 事件逐行分发给
     * {@code handler},以 result 行(handler 返回非 null)结束本轮。
     *
     * @throws IllegalStateException 已有活跃轮(上层 per-tab inFlight 链之外的防御断言)
     */
    public TurnHandle startTurn(String stdinLine, TurnLineHandler handler) {
        return startTurn(stdinLine, handler, null, null);
    }

    public TurnHandle startTurn(String stdinLine, TurnLineHandler handler,
                                String runtimeSessionEpoch, Long responseTurnEpoch) {
        if (handler == null) {
            throw new IllegalArgumentException("turn handler must not be null");
        }
        if (closed || dirty || !isAlive()) {
            throw new IllegalStateException(
                    "Persistent CLI process is not usable: provider=" + provider + ", tab=" + tabId);
        }
        ActiveTurn turn = new ActiveTurn(handler, newTurnId());
        if (!currentTurn.compareAndSet(null, turn)) {
            throw new IllegalStateException(
                    "Concurrent turn on persistent CLI process: provider=" + provider
                            + ", tab=" + tabId + " — per-tab inFlight must serialize sends");
        }
        if (closed || dirty || !isAlive()) {
            if (currentTurn.compareAndSet(turn, null)) {
                turn.future.completeExceptionally(new IllegalStateException(
                        "Persistent CLI process became unusable: provider=" + provider + ", tab=" + tabId));
            }
            killForcibly("process became unusable before turn start");
            throw new IllegalStateException(
                    "Persistent CLI process became unusable: provider=" + provider + ", tab=" + tabId);
        }
        lastActiveAtMs = System.currentTimeMillis();
        this.runtimeSessionEpoch = runtimeSessionEpoch;
        this.responseTurnEpoch = responseTurnEpoch;
        // 轮超时:超时即轮卡死,须杀进程(进程句柄私有,只能在此处理)。
        turn.future.orTimeout(CliConstants.CLI_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        turn.future.whenComplete((result, error) -> {
            if (error != null && currentTurn.compareAndSet(turn, null)) {
                killForcibly("turn failed: " + error);
            }
        });
        try {
            writeStdinLine(stdinLine == null ? "" : stdinLine);
            LOG.info("[CliPersistentProcess] turn started: provider=" + provider + ", tab=" + tabId
                    + ", turnId=" + turn.turnId + ", pid=" + safePid());
        } catch (Exception e) {
            killForcibly("turn start failed: " + e.getMessage());
        }
        return new TurnHandle(turn.future, turn.interrupted, turn.turnId);
    }

    /**
     * 中断当前轮(进程保留式中断):经 provider 注入的 {@code interruptLineSupplier}
     * 取协议行写入 stdin,标记中断;被中断轮以 result(error_during_execution)收尾,
     * future 正常完成。provider 未注入协议行(无进程保留式中断)或写入失败 → 直接杀进程兜底。
     * 兜底:{@link CliConstants#CLI_INTERRUPT_FALLBACK_MS} 无 result 回应 → 杀进程 + dirty。
     * 无活跃轮时为空操作。
     */
    public void interruptTurn() {
        ActiveTurn turn = currentTurn.get();
        if (turn == null) {
            return;
        }
        turn.interrupted.set(true);
        String interruptLine = null;
        Supplier<String> supplier = interruptLineSupplier;
        if (supplier != null) {
            try {
                interruptLine = supplier.get();
            } catch (Exception e) {
                LOG.warn("[CliPersistentProcess] interrupt line supplier failed: provider=" + provider
                        + ", tab=" + tabId + ", turnId=" + turn.turnId, e);
            }
        }
        if (interruptLine == null || interruptLine.isBlank()) {
            LOG.info("[CliPersistentProcess] no protocol interrupt available, killing process: provider="
                    + provider + ", tab=" + tabId + ", turnId=" + turn.turnId + ", pid=" + safePid());
            killForcibly("no protocol interrupt for provider " + provider);
            return;
        }
        try {
            writeStdinLine(interruptLine);
            LOG.info("[CliPersistentProcess] interrupt request written: provider=" + provider
                    + ", tab=" + tabId + ", turnId=" + turn.turnId + ", pid=" + safePid());
        } catch (Exception e) {
            LOG.warn("[CliPersistentProcess] interrupt write failed, killing process: provider="
                    + provider + ", tab=" + tabId + ", turnId=" + turn.turnId, e);
            killForcibly("interrupt write failed");
            return;
        }
        turn.interruptFallback = SCHEDULER.schedule(() -> {
            if (!turn.future.isDone() && !closed && currentTurn.get() == turn) {
                LOG.warn("[CliPersistentProcess] interrupt fallback (no result response in "
                        + CliConstants.CLI_INTERRUPT_FALLBACK_MS + "ms), killing: provider=" + provider
                        + ", tab=" + tabId + ", turnId=" + turn.turnId + ", pid=" + safePid());
                killForcibly("interrupt fallback timeout");
            }
        }, CliConstants.CLI_INTERRUPT_FALLBACK_MS, TimeUnit.MILLISECONDS);
    }

    // ── 元数据 ─────────────────────────────────────────────────────────────────

    /** 更新 session_id(provider 层从 init 事件获知后回填,进程面板展示用)。 */
    public void updateSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.sessionId = sessionId;
        }
    }

    public String sessionId() {
        return sessionId;
    }

    public long pid() {
        return safePid();
    }

    /** 最近一次轮活动时刻(轮开始/结束更新),空闲回收判定用。 */
    public long lastActiveAtMs() {
        return lastActiveAtMs;
    }

    /** 进程面板元数据快照。 */
    public PersistentProcessInfo describe() {
        State state = currentTurn.get() != null ? State.STREAMING
                : isAlive() ? State.IDLE : State.DEAD;
        return new PersistentProcessInfo(safePid(), provider, tabId, sessionId, state,
                startedAtMs, lastActiveAtMs,
                lifecycleService != null ? lifecycleService.projectLifecycleId() : null,
                runtimeSessionEpoch, responseTurnEpoch, processGeneration);
    }

    // ── 内部实现 ───────────────────────────────────────────────────────────────

    /**
     * 永久读行线程:逐行读取 stdout,分发给当前轮 handler;分发即回归调,无缓冲无排队。
     * 无活跃轮时的输出(轮间噪声)丢弃。stdout EOF = 进程退出前兆,未完成轮异常收尾。
     */
    private void runReaderLoop() {
        Process p = process;
        if (p == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            BoundedLine line;
            while ((line = readBoundedLine(reader)) != null) {
                if (line.truncated()) {
                    LOG.warn("[CliPersistentProcess] stdout line exceeded limit: provider=" + provider
                            + ", tab=" + tabId + ", maxBytes=" + CliOutputLimits.MAX_LINE_BYTES);
                    killForcibly("stdout line exceeded " + CliOutputLimits.MAX_LINE_BYTES + " bytes");
                    break;
                }
                String text = line.text();
                ActiveTurn turn = currentTurn.get();
                if (turn == null) {
                    logLineOutsideTurn(text);
                    continue;
                }
                CliResult result;
                try {
                    result = turn.handler.onLine(text, turn.interrupted.get());
                } catch (Exception e) {
                    LOG.warn("[CliPersistentProcess] turn handler failed: provider=" + provider
                            + ", tab=" + tabId, e);
                    if (currentTurn.compareAndSet(turn, null)) {
                        lastActiveAtMs = System.currentTimeMillis();
                        cancelInterruptFallback(turn);
                        turn.future.completeExceptionally(e);
                    }
                    killForcibly("turn handler failed");
                    break;
                }
                if (result != null && currentTurn.compareAndSet(turn, null)) {
                    lastActiveAtMs = System.currentTimeMillis();
                    cancelInterruptFallback(turn);
                    turn.future.complete(result);
                }
            }
        } catch (Exception e) {
            LOG.warn("[CliPersistentProcess] reader loop ended: provider=" + provider
                    + ", tab=" + tabId + ", error=" + e.getMessage());
        }
        recordLifecycle(LifecycleEventType.STDOUT_EOF, p, "persistent stdout EOF");
        if (!p.isAlive()) {
            recordLifecycle(LifecycleEventType.EXIT, p, "persistent CLI exited: " + safeExitCode(p));
        }
        try {
            // stdout 关闭(进程退出前兆):未完成轮异常收尾,由上层走 one-shot 兜底。
            failActiveTurn("persistent CLI process stdout closed before turn result: tab=" + tabId);
        } finally {
            if (readerThread == Thread.currentThread()) {
                readerThread = null;
            }
        }
    }

    private static BoundedLine readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(CliOutputLimits.MAX_LINE_BYTES, 8192));
        char[] buffer = new char[8192];
        boolean truncated = false;
        int read;
        while ((read = reader.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) {
                char ch = buffer[i];
                if (ch == '\n') {
                    return new BoundedLine(line.toString(), truncated);
                }
                if (!truncated) {
                    if (line.length() < CliOutputLimits.MAX_LINE_BYTES) {
                        line.append(ch);
                    } else {
                        truncated = true;
                    }
                }
            }
        }
        if (line.isEmpty() && !truncated) {
            return null;
        }
        return new BoundedLine(line.toString(), truncated);
    }

    private record BoundedLine(String text, boolean truncated) {
    }

    /**
     * 轮外行分流:协议事件(result/assistant/system 类)在无活跃轮时到达,通常是 CLI 版本/
     * 协议错配的信号(无法归属到当前 turn 的协议消息)。保守落地:
     * 可观测化(WARN,每进程限流 {@link CliConstants#CLI_PERSISTENT_ORPHAN_WARN_LIMIT} 条防刷屏)
     * 但<b>不自动降级</b>——中断收尾后的迟到行属正常拖尾,自动降级误伤率高于收益;
     * 持续出现且伴随轮异常时,人工据此判断是否版本回退。非协议噪声行维持 debug 级丢弃。
     */
    private void logLineOutsideTurn(String line) {
        if (!isProtocolEventLine(line)) {
            LOG.debug("[CliPersistentProcess] line outside active turn dropped: tab="
                    + tabId + ", preview=" + preview(line));
            return;
        }
        if (orphanProtocolWarnCount.incrementAndGet() <= CliConstants.CLI_PERSISTENT_ORPHAN_WARN_LIMIT) {
            LOG.warn("[CliPersistentProcess] protocol event outside active turn (possible protocol mismatch): provider="
                    + provider + ", tab=" + tabId + ", pid=" + safePid()
                    + ", preview=" + preview(line));
        } else {
            LOG.debug("[CliPersistentProcess] protocol event outside active turn (suppressed): tab="
                    + tabId + ", preview=" + preview(line));
        }
    }

    /** 顶层协议事件行判定:复用 {@link CliConstants#NORMAL_STREAM_EVENT_PREFIXES} SSOT(紧凑 JSON,顶层 type 在行首)。 */
    private static boolean isProtocolEventLine(String line) {
        for (String prefix : CliConstants.NORMAL_STREAM_EVENT_PREFIXES) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void writeStdinLine(String line) throws IOException {
        OutputStreamWriter writer = stdinWriter;
        if (closed || writer == null) {
            throw new IllegalStateException("stdin already closed: tab=" + tabId);
        }
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    /**
     * 强杀兜底(interrupt 3s 无回应/轮超时/写入失败):杀进程树并标记槽位不可复用。
     * registry 后续 isUsable() 检查失败,下条消息走 one-shot 并后台重建。
     */
    private void killForcibly(String reason) {
        dirty = true;
        closeWriterQuietly();
        Thread reader = readerThread;
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
        Process p = process;
        ActiveTurn turn = currentTurn.get();
        LOG.warn("[CliPersistentProcess] killing forcibly: provider=" + provider + ", tab="
                + tabId + ", turnId=" + (turn != null ? turn.turnId : "-")
                + ", pid=" + safePid() + ", reason=" + reason);
        failActiveTurn("persistent CLI process killed: " + reason);
        terminateAndAwait(p);
        joinReader(reader);
        clearProcessReferences(p, reader);
    }

    private void failActiveTurn(String reason) {
        ActiveTurn turn = currentTurn.getAndSet(null);
        if (turn != null) {
            lastActiveAtMs = System.currentTimeMillis();
            cancelInterruptFallback(turn);
            turn.future.completeExceptionally(new IllegalStateException(
                    reason + " [turnId=" + turn.turnId + "]"));
        }
    }

    private static void cancelInterruptFallback(ActiveTurn turn) {
        if (turn == null) {
            return;
        }
        ScheduledFuture<?> fallback = turn.interruptFallback;
        if (fallback != null) {
            fallback.cancel(false);
            turn.interruptFallback = null;
        }
    }

    private void joinReader(Thread reader) {
        if (reader == null || reader == Thread.currentThread()) {
            return;
        }
        try {
            reader.join(Math.max(100L, Math.min(CliConstants.PROCESS_WAIT_TIMEOUT_MS, 1_000L)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void terminateAndAwait(Process target) {
        if (target == null) {
            return;
        }
        if (target.isAlive()) {
            PlatformUtils.terminateProcess(target);
            recordLifecycle(LifecycleEventType.TERMINATE, target, "process tree terminated");
        }
        try {
            target.waitFor(CliConstants.PROCESS_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private void clearProcessReferences(Process target, Thread reader) {
        if (target == null || process == target) {
            process = null;
        }
        if (reader == null || readerThread == reader || reader == Thread.currentThread()) {
            readerThread = null;
        }
        if (stdinWriter == null) {
            interruptLineSupplier = null;
            sessionId = null;
        }
    }

    private void closeWriterQuietly() {
        OutputStreamWriter writer;
        synchronized (this) {
            writer = stdinWriter;
            stdinWriter = null;
        }
        if (writer != null) {
            try {
                writer.close();
                recordLifecycle(LifecycleEventType.STDIN_CLOSE, process, "persistent stdin closed");
            } catch (IOException ignored) {
            }
        }
    }

    private void recordLifecycle(LifecycleEventType type, Process target, String detail) {
        if (lifecycleService == null) {
            return;
        }
        ProcessLifecycleMetadata metadata = lifecycleService.metadata(
                LifecycleProcessKind.CLI_PERSISTENT,
                runtimeSessionEpoch,
                responseTurnEpoch,
                processGeneration);
        long pid = target != null ? target.pid() : -1L;
        lifecycleService.record(type, metadata, pid, detail);
    }

    private static String preview(String line) {
        if (line == null) {
            return "";
        }
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private long safePid() {
        Process p = process;
        return p != null ? p.pid() : -1;
    }

    private static int safeExitCode(Process p) {
        try {
            return p.isAlive() ? -1 : p.exitValue();
        } catch (IllegalStateException e) {
            return -1;
        }
    }
}
