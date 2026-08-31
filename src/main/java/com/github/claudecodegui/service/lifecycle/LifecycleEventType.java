package com.github.claudecodegui.service.lifecycle;

/** Structured lifecycle transitions emitted by owned process paths. */
public enum LifecycleEventType {
    SPAWN,
    STDIN_CLOSE,
    STDOUT_EOF,
    EXIT,
    TERMINATE,
    REBUILD,
    FALLBACK,
    DEGRADED
}
