package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void degradedFlagTrueWhenRequestedRuntimeUnsupportedForCodex() {
        // 冲突点 B:用户经「调用模式」请求 sdk,但「路由策略」codex 仅支持 CLI → 被降级到 default=CLI。
        // 此前静默降级零反馈;现 resolve 标记 degraded=true 供调用方提示用户。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "sdk", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertTrue(runtime.degraded());
    }

    @Test
    public void degradedFlagFalseWhenRequestedRuntimeHonoredForCodex() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "cli", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    @Test
    public void degradedFlagFalseWhenCodexHasNoExplicitRequest() {
        // requestedMode=null(调用模式未加载)→ 走 policy.default,用户未明确请求,不算降级。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, null, policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    @Test
    public void degradedFlagTrueWhenClaudeRequestedRuntimeUnsupported() {
        // claude 仅支持 SDK,会话级模式 cli → 降级到 default=SDK。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "claude", "cli", null, policy);

        assertEquals(RuntimeType.SDK, runtime.runtimeType());
        assertTrue(runtime.degraded());
    }

    @Test
    public void degradedNoticeNullWhenNotDegraded() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        // 请求 cli 被策略采纳,非降级 → 文案应为 null(调用方据此跳过通知)。
        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "cli", policy);

        assertNull(EffectiveRuntimeResolver.degradedNotice(runtime));
    }

    @Test
    public void degradedNoticeNullForNullRuntime() {
        assertNull(EffectiveRuntimeResolver.degradedNotice(null));
    }

    @Test
    public void degradedNoticeDescribesFallbackRuntimeWhenDegraded() {
        // 冲突点 B:codex 请求 sdk 但策略仅支持 CLI → 降级到 CLI。
        // 提示文案应明确回退到的运行时(CLI),让用户理解降级方向。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "sdk", policy);

        String notice = EffectiveRuntimeResolver.degradedNotice(runtime);
        assertNotNull(notice);
        assertTrue(notice.contains("CLI"));
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
