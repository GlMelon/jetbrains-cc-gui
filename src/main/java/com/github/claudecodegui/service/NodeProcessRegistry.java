package com.github.claudecodegui.service;

import com.github.claudecodegui.bridge.NodeService;
import com.github.claudecodegui.cli.common.CliPersistentProcess;
import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatToolWindow;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.github.claudecodegui.common.CommonConstants;

/**
 * Project-scoped service that aggregates all Node.js subprocess data for the
 * Node Process Management panel.
 *
 * <p>Three data sources are unified:
 * <ol>
 *   <li><b>Per-channel processes</b>: tracked in each bridge's {@code ProcessManager}.</li>
 *   <li><b>Persistent CLI session processes</b>: tracked by
 *       {@code CliPersistentProcessRegistry}, merged read-only into the panel.</li>
 *   <li><b>Orphan processes</b>: discovered by scanning {@link ProcessHandle#allProcesses()}
 *       for {@code daemon.js} / {@code channel-manager.js} command lines that don't match
 *       any registered process — the root cause of "node piling up" user complaints.
 *       The {@code daemon.js} hint identifies daemon processes left behind by older
 *       plugin versions (the SDK daemon invocation mode has been removed); matching
 *       leftovers surface as orphans so the user can clean them up.</li>
 * </ol>
 *
 * <p>The service is read-only with respect to process lifecycle (use {@link #killByPid} for
 * termination). It does not poll — callers invoke {@link #snapshot()} on demand.
 */
@Service(Service.Level.PROJECT)
public final class NodeProcessRegistry implements Disposable {

    private static final Logger LOG = Logger.getInstance(NodeProcessRegistry.class);

    /**
     * Substrings used to identify Node processes that look like ours.
     * Used by the orphan scanner; intentionally narrow to avoid false positives.
     * {@code daemon.js} only matches leftovers from the removed SDK daemon mode
     * (older plugin versions); the current CLI mode spawns channel-manager.js.
     */
    private static final String[] OWNED_PROCESS_HINTS = {
            "daemon.js",
            "channel-manager.js",
            "mcp-gateway-server.js",
            "gateway-stdio-client.js"
    };

    /**
     * Soft deadline for the {@code ProcessHandle.allProcesses()} sweep. On macOS /
     * Windows hosts with thousands of running processes the sweep can otherwise
     * take 100–500 ms per call — and the panel re-fetches on every menu open.
     * Past this budget we return the partial result and warn; missing a late-
     * arriving orphan is acceptable, blocking the user's menu is not.
     */
    private static final long ORPHAN_SCAN_BUDGET_MS = 500L;

    private final Project project;

    public NodeProcessRegistry(@NotNull Project project) {
        this.project = project;
    }

    public static NodeProcessRegistry getInstance(@NotNull Project project) {
        return project.getService(NodeProcessRegistry.class);
    }

    // ============================================================================
    // Snapshot
    // ============================================================================

    /**
     * Build a one-shot snapshot of all Node processes related to this project.
     * Safe to call from any thread; no I/O beyond {@link ProcessHandle} reads.
     *
     * @return list of process descriptors, never null
     */
    public List<NodeProcessInfo> snapshot() {
        long now = System.currentTimeMillis();
        List<NodeProcessInfo> result = new ArrayList<>();
        Set<Long> knownPids = new HashSet<>();

        Set<ClaudeChatWindow> windows = ClaudeChatToolWindow.getAllChatWindowsForProject(project);

        for (ClaudeChatWindow window : windows) {
            if (window == null) {
                continue;
            }
            String tabName = resolveTabName(window);
            String sessionId = safeGetSessionId(window);
            String tabProvider = safeGetCurrentProvider(window);

            Map<String, Process> channels = NodeService.getInstance().getProcessManager().getActiveChannelSnapshot();
            for (Map.Entry<String, Process> entry : channels.entrySet()) {
                Process p = entry.getValue();
                if (p == null || !p.isAlive()) {
                    continue;
                }
                long pid = p.pid();
                knownPids.add(pid);
                ProcessHandle.Info info = safeInfo(p);
                long startedAt = info != null
                        ? info.startInstant().map(Instant::toEpochMilli).orElse(-1L)
                        : -1L;
                result.add(NodeProcessInfo.builder()
                        .kind(NodeProcessInfo.Kind.CHANNEL)
                        .provider(tabProvider)
                        .pid(pid)
                        .alive(true)
                        .startedAtMs(startedAt)
                        .uptimeMs(startedAt > 0 ? Math.max(0, now - startedAt) : 0L)
                        .command(extractCommand(info))
                        .channelId(entry.getKey())
                        .sessionId(sessionId)
                        .tabName(tabName)
                        .build());
            }
        }

        // -- CLI persistent sessions --
        // 长驻 CLI 进程由 CliPersistentProcessRegistry 槽位追踪,合并进面板只读展示。
        // pid 计入 knownPids:即使 orphan 扫描的 hint 匹配到其命令行,也不会重复报为孤儿。
        try {
            for (CliPersistentProcess.PersistentProcessInfo info
                    : CliPersistentProcessRegistry.getInstance(project).describeAll()) {
                if (info.pid() <= 0) {
                    continue;
                }
                knownPids.add(info.pid());
                long uptime = info.startedAtMs() > 0 ? Math.max(0, now - info.startedAtMs()) : 0L;
                result.add(NodeProcessInfo.builder()
                        .kind(NodeProcessInfo.Kind.CLI_SESSION)
                        .provider(info.provider())
                        .pid(info.pid())
                        .alive(info.state() != CliPersistentProcess.State.DEAD)
                        .startedAtMs(info.startedAtMs())
                        .uptimeMs(uptime)
                        .channelId(info.tabId())
                        .sessionId(info.sessionId())
                        .activeRequestCount(info.state() == CliPersistentProcess.State.STREAMING ? 1 : 0)
                        .build());
            }
        } catch (Exception e) {
            // 面板展示不得因长驻注册表异常整体失败,降级为不显示该分组
            LOG.warn("[NodeProcessRegistry] persistent CLI describe failed: " + e.getMessage());
        }

        // -- ORPHAN scan --
        // Find Node processes that look like ours but don't appear in any registry.
        // CRITICAL: we MUST only consider processes whose parent PID is this JVM.
        // When multiple IDEs (e.g. IDEA + PyCharm) both run AI Code GUI, each instance's
        // node subprocesses would otherwise show up in every other instance's panel — and
        // "Kill all orphans" would terminate live work in foreign IDEs. Each JVM
        // is responsible only for its own children.
        final long currentJvmPid;
        try {
            currentJvmPid = ProcessHandle.current().pid();
        } catch (Exception e) {
            LOG.warn("[NodeProcessRegistry] Cannot resolve current JVM PID, skipping orphan scan: "
                    + e.getMessage());
            return result;
        }

        final long scanDeadline = System.currentTimeMillis() + ORPHAN_SCAN_BUDGET_MS;
        // AtomicBoolean so the forEach lambda can mutate it (effectively-final restriction).
        // Checkstyle forbids single-element arrays for this purpose.
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        try {
            ProcessHandle.allProcesses().forEach(handle -> {
                if (timedOut.get()) {
                    // Lambda return only skips the current element, but subsequent
                    // iterations will hit this guard within microseconds — cheaper
                    // than throwing or short-circuiting via takeWhile.
                    return;
                }
                if (System.currentTimeMillis() > scanDeadline) {
                    timedOut.set(true);
                    return;
                }
                long pid = handle.pid();
                if (knownPids.contains(pid)) {
                    return;
                }
                ProcessHandle.Info info = handle.info();
                String cmdLine = info.commandLine().orElse(null);
                String cmd = info.command().orElse(null);
                String fingerprint = cmdLine != null ? cmdLine : cmd;
                if (fingerprint == null) {
                    return;
                }
                if (!looksLikeOurProcess(fingerprint)) {
                    return;
                }

                // Ownership check: skip processes spawned by another JVM
                long parentPid = handle.parent().map(ProcessHandle::pid).orElse(-1L);
                if (!isOwnedByJvm(parentPid, currentJvmPid)) {
                    return;
                }

                long startedAt = info.startInstant().map(Instant::toEpochMilli).orElse(-1L);
                result.add(NodeProcessInfo.builder()
                        .kind(NodeProcessInfo.Kind.ORPHAN)
                        .provider(detectProviderFromCmd(fingerprint))
                        .pid(pid)
                        .alive(handle.isAlive())
                        .startedAtMs(startedAt)
                        .uptimeMs(startedAt > 0 ? Math.max(0, now - startedAt) : 0L)
                        .command(fingerprint)
                        .build());
            });
            if (timedOut.get()) {
                LOG.warn("[NodeProcessRegistry] Orphan scan exceeded " + ORPHAN_SCAN_BUDGET_MS
                        + "ms budget; returning partial results. Some orphans may not be listed.");
            }
        } catch (Exception e) {
            LOG.warn("[NodeProcessRegistry] Orphan scan failed: " + e.getMessage());
        }

        return result;
    }

    /**
     * Returns true when a process with the given parent PID was spawned by
     * THIS JVM and is therefore a valid orphan candidate for this instance.
     *
     * <p>Package-private so unit tests can pin the matrix:
     * <ul>
     *   <li>parent == currentJvmPid: ours (true)</li>
     *   <li>parent != currentJvmPid (another IDE instance): NOT ours (false)</li>
     *   <li>parent &lt;= 0 (parent died / unresolved): not safely attributable (false)</li>
     * </ul>
     */
    static boolean isOwnedByJvm(long parentPid, long currentJvmPid) {
        return parentPid > 0 && parentPid == currentJvmPid;
    }

    // ============================================================================
    // Process termination
    // ============================================================================

    /**
     * Terminate a process tree by PID. Uses the existing {@link PlatformUtils} helpers
     * for cross-platform correctness. Returns true when the kill command executed
     * successfully — does not guarantee the process is fully reaped yet.
     *
     * <p>Ownership guard: only PIDs that appear in this JVM's own {@link #snapshot()}
     * are eligible. Snapshot entries are either registry-tracked (channel/CLI session) or
     * orphans that already passed the {@link #isOwnedByJvm} parent-PID check. Without
     * this guard a malformed or hostile frontend payload could ask us to terminate an
     * arbitrary process tree on the host (the PID arrives untrusted via
     * {@code NodeProcessHandler.handleKillNodeProcess}).
     */
    public boolean killByPid(long pid) {
        if (pid <= 0) {
            return false;
        }
        List<NodeProcessInfo> snapshot = snapshot();
        if (!isPidOwned(pid, pidsOf(snapshot))) {
            LOG.warn("[NodeProcessRegistry] Refusing to kill PID " + pid
                    + " — not tracked by this JVM's snapshot");
            return false;
        }
        // CLI_SESSION 双层防护第一层(真正防线):长驻会话进程由 registry 生命周期
        // 管理(空闲回收/tab 关闭/项目关闭),手动 kill 只会留下 dirty 槽位。
        if (isProtectedKind(kindOfPid(pid, snapshot))) {
            LOG.warn("[NodeProcessRegistry] Refusing to kill PID " + pid
                    + " — cli_session_protected");
            return false;
        }
        return terminateTrackedPid(pid);
    }

    /**
     * Kill 前检查目标是否为受保护的 CLI_SESSION 进程。
     * 返回错误码 {@code cli_session_protected}(前端提示用),非保护目标返回 null。
     * killByPid 内部另有同判定闸门——此处仅供 handler 预检以透传具体拒绝原因。
     */
    public @Nullable String checkKillProtected(long pid) {
        if (pid <= 0) {
            return null;
        }
        return isProtectedKind(kindOfPid(pid, snapshot())) ? KILL_PROTECTED_CLI_SESSION : null;
    }

    /** CLI_SESSION 拒绝码:前端据此渲染保护提示。 */
    public static final String KILL_PROTECTED_CLI_SESSION = "cli_session_protected";

    /** 受保护不可手动 kill 的 kind 判定。静态纯函数供单测。 */
    static boolean isProtectedKind(NodeProcessInfo.Kind kind) {
        return kind == NodeProcessInfo.Kind.CLI_SESSION;
    }

    /** 在快照中按 pid 查找 kind;找不到返回 null。静态纯函数供单测。 */
    static @Nullable NodeProcessInfo.Kind kindOfPid(long pid, List<NodeProcessInfo> snapshot) {
        if (snapshot == null) {
            return null;
        }
        for (NodeProcessInfo info : snapshot) {
            if (info.getPid() == pid) {
                return info.getKind();
            }
        }
        return null;
    }

    /** Collect the PID set of every process in the given snapshot. */
    private static Set<Long> pidsOf(List<NodeProcessInfo> snapshot) {
        Set<Long> pids = new HashSet<>();
        for (NodeProcessInfo info : snapshot) {
            pids.add(info.getPid());
        }
        return pids;
    }

    /**
     * Pure ownership predicate for the kill guard. Package-private so the
     * security-sensitive decision can be unit-tested without a live Project.
     */
    static boolean isPidOwned(long pid, Set<Long> ownedPids) {
        return pid > 0 && ownedPids != null && ownedPids.contains(pid);
    }

    /**
     * Unconditional kill — callers MUST have already established that {@code pid}
     * belongs to this JVM (via the {@link #killByPid} guard or a freshly built
     * snapshot in {@link #killAllOrphans}). Kept private to prevent new unguarded
     * termination entry points.
     */
    private boolean terminateTrackedPid(long pid) {
        LOG.info("[NodeProcessRegistry] Killing process tree for PID " + pid);
        return PlatformUtils.terminateProcessTree(pid);
    }

    /**
     * Bulk kill every orphan reported in the current snapshot. Returns the number
     * of processes for which the kill command was successfully dispatched.
     */
    public int killAllOrphans() {
        int killed = 0;
        // Orphans in the snapshot already passed the isOwnedByJvm check, so kill
        // them directly — re-running killByPid would rebuild the snapshot per PID.
        for (NodeProcessInfo info : snapshot()) {
            if (info.getKind() == NodeProcessInfo.Kind.ORPHAN && terminateTrackedPid(info.getPid())) {
                killed++;
            }
        }
        return killed;
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    // Package-private for unit testing.
    static boolean looksLikeOurProcess(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        String lower = fingerprint.toLowerCase();
        for (String hint : OWNED_PROCESS_HINTS) {
            if (lower.contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    // Package-private for unit testing.
    static @Nullable String detectProviderFromCmd(String cmd) {
        if (cmd == null) {
            return null;
        }
        String lower = cmd.toLowerCase();
        // daemon.js: leftover process from the removed SDK daemon mode (older plugin
        // versions); such daemons were Claude-only, hence PROVIDER_CLAUDE.
        if (lower.contains("daemon.js")) {
            return CommonConstants.PROVIDER_CLAUDE;
        }
        if (lower.contains("mcp-gateway-server.js") || lower.contains("gateway-stdio-client.js")) {
            return "mcp-gateway";
        }
        if (lower.contains("codex")) {
            return CommonConstants.PROVIDER_CODEX;
        }
        if (lower.contains("claude")) {
            return CommonConstants.PROVIDER_CLAUDE;
        }
        return null;
    }

    private static @Nullable ProcessHandle.Info safeInfo(Process p) {
        try {
            return p.toHandle().info();
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable String extractCommand(@Nullable ProcessHandle.Info info) {
        if (info == null) {
            return null;
        }
        Optional<String> cmdLine = info.commandLine();
        if (cmdLine.isPresent()) {
            return cmdLine.get();
        }
        // Windows fallback: commandLine() may return empty for cross-owner processes
        return info.command().orElse(null);
    }

    private static @Nullable String safeGetSessionId(ClaudeChatWindow window) {
        try {
            String sid = window != null ? window.getSessionId() : null;
            return (sid != null && !sid.isEmpty()) ? sid : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeGetCurrentProvider(ClaudeChatWindow window) {
        try {
            if (window == null) {
                return CommonConstants.PROVIDER_CLAUDE;
            }
            String provider = window.getCurrentProvider();
            return provider != null && !provider.isEmpty() ? provider : CommonConstants.PROVIDER_CLAUDE;
        } catch (Exception e) {
            return CommonConstants.PROVIDER_CLAUDE;
        }
    }

    private static @Nullable String resolveTabName(ClaudeChatWindow window) {
        try {
            if (window == null) {
                return null;
            }
            // Try parent content's display name first (e.g., "AI1", "AI2")
            Content content = window.getParentContent();
            if (content != null) {
                String displayName = content.getDisplayName();
                if (displayName != null && !displayName.isEmpty()) {
                    return displayName;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void dispose() {
        // No long-lived resources — the registry is a pure aggregator.
        // Individual processes are owned by their respective bridges and disposed there.
    }
}
