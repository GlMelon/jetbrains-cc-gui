package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class EffectiveRuntimeResolverTest {

    @Test
    public void codexRuntimeComesFromPolicyInsteadOfHardcodedCli() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, null, policy);

        assertEquals(ProviderType.CODEX, runtime.provider());
        assertEquals(RuntimeType.SDK, runtime.runtimeType());
    }

    @Test
    public void requestedRuntimeWinsWhenProviderSupportsIt() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "cli", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
    }

    @Test
    public void unsupportedRequestedRuntimeFallsBackToProviderDefault() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "sdk", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
    }

    @Test(expected = IllegalStateException.class)
    public void disabledProviderIsRejected() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(false, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.resolve("codex", null, null, policy);
    }

    private static RuntimePolicyConfig policy(ProviderRuntimePolicy claude, ProviderRuntimePolicy codex) {
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE, claude);
        providers.put(ProviderType.CODEX, codex);
        return new RuntimePolicyConfig(providers);
    }
}
