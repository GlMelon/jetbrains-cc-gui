package com.github.claudecodegui.service.lifecycle;

/** Immutable lifecycle event retained in the bounded project-local event journal. */
public record LifecycleEvent(
        LifecycleEventType type,
        long occurredAtMs,
        long pid,
        ProcessLifecycleMetadata metadata,
        String detail
) {
}
