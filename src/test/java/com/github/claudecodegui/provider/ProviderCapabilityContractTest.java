package com.github.claudecodegui.provider;

import com.github.claudecodegui.provider.claude.ClaudeProviderAdapter;
import com.github.claudecodegui.provider.codex.CodexProviderAdapter;
import com.github.claudecodegui.provider.opencode.OpenCodeProviderAdapter;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * F1 capability descriptor 契约测试:验证 {@link ProviderCapability} 枚举完整性、
 * 三 Provider 能力声明对称性,以及 {@link ProviderRegistry} 的能力查询行为。
 */
public class ProviderCapabilityContractTest {

    private static final Set<ProviderCapability> ALL_CAPABILITIES = EnumSet.allOf(ProviderCapability.class);

    @Test
    public void enumDeclaresSixCapabilities() {
        assertEquals(6, ProviderCapability.values().length);
    }

    @Test
    public void threeProvidersDeclareAllCapabilities() {
        // 三 Provider 均为完整接入(SDK + CLI + 历史 + Skills + MCP),ProviderCapability 层对称声明全能力。
        // 真实运行时差异(如 OpenCode CLI 思考受 provider 限制)是模式级细节,由各域已修复逻辑处理,
        // 不进入 Provider 级粗粒度声明。
        assertEquals(ALL_CAPABILITIES, new ClaudeProviderAdapter().capabilities());
        assertEquals(ALL_CAPABILITIES, new CodexProviderAdapter().capabilities());
        assertEquals(ALL_CAPABILITIES, new OpenCodeProviderAdapter().capabilities());
    }

    @Test
    public void adapterSupportsDelegatesToCapabilities() {
        ProviderAdapter claude = new ClaudeProviderAdapter();
        assertTrue(claude.supports(ProviderCapability.MCP));
        assertTrue(claude.supports(ProviderCapability.CLI_SESSION));
    }

    @Test
    public void registryHasCapabilityTrueForDeclared() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new ClaudeProviderAdapter()));
        assertTrue(registry.hasCapability(ProviderId.CLAUDE, ProviderCapability.STREAMING));
    }

    @Test
    public void registryHasCapabilityFalseForUndeclared() {
        // BareFake 不覆盖 capabilities() → default 空集 → 任何能力均 false
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new BareFakeProviderAdapter(ProviderId.CODEX)));
        assertFalse(registry.hasCapability(ProviderId.CODEX, ProviderCapability.MCP));
    }

    @Test
    public void registryHasCapabilityFalseForUnknownProviderWithoutThrowing() {
        ProviderRegistry registry = new ProviderRegistry(List.of(new ClaudeProviderAdapter()));
        // 未知 provider 能力探测不抛异常,返回 false 以支持优雅降级
        assertFalse(registry.hasCapability(ProviderId.of("unknown"), ProviderCapability.MCP));
    }

    @Test(expected = IllegalArgumentException.class)
    public void registryCapabilitiesThrowsForUnknownProvider() {
        ProviderRegistry registry = new ProviderRegistry(List.of());
        registry.capabilities(ProviderId.of("unknown"));
    }

    @Test
    public void registryCapabilitiesReturnsDeclaredSetForRegisteredProvider() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new DeclaringFakeProviderAdapter(ProviderId.CLAUDE,
                        ProviderCapability.STREAMING, ProviderCapability.CLI_SESSION)));
        assertEquals(
                EnumSet.of(ProviderCapability.STREAMING, ProviderCapability.CLI_SESSION),
                registry.capabilities(ProviderId.CLAUDE));
    }

    @Test
    public void registryProvidersWithCapabilityReturnsRegistrationOrder() {
        ProviderRegistry registry = new ProviderRegistry(List.of(
                new DeclaringFakeProviderAdapter(ProviderId.CLAUDE, ProviderCapability.MCP),
                new DeclaringFakeProviderAdapter(ProviderId.CODEX),               // 不声明 MCP
                new DeclaringFakeProviderAdapter(ProviderId.OPENCODE, ProviderCapability.MCP)
        ));
        assertEquals(List.of(ProviderId.CLAUDE, ProviderId.OPENCODE),
                registry.providersWithCapability(ProviderCapability.MCP));
    }

    /** 不覆盖 capabilities() 的 fake,用于验证 default 空集行为。 */
    private static final class BareFakeProviderAdapter implements ProviderAdapter {
        private final ProviderId providerId;

        private BareFakeProviderAdapter(ProviderId providerId) {
            this.providerId = providerId;
        }

        @Override
        public ProviderId providerId() {
            return providerId;
        }

        @Override
        public ProviderViewModel viewModel() {
            return new ProviderViewModel(providerId, providerId.value());
        }
    }

    /** 覆盖 capabilities() 的 fake,用于构造子集场景。 */
    private static final class DeclaringFakeProviderAdapter implements ProviderAdapter {
        private final ProviderId providerId;
        private final Set<ProviderCapability> capabilities;

        private DeclaringFakeProviderAdapter(ProviderId providerId, ProviderCapability... capabilities) {
            this.providerId = providerId;
            this.capabilities = Set.of(capabilities);
        }

        @Override
        public ProviderId providerId() {
            return providerId;
        }

        @Override
        public ProviderViewModel viewModel() {
            return new ProviderViewModel(providerId, providerId.value());
        }

        @Override
        public Set<ProviderCapability> capabilities() {
            return capabilities;
        }
    }
}
