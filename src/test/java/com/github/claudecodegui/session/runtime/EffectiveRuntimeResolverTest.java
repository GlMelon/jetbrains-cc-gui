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
        // 方向 A:codex 经「调用模式」UI 切到 CLI(sendToCodex 透传 requestedMode="cli")时,
        // supported 含 CLI,应解析为 CLI(请求级优先于「路由策略」面板的 default)。
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

    @Test
    public void codexUsesPolicyDefaultWhenNoRequestedMode() {
        // codex 无前端调用模式传递(requestedMode=null),应取 policy.default(用户在「路由策略」面板的选择),
        // 而非被 fromInvocationMode(null)=SDK 绕过。default=CLI 时应走 CLI。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, null, policy);

        assertEquals(ProviderType.CODEX, runtime.provider());
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
