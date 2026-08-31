package com.github.claudecodegui.service;

/**
 * DTO representing a single Node.js process tracked by the management panel.
 * Aggregates per-channel processes, persistent CLI session processes, and orphan
 * processes detected via system-level ProcessHandle scanning.
 *
 * <p>Five kinds:
 * <ul>
 *   <li>{@link Kind#DAEMON}: legacy Claude daemon process. The SDK(daemon) invocation
 *       mode has been removed; this kind is retained only so the panel can still
 *       identify (and let the user clean up) daemon processes left behind by older
 *       plugin versions.</li>
 *   <li>{@link Kind#CHANNEL}: per-request process tracked in a ProcessManager</li>
 *   <li>{@link Kind#MCP_GATEWAY}: MCP Gateway server or stdio client process. A
 *       registered Gateway is active; an OS-scanned, unregistered Gateway keeps
 *       this kind but is additionally marked {@link #isOrphan() orphan}.</li>
 *   <li>{@link Kind#ORPHAN}: detected via ProcessHandle.allProcesses() but not in any registry</li>
 *   <li>{@link Kind#CLI_SESSION}: persistent CLI session process
 *       ({@code CliPersistentProcessRegistry}) — read-only,
 *       protected from kill</li>
 * </ul>
 *
 * <p>Serialized to JSON via Gson for the webview's Node Process Management panel.
 */
public final class NodeProcessInfo {

    public enum Kind {
        DAEMON("DAEMON"),
        CHANNEL("CHANNEL"),
        MCP_GATEWAY("MCP_GATEWAY"),
        ORPHAN("ORPHAN"),
        CLI_SESSION("CLI_SESSION");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Synthetic identifier — used by the panel to dedupe entries across snapshots. */
    private final String id;

    /** What kind of process this is. */
    private final Kind kind;

    /** Provider identifier; null for Gateway and processes without an identifiable provider. */
    private final String provider;

    /** Operating system PID. */
    private final long pid;

    /** True when the OS reports the process is still alive at snapshot time. */
    private final boolean alive;

    /** Process start time in epoch milliseconds. -1 when not available (e.g., on some Windows hosts). */
    private final long startedAtMs;

    /** Snapshot uptime in milliseconds (now - startedAtMs). 0 when startedAtMs is -1. */
    private final long uptimeMs;

    /** Command line as reported by ProcessHandle.Info, or null when unavailable. */
    private final String command;

    /** For legacy DAEMON entries: heap memory reported by the old daemon's status response. -1 when unknown. */
    private final long heapUsedBytes;

    /** For legacy DAEMON entries: number of in-flight requests reported by the old daemon. */
    private final int activeRequestCount;

    /** For CHANNEL only: the channelId in ProcessManager.activeChannelProcesses. */
    private final String channelId;

    /** Session identifier associated with this process; null when the process is not session-bound. */
    private final String sessionId;

    /** Tab name shown in the IDE (e.g., "AI1") so users can correlate process → tab. */
    private final String tabName;

    /** Stable Project lifecycle identifier; null when ownership is unknown. */
    private final String projectLifecycleId;

    /** Runtime session epoch; null for project-only, orphan, and auxiliary processes. */
    private final String runtimeSessionEpoch;

    /** Response turn epoch; null when no active turn is known. */
    private final Long responseTurnEpoch;

    /** Physical process generation; null when no owner allocated one. */
    private final Long processGeneration;

    /**
     * True when the process appears in the OS process list but our registries
     * don't know about it. Highlighted in the panel with a warning color.
     */
    private final boolean orphan;

    private NodeProcessInfo(Builder builder) {
        this.id = builder.id;
        this.kind = builder.kind;
        this.provider = builder.provider;
        this.pid = builder.pid;
        this.alive = builder.alive;
        this.startedAtMs = builder.startedAtMs;
        this.uptimeMs = builder.uptimeMs;
        this.command = builder.command;
        this.heapUsedBytes = builder.heapUsedBytes;
        this.activeRequestCount = builder.activeRequestCount;
        this.channelId = builder.channelId;
        this.sessionId = builder.sessionId;
        this.tabName = builder.tabName;
        this.projectLifecycleId = builder.projectLifecycleId;
        this.runtimeSessionEpoch = builder.runtimeSessionEpoch;
        this.responseTurnEpoch = builder.responseTurnEpoch;
        this.processGeneration = builder.processGeneration;
        this.orphan = builder.orphan || builder.kind == Kind.ORPHAN;
    }

    public String getId() { return id; }
    public Kind getKind() { return kind; }
    public String getProvider() { return provider; }
    public long getPid() { return pid; }
    public boolean isAlive() { return alive; }
    public long getStartedAtMs() { return startedAtMs; }
    public long getUptimeMs() { return uptimeMs; }
    public String getCommand() { return command; }
    public long getHeapUsedBytes() { return heapUsedBytes; }
    public int getActiveRequestCount() { return activeRequestCount; }
    public String getChannelId() { return channelId; }
    public String getSessionId() { return sessionId; }
    public String getTabName() { return tabName; }
    public boolean isOrphan() { return orphan; }
    public String getProjectLifecycleId() { return projectLifecycleId; }
    public String getRuntimeSessionEpoch() { return runtimeSessionEpoch; }
    public Long getResponseTurnEpoch() { return responseTurnEpoch; }
    public Long getProcessGeneration() { return processGeneration; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private Kind kind;
        private String provider;
        private long pid;
        private boolean alive = true;
        private long startedAtMs = -1L;
        private long uptimeMs = 0L;
        private String command;
        private long heapUsedBytes = -1L;
        private int activeRequestCount = 0;
        private String channelId;
        private String sessionId;
        private String tabName;
        private String projectLifecycleId;
        private String runtimeSessionEpoch;
        private Long responseTurnEpoch;
        private Long processGeneration;
        private boolean orphan;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder pid(long pid) {
            this.pid = pid;
            return this;
        }

        public Builder alive(boolean alive) {
            this.alive = alive;
            return this;
        }

        public Builder startedAtMs(long startedAtMs) {
            this.startedAtMs = startedAtMs;
            return this;
        }

        public Builder uptimeMs(long uptimeMs) {
            this.uptimeMs = uptimeMs;
            return this;
        }

        public Builder command(String command) {
            this.command = command;
            return this;
        }

        public Builder heapUsedBytes(long heapUsedBytes) {
            this.heapUsedBytes = heapUsedBytes;
            return this;
        }

        public Builder activeRequestCount(int n) {
            this.activeRequestCount = n;
            return this;
        }

        public Builder channelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder tabName(String tabName) {
            this.tabName = tabName;
            return this;
        }

        public Builder projectLifecycleId(String projectLifecycleId) {
            this.projectLifecycleId = projectLifecycleId;
            return this;
        }

        public Builder runtimeSessionEpoch(String runtimeSessionEpoch) {
            this.runtimeSessionEpoch = runtimeSessionEpoch;
            return this;
        }

        public Builder responseTurnEpoch(Long responseTurnEpoch) {
            this.responseTurnEpoch = responseTurnEpoch;
            return this;
        }

        public Builder processGeneration(Long processGeneration) {
            this.processGeneration = processGeneration;
            return this;
        }

        public Builder orphan(boolean orphan) {
            this.orphan = orphan;
            return this;
        }

        public NodeProcessInfo build() {
            if (kind == null) {
                throw new IllegalStateException("NodeProcessInfo requires kind");
            }
            if (id == null || id.isEmpty()) {
                // Synthesize a stable id from kind + pid + (channelId or sessionId)
                String suffix = channelId != null ? channelId : (sessionId != null ? sessionId : "");
                this.id = kind.name().toLowerCase() + "-" + pid + (suffix.isEmpty() ? "" : "-" + suffix);
            }
            return new NodeProcessInfo(this);
        }
    }
}
