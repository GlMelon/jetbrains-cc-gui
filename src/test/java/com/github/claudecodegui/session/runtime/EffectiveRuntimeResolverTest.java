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
    public void explicitCodexSdkRequestHonoredEvenWhenUnsupported() {
        // CLI/SDK 相互独立:用户经「调用模式」显式请求 sdk,即使「路由策略」codex 仅支持 CLI,
        // 也尊重用户选择用 SDK,不再降级到 default=CLI(2026-06-29:消除显式选择被降级)。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "sdk", policy);

        assertEquals(RuntimeType.SDK, runtime.runtimeType());
        assertFalse(runtime.degraded());
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
    public void explicitCodexCliRequestHonoredEvenWhenUnsupported() {
        // 对称覆盖 CLI 方向:用户经「调用模式」显式请求 cli,即使「路由策略」codex 仅支持 SDK,
        // 也尊重用户选择用 CLI,不降级(CLI/SDK 相互独立)。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", null, "cli", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertFalse(runtime.degraded());
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
    public void explicitClaudeCliSnapshotHonoredEvenWhenUnsupported() {
        // 用户在「调用模式」选 CLI(会话快照 cli),即使「路由策略」claude 仅支持 SDK,
        // 也尊重用户选择用 CLI,不再降级到 SDK(CLI/SDK 相互独立,2026-06-29)。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "claude", "cli", null, policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertFalse(runtime.degraded());
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
        // 显式选择不再降级后,resolve 产出的 degraded=true 仅剩 Claude 无显式选择走 settings、
        // 且 settings 模式不在 supported 内的防御性路径(Platform 依赖难单测)。degradedNotice 是纯函数,
        // 直接构造 degraded Runtime 验证文案功能(回退运行时描述)。
        EffectiveRuntimeResolver.Runtime runtime = new EffectiveRuntimeResolver.Runtime(
                ProviderType.CODEX, RuntimeType.CLI, true);

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

    // ===== 纯快照语义:Codex/OpenCode 会话快照对称(本次修复核心)=====

    @Test
    public void codexRespectsSessionSnapshotCli() {
        // 此前 Codex 跳过 sessionMode 只看 requestedMode 是不对称 bug 根因(de79aaf1 默认 SDK → daemon 串行)。
        // 修复后 Codex 会话快照 cli → CLI,与 Claude 对称。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", "cli", null, policy);

        assertEquals(ProviderType.CODEX, runtime.provider());
        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    @Test
    public void codexRespectsSessionSnapshotSdk() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", "sdk", null, policy);

        assertEquals(RuntimeType.SDK, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    @Test
    public void codexSessionSnapshotOverridesRequestedMode() {
        // 纯快照语义:会话快照 cli 锁死,即使请求级传 sdk 仍用快照 cli(切换只影响新会话,不污染当前会话)。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", "cli", "sdk", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
    }

    @Test
    public void opencodeRespectsSessionSnapshotCli() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "opencode", "cli", null, policy);

        assertEquals(ProviderType.OPENCODE, runtime.provider());
        assertEquals(RuntimeType.CLI, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    @Test
    public void opencodeRespectsSessionSnapshotSdk() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "opencode", "sdk", null, policy);

        assertEquals(RuntimeType.SDK, runtime.runtimeType());
    }

    @Test
    public void opencodeSessionSnapshotOverridesRequestedMode() {
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "opencode", "cli", "sdk", policy);

        assertEquals(RuntimeType.CLI, runtime.runtimeType());
    }

    @Test
    public void explicitCodexSdkSnapshotHonoredEvenWhenUnsupported() {
        // 会话快照 sdk 为显式选择,即使「路由策略」codex 仅支持 CLI,也尊重用 SDK,不降级。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "codex", "sdk", null, policy);

        assertEquals(RuntimeType.SDK, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    @Test
    public void explicitOpencodeSdkSnapshotHonoredEvenWhenUnsupported() {
        // 会话快照 sdk 为显式选择,即使「路由策略」opencode 仅支持 CLI,也尊重用 SDK,不降级。
        RuntimePolicyConfig policy = policy(
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK),
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI)
        );

        EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                "opencode", "sdk", null, policy);

        assertEquals(RuntimeType.SDK, runtime.runtimeType());
        assertFalse(runtime.degraded());
    }

    private static RuntimePolicyConfig policy(ProviderRuntimePolicy claude, ProviderRuntimePolicy codex) {
        // 两参数重载:opencode 默认同 codex(向后兼容现有 codex 用例,opencode 显式用例走三参数重载)。
        return policy(claude, codex, codex);
    }

    private static RuntimePolicyConfig policy(ProviderRuntimePolicy claude, ProviderRuntimePolicy codex, ProviderRuntimePolicy opencode) {
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE, claude);
        providers.put(ProviderType.CODEX, codex);
        providers.put(ProviderType.OPENCODE, opencode);
        return new RuntimePolicyConfig(providers);
    }
}
