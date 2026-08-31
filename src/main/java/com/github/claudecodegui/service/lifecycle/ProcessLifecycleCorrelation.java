package com.github.claudecodegui.service.lifecycle;

/** Immutable identifiers that correlate a process event to project/session/turn state. */
public record ProcessLifecycleCorrelation(
        String projectLifecycleId,
        String runtimeSessionEpoch,
        Long responseTurnEpoch,
        Long processGeneration
) {
}
