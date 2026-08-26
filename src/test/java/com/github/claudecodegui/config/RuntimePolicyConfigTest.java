package com.github.claudecodegui.config;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.util.LinkedHashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 {@link RuntimePolicyConfig#mergeWithDefaults()}:
 * <p>
 * 修复存量用户旧 config.json 在「OpenCode 加入路由策略」之前持久化、
 * 缺失 opencode provider 策略 → {@code of(OPENCODE)=null} →
 * 路由层抛 "Provider disabled/unknown: opencode"
 * 的向后兼容 bug(2026-06-28 复现)。
 * <p>
 * SDK 调用模式已移除,runtime 维度已消除——策略仅剩 enabled 一维。
 */
public class RuntimePolicyConfigTest {

    /** 模拟存量用户旧 config.json:runtime 只含 claude/codex,缺 opencode。 */
    private static RuntimePolicyConfig legacyConfig() {
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE, new ProviderRuntimePolicy(true));
        providers.put(ProviderType.CODEX, new ProviderRuntimePolicy(true));
        return new RuntimePolicyConfig(providers);
    }

    @Test
    public void mergeWithDefaults_补全缺失的新provider策略() {
        // 旧 config 缺 opencode → merge 后应补默认策略,使 of(OPENCODE) 不再返回 null
        RuntimePolicyConfig merged = legacyConfig().mergeWithDefaults();

        ProviderRuntimePolicy opencode = merged.of(ProviderType.OPENCODE);
        assertNotNull("opencode 策略应被 merge 补全(旧 config 缺失)", opencode);
        assertTrue("opencode 应 enabled", opencode.enabled());
    }

    @Test
    public void mergeWithDefaults_保留用户对已知provider的自定义() {
        // 用户 claude/codex 条目应被保留(merge 优先用户配置),不被默认覆盖
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE, new ProviderRuntimePolicy(true));
        providers.put(ProviderType.CODEX, new ProviderRuntimePolicy(false));
        RuntimePolicyConfig merged = new RuntimePolicyConfig(providers).mergeWithDefaults();

        assertTrue("claude 保留用户策略 enabled", merged.of(ProviderType.CLAUDE).enabled());
        assertFalse("codex 保留用户策略 enabled=false", merged.of(ProviderType.CODEX).enabled());
    }

    @Test
    public void mergeWithDefaults_保留用户显式禁用的provider() {
        // 用户显式禁用 codex(enabled=false),merge 不应把它当「缺失」而用默认重新启用
        var providers = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        providers.put(ProviderType.CLAUDE, new ProviderRuntimePolicy(true));
        providers.put(ProviderType.CODEX, new ProviderRuntimePolicy(false));
        RuntimePolicyConfig merged = new RuntimePolicyConfig(providers).mergeWithDefaults();

        assertFalse("codex 用户显式禁用应被保留(不被默认重新启用)", merged.of(ProviderType.CODEX).enabled());
        assertNotNull("opencode 缺失仍应被补全", merged.of(ProviderType.OPENCODE));
    }
}
