package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EffectiveRuntimeResolverTest {

    @Test
    public void resolvesEveryProviderFromCurrentPolicyDefault() {
        RuntimePolicyConfig policy = policy(RuntimeType.CLI, RuntimeType.SDK, RuntimeType.CLI);

        assertEquals(RuntimeType.CLI, EffectiveRuntimeResolver.resolve(ProviderType.CLAUDE.value(), policy).runtimeType());
        assertEquals(RuntimeType.SDK, EffectiveRuntimeResolver.resolve(ProviderType.CODEX.value(), policy).runtimeType());
        assertEquals(RuntimeType.CLI, EffectiveRuntimeResolver.resolve(ProviderType.OPENCODE.value(), policy).runtimeType());
    }

    @Test
    public void policyChangeTakesEffectWithoutSessionSnapshot() {
        RuntimePolicyConfig sdkPolicy = policy(RuntimeType.SDK, RuntimeType.SDK, RuntimeType.SDK);
        RuntimePolicyConfig cliPolicy = policy(RuntimeType.CLI, RuntimeType.CLI, RuntimeType.CLI);

        assertFalse(EffectiveRuntimeResolver.isCliMode(ProviderType.CLAUDE.value(), sdkPolicy));
        assertTrue(EffectiveRuntimeResolver.isCliMode(ProviderType.CLAUDE.value(), cliPolicy));
    }

    @Test
    public void disabledProviderFailsFast() {
        RuntimePolicyConfig policy = policy(RuntimeType.SDK, RuntimeType.SDK, RuntimeType.SDK);
        policy.providers().put(ProviderType.CODEX,
                new ProviderRuntimePolicy(false, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));

        assertThrows(IllegalStateException.class,
                () -> EffectiveRuntimeResolver.resolve(ProviderType.CODEX.value(), policy));
    }

    @Test
    public void missingProviderIsFilledFromBackendDefaults() {
        RuntimePolicyConfig partial = new RuntimePolicyConfig();
        partial.providers().put(ProviderType.CLAUDE,
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.CLI));

        assertEquals(RuntimeType.CLI,
                EffectiveRuntimeResolver.resolve(ProviderType.CLAUDE.value(), partial).runtimeType());
        assertEquals(RuntimeType.SDK,
                EffectiveRuntimeResolver.resolve(ProviderType.OPENCODE.value(), partial).runtimeType());
    }

    private static RuntimePolicyConfig policy(RuntimeType claude, RuntimeType codex, RuntimeType opencode) {
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        Set<RuntimeType> supported = Set.of(RuntimeType.SDK, RuntimeType.CLI);
        providers.put(ProviderType.CLAUDE, new ProviderRuntimePolicy(true, supported, claude));
        providers.put(ProviderType.CODEX, new ProviderRuntimePolicy(true, supported, codex));
        providers.put(ProviderType.OPENCODE, new ProviderRuntimePolicy(true, supported, opencode));
        return new RuntimePolicyConfig(providers);
    }
}
