package com.github.claudecodegui.config;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 {@link RuntimePolicyConfig#mergeWithDefaults()}:
 * <p>
 * 修复存量用户旧 config.json 在「OpenCode 加入路由策略」之前持久化、
 * 缺失 opencode provider 策略 → {@code of(OPENCODE)=null} →
 * {@code EffectiveRuntimeResolver.resolve} 抛 "Provider disabled/unknown: opencode"
 * → opencode 请求静默失败的向后兼容 bug(2026-06-28 复现)。
 * <p>
 * SDK 调用模式已移除,所有 provider 仅 CLI;测试用 CLI 断言。
 */
public class RuntimePolicyConfigTest {

    /** 模拟存量用户旧 config.json:runtime 只含 claude/codex,缺 opencode。 */
    private static RuntimePolicyConfig legacyConfig() {
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE,
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI));
        providers.put(ProviderType.CODEX,
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI));
        return new RuntimePolicyConfig(providers);
    }

    @Test
    public void mergeWithDefaults_补全缺失的新provider策略() {
        // 旧 config 缺 opencode → merge 后应补默认策略,使 of(OPENCODE) 不再返回 null
        RuntimePolicyConfig merged = legacyConfig().mergeWithDefaults();

        ProviderRuntimePolicy opencode = merged.of(ProviderType.OPENCODE);
        assertNotNull("opencode 策略应被 merge 补全(旧 config 缺失)", opencode);
        assertTrue("opencode 应 enabled", opencode.enabled());
        assertEquals("opencode 应仅支持 CLI", Set.of(RuntimeType.CLI), opencode.supported());
        assertEquals("opencode 默认 CLI", RuntimeType.CLI, opencode.defaultRuntime());
    }

    @Test
    public void mergeWithDefaults_保留用户对已知provider的自定义() {
        // 用户 claude/codex 条目应被保留(merge 优先用户配置),不被默认覆盖
        RuntimePolicyConfig merged = legacyConfig().mergeWithDefaults();

        assertEquals("claude 保留用户策略 default=CLI", RuntimeType.CLI,
                merged.of(ProviderType.CLAUDE).defaultRuntime());
        assertEquals("codex 保留用户策略 default=CLI", RuntimeType.CLI,
                merged.of(ProviderType.CODEX).defaultRuntime());
    }

    @Test
    public void mergeWithDefaults_保留用户显式禁用的provider() {
        // 用户显式禁用 codex(enabled=false),merge 不应把它当「缺失」而用默认重新启用
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE,
                new ProviderRuntimePolicy(true, Set.of(RuntimeType.CLI), RuntimeType.CLI));
        providers.put(ProviderType.CODEX,
                new ProviderRuntimePolicy(false, Set.of(RuntimeType.CLI), RuntimeType.CLI));
        RuntimePolicyConfig merged = new RuntimePolicyConfig(providers).mergeWithDefaults();

        assertFalse("codex 用户显式禁用应被保留(不被默认重新启用)", merged.of(ProviderType.CODEX).enabled());
        assertNotNull("opencode 缺失仍应被补全", merged.of(ProviderType.OPENCODE));
    }
}
