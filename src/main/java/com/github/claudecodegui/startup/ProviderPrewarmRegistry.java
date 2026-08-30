package com.github.claudecodegui.startup;

import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Explicit Provider prewarm registry.
 *
 * <p>The registry is the only provider dispatch point for startup prewarming.
 * It validates the complete ProviderType matrix and rejects duplicate
 * registrations so a newly added provider cannot silently miss startup policy.</p>
 */
public final class ProviderPrewarmRegistry {

    private static final Duration RESOLVER_TIMEOUT = Duration.ofSeconds(10);
    private final Map<ProviderType, ProviderPrewarmStrategy> strategies;
    private final List<ProviderPrewarmStrategy> orderedStrategies;

    public ProviderPrewarmRegistry(List<? extends ProviderPrewarmStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        EnumMap<ProviderType, ProviderPrewarmStrategy> registered = new EnumMap<>(ProviderType.class);
        for (ProviderPrewarmStrategy strategy : strategies) {
            Objects.requireNonNull(strategy, "strategy");
            ProviderType provider = Objects.requireNonNull(strategy.provider(), "strategy.provider()");
            if (registered.put(provider, strategy) != null) {
                throw new IllegalArgumentException("Duplicate provider prewarm strategy: " + provider);
            }
        }
        List<ProviderType> missing = new ArrayList<>();
        for (ProviderType provider : ProviderType.values()) {
            if (!registered.containsKey(provider)) {
                missing.add(provider);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing provider prewarm strategies: " + missing);
        }
        this.strategies = Map.copyOf(registered);
        this.orderedStrategies = List.copyOf(registered.values());
    }

    public static ProviderPrewarmRegistry defaultRegistry() {
        return new ProviderPrewarmRegistry(List.of(
                resolver(ProviderType.CODEX, RESOLVER_TIMEOUT, CodexCliResolver::findExecutable),
                resolver(ProviderType.OPENCODE, RESOLVER_TIMEOUT, OpenCodeCliResolver::findExecutable),
                resolver(ProviderType.KIMI, RESOLVER_TIMEOUT,
                        () -> new ProviderCliResolver(
                                ProviderType.KIMI, ProviderType.KIMI.cliCommand()).findExecutable()),
                resolver(ProviderType.GROK, RESOLVER_TIMEOUT,
                        () -> new ProviderCliResolver(
                                ProviderType.GROK, ProviderType.GROK.cliCommand()).findExecutable()),
                resolver(ProviderType.PI, RESOLVER_TIMEOUT,
                        () -> new ProviderCliResolver(
                                ProviderType.PI, ProviderType.PI.cliCommand()).findExecutable()),
                explicitNoPrewarm(
                        ProviderType.CLAUDE,
                        new ProviderPrewarmPolicy(false, false, false, false, false,
                                Duration.ofSeconds(1), PrewarmFallback.RETRY_ON_FIRST_USE)),
                explicitNoPrewarm(
                        ProviderType.OMP,
                        new ProviderPrewarmPolicy(false, false, true, false, false,
                                Duration.ofSeconds(5), PrewarmFallback.DIRECT_CHANNEL)),
                explicitNoPrewarm(
                        ProviderType.DSH,
                        new ProviderPrewarmPolicy(false, false, true, true, false,
                                Duration.ofSeconds(5), PrewarmFallback.HOST_CHANNEL))
        ));
    }

    public ProviderPrewarmStrategy strategy(ProviderType provider) {
        return strategies.get(Objects.requireNonNull(provider, "provider"));
    }

    public List<ProviderPrewarmStrategy> strategies() {
        return orderedStrategies;
    }

    private static ProviderPrewarmStrategy resolver(
            ProviderType provider,
            Duration timeout,
            Supplier<String> resolver
    ) {
        ProviderPrewarmPolicy policy = new ProviderPrewarmPolicy(
                true, true, false, false, false, timeout, PrewarmFallback.RETRY_ON_FIRST_USE);
        return new ProviderPrewarmStrategy() {
            @Override
            public ProviderType provider() {
                return provider;
            }

            @Override
            public ProviderPrewarmPolicy policy() {
                return policy;
            }

            @Override
            public void prewarm(BooleanSupplier cancelled) {
                if (isCancelled(cancelled)) {
                    return;
                }
                resolver.get();
            }
        };
    }

    private static ProviderPrewarmStrategy explicitNoPrewarm(
            ProviderType provider,
            ProviderPrewarmPolicy policy
    ) {
        return new ProviderPrewarmStrategy() {
            @Override
            public ProviderType provider() {
                return provider;
            }

            @Override
            public ProviderPrewarmPolicy policy() {
                return policy;
            }

            @Override
            public void prewarm(BooleanSupplier cancelled) {
                // The policy is the explicit record of this provider's channel
                // architecture; no resolver probe is performed here.
                if (isCancelled(cancelled)) {
                    return;
                }
            }
        };
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean());
    }
}
