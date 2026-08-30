package com.github.claudecodegui.startup;

import java.time.Duration;
import java.util.Objects;

/**
 * Declarative prewarm contract for one provider.
 *
 * <p>The flags describe what a startup strategy owns. A false flag is an
 * intentional capability difference, not an implicit omission in the
 * preloader.</p>
 */
public record ProviderPrewarmPolicy(
        boolean executableProbe,
        boolean versionProbe,
        boolean channelProbe,
        boolean configurationLoad,
        boolean capabilityNegotiation,
        Duration timeout,
        PrewarmFallback fallback
) {

    public ProviderPrewarmPolicy {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(fallback, "fallback");
    }
}
