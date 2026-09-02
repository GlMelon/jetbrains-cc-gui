package com.github.claudecodegui.startup;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.project.Project;

import java.util.function.BooleanSupplier;

/** Provider-specific startup prewarm strategy. */
public interface ProviderPrewarmStrategy {

    ProviderType provider();

    ProviderPrewarmPolicy policy();

    /**
     * Runs the provider probe. Implementations must not cache a failed probe
     * as a permanent detector failure and must return promptly when cancelled.
     *
     * <p>{@code project} gives project-scoped context (base path, project services)
     * for strategies that warm more than a global resolver cache; resolver/channel
     * probes may ignore it.</p>
     */
    void prewarm(Project project, BooleanSupplier cancelled);
}
