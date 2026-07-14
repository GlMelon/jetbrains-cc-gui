package com.github.claudecodegui.provider.common;

/**
 * Daemon bridge wire 协议常量集中管理。
 * <p>
 * Daemon Bridge 与 Node.js daemon 进程通信时使用的事件、方法名、消息类型、
 * 日志级别等魔法字符串。集中管理以便协议升级时单点修改，并消除 DaemonBridge 中的分散字面量。
 *
 * @see DaemonBridge
 */
public final class DaemonConstants {

    private DaemonConstants() {
        // 工具类禁止实例化
    }

    // ── Daemon 事件类型（event 字段值） ──────────────────────────────────────

    public static final String EVENT_READY = "ready";
    public static final String EVENT_SDK_LOADED = "sdk_loaded";
    public static final String EVENT_SDK_LOAD_ERROR = "sdk_load_error";
    public static final String EVENT_SHUTDOWN = "shutdown";
    public static final String EVENT_TITLE_LOG = "title_log";
    public static final String EVENT_TITLE_GENERATED = "title_generated";
    public static final String EVENT_QUEUE_WAITING = "queue_waiting";
    public static final String EVENT_QUEUE_STARTED = "queue_started";
    public static final String EVENT_QUEUE_CLEARED = "queue_cleared";
    public static final String EVENT_SESSION_UPDATED = "session_updated";

    // ── Daemon 方法名（method 字段值） ───────────────────────────────────────

    public static final String METHOD_HEARTBEAT = "heartbeat";
    public static final String METHOD_STATUS = "status";
    public static final String METHOD_SHUTDOWN = "shutdown";
    public static final String METHOD_ABORT = "abort";

    // ── Daemon 消息类型（type 字段值） ───────────────────────────────────────

    public static final String TYPE_DAEMON = "daemon";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_STATUS = "status";

    // ── 日志级别（level 字段值） ─────────────────────────────────────────────

    public static final String LEVEL_INFO = "info";
    public static final String LEVEL_WARN = "warn";
    public static final String LEVEL_ERROR = "error";

    // ── 哨兵值 ───────────────────────────────────────────────────────────────

    /** 未知事件/错误的占位值 */
    public static final String UNKNOWN = "unknown";

    // ── Request lifecycle ────────────────────────────────────────────────────

    /** Absolute deadline for one daemon-backed chat turn. */
    public static final long REQUEST_TIMEOUT_MS = 15 * 60 * 1000L;
    /** Poll interval used to retain daemon liveness diagnostics while waiting. */
    public static final long REQUEST_POLL_INTERVAL_MS = 30_000L;
}
