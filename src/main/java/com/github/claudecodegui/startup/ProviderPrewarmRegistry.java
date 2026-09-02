package com.github.claudecodegui.startup;

import com.github.claudecodegui.cli.common.ProviderCliResolver;
import com.github.claudecodegui.cli.opencode.OpenCodeCliResolver;
import com.github.claudecodegui.provider.claude.ClaudeCliDetector;
import com.github.claudecodegui.session.runtime.CodexCliResolver;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpChannelGate;
import com.github.claudecodegui.cli.kimi.acp.KimiAcpWarmPool;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

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

    private static final Logger LOG = Logger.getInstance(ProviderPrewarmRegistry.class);
    private static final Duration RESOLVER_TIMEOUT = Duration.ofSeconds(10);
    /** KIMI 除 resolver 探测外还要 spawn + initialize 暖连接(node 冷启实测 ~1s,留足余量)。 */
    private static final Duration KIMI_WARM_TIMEOUT = Duration.ofSeconds(20);
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
                kimiAcpWarm(),
                resolver(ProviderType.GROK, RESOLVER_TIMEOUT,
                        () -> new ProviderCliResolver(
                                ProviderType.GROK, ProviderType.GROK.cliCommand()).findExecutable()),
                resolver(ProviderType.PI, RESOLVER_TIMEOUT,
                        () -> new ProviderCliResolver(
                                ProviderType.PI, ProviderType.PI.cliCommand()).findExecutable()),
                resolver(ProviderType.CLAUDE, RESOLVER_TIMEOUT,
                        () -> ClaudeCliDetector.getInstance().findCliExecutable()),
                channel(ProviderType.OMP, Duration.ofSeconds(5),
                        ProviderChannelPrewarm.COMMAND_LIST_MODELS, PrewarmFallback.DIRECT_CHANNEL),
                channel(ProviderType.DSH, Duration.ofSeconds(5),
                        ProviderChannelPrewarm.COMMAND_STATUS, PrewarmFallback.HOST_CHANNEL)
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
            public void prewarm(Project project, BooleanSupplier cancelled) {
                if (isCancelled(cancelled)) {
                    return;
                }
                resolver.get();
            }
        };
    }

    /**
     * KIMI 策略:先做 resolver 探测(填充版本缓存,供 ACP 门禁判定),再深化预热——
     * spawn 一个已完成 initialize 握手的 {@code kimi acp} 暖连接入
     * {@link KimiAcpWarmPool},首发消息直接 adopt,把 node 冷启 + 握手从发送链上消除。
     * 暖连接失败/门禁不通过不影响 resolver 探测结果(首发回退冷启动/legacy,行为不变)。
     */
    private static ProviderPrewarmStrategy kimiAcpWarm() {
        ProviderPrewarmPolicy policy = new ProviderPrewarmPolicy(
                true, true, false, false, false, KIMI_WARM_TIMEOUT, PrewarmFallback.RETRY_ON_FIRST_USE);
        return new ProviderPrewarmStrategy() {
            @Override
            public ProviderType provider() {
                return ProviderType.KIMI;
            }

            @Override
            public ProviderPrewarmPolicy policy() {
                return policy;
            }

            @Override
            public void prewarm(Project project, BooleanSupplier cancelled) {
                if (isCancelled(cancelled)) {
                    return;
                }
                new ProviderCliResolver(ProviderType.KIMI, ProviderType.KIMI.cliCommand()).findExecutable();
                if (isCancelled(cancelled) || project == null || project.isDisposed()
                        || !KimiAcpChannelGate.isAcpEligible()) {
                    return;
                }
                try {
                    KimiAcpWarmPool.getInstance(project).warm(cancelled);
                } catch (Exception | LinkageError e) {
                    LOG.warn("[ProviderPrewarmRegistry] kimi ACP warm-up failed (first send falls back to cold start): "
                            + e.getMessage());
                }
            }
        };
    }

    private static ProviderPrewarmStrategy channel(
            ProviderType provider,
            Duration timeout,
            String command,
            PrewarmFallback fallback
    ) {
        ProviderPrewarmPolicy policy = new ProviderPrewarmPolicy(
                false, false, true, provider == ProviderType.DSH, false, timeout, fallback);
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
            public void prewarm(Project project, BooleanSupplier cancelled) {
                if (!isCancelled(cancelled)) {
                    ProviderChannelPrewarm.probe(provider, command, timeout, cancelled);
                }
            }
        };
    }
    private static boolean isCancelled(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean());
    }
}
