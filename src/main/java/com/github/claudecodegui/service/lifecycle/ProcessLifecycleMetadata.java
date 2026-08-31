package com.github.claudecodegui.service.lifecycle;

/** Correlation plus the physical process domain, kept separate from gateway generations. */
public record ProcessLifecycleMetadata(
        LifecycleProcessKind processKind,
        ProcessLifecycleCorrelation correlation
) {
}
