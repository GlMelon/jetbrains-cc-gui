package com.github.claudecodegui.startup;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.function.BooleanSupplier;

/** Provider-specific startup prewarm strategy. */
public interface ProviderPrewarmStrategy {

    ProviderType provider();

    ProviderPrewarmPolicy policy();

    /**
     * Runs the provider probe. Implementations must not cache a failed probe
     * as a permanent detector failure and must return promptly when cancelled.
     */
    void prewarm(BooleanSupplier cancelled);
}
