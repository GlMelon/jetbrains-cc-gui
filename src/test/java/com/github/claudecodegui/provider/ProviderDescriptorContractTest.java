package com.github.claudecodegui.provider;

import com.github.claudecodegui.session.runtime.ProviderType;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * F1 / S4-1C+ ProviderDescriptor 配置驱动扩展地基的契约测试。
 * <p>
 * runtime 维度已消除(SDK 调用模式已移除)——描述符仅按 capability 断言。
 */
public class ProviderDescriptorContractTest {

    @Test
    public void builtinsReturnsAllProvidersWithExpectedCapabilities() {
        List<ProviderDescriptor> builtins = ProviderDescriptor.builtins();
        assertEquals(8, builtins.size());
        // claude/codex/opencode:全能力
        for (ProviderDescriptor d : List.of(
                ProviderDescriptor.claude(), ProviderDescriptor.codex(), ProviderDescriptor.opencode())) {
            assertEquals("expected all capabilities for builtin " + d.providerId(),
                    EnumSet.allOf(ProviderCapability.class), d.capabilities());
            assertTrue(d.providerId() + " should support CLI session",
                    d.supports(ProviderCapability.CLI_SESSION));
        }
        // grok:CLI_SESSION + STREAMING + REASONING_THINKING(thought 事件)+ HISTORY
        assertEquals("expected grok capabilities",
                EnumSet.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                        ProviderCapability.REASONING_THINKING, ProviderCapability.HISTORY),
                ProviderDescriptor.grok().capabilities());
        // kimi:CLI_SESSION + STREAMING + REASONING_THINKING(ACP 通道 agent_thought_chunk)+ HISTORY
        assertEquals("expected kimi capabilities",
                EnumSet.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                        ProviderCapability.REASONING_THINKING, ProviderCapability.HISTORY),
                ProviderDescriptor.kimi().capabilities());
        assertTrue("kimi declares thinking via ACP channel (agent_thought_chunk)",
                ProviderDescriptor.kimi().supports(ProviderCapability.REASONING_THINKING));
        // pi:CLI_SESSION + STREAMING + REASONING_THINKING + HISTORY(session JSONL 公开规范)
        assertEquals("expected pi capabilities",
                EnumSet.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                        ProviderCapability.REASONING_THINKING, ProviderCapability.HISTORY),
                ProviderDescriptor.pi().capabilities());
        // omp(pi fork channel 模式)/ dsh(host RPC):思考经 marker/reasoning-delta,历史已接线
        assertEquals("expected omp capabilities",
                EnumSet.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                        ProviderCapability.REASONING_THINKING, ProviderCapability.HISTORY),
                ProviderDescriptor.omp().capabilities());
        assertEquals("expected dsh capabilities",
                EnumSet.of(ProviderCapability.CLI_SESSION, ProviderCapability.STREAMING,
                        ProviderCapability.REASONING_THINKING, ProviderCapability.HISTORY),
                ProviderDescriptor.dsh().capabilities());
    }

    @Test
    public void builtinAlignsWithProviderTypeSsot() {
        // ProviderDescriptor 内置默认必须与 ProviderType 枚举 SSOT 一致(id / label / cliCommand)
        assertEquals(ProviderType.CLAUDE.value(), ProviderDescriptor.claude().providerId());
        assertEquals(ProviderType.CLAUDE.displayLabel(), ProviderDescriptor.claude().displayLabel());
        assertEquals(ProviderType.CODEX.cliCommand(), ProviderDescriptor.codex().cliCommand());
        assertEquals(ProviderType.OPENCODE.cliCommandWindows(), ProviderDescriptor.opencode().cliCommandWindows());
    }

    @Test
    public void supportsChecksCapability() {
        ProviderDescriptor d = ProviderDescriptor.claude();
        assertTrue(d.supports(ProviderCapability.MCP));
        assertTrue(d.supports(ProviderCapability.CLI_SESSION));
    }

    @Test
    public void descriptorRejectsBlankProviderId() {
        try {
            new ProviderDescriptor("  ", "X", "x", "x.cmd", EnumSet.noneOf(ProviderCapability.class));
            org.junit.Assert.fail("expected IllegalArgumentException for blank providerId");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void descriptorNormalizesProviderIdToLowercase() {
        ProviderDescriptor d = new ProviderDescriptor("Acme", "Acme", "acme", "acme.cmd",
                EnumSet.of(ProviderCapability.CLI_SESSION));
        assertEquals("acme", d.providerId());
    }

    @Test
    public void registryHasBuiltinProvidersByDefault() {
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry();
        assertEquals(8, registry.all().size());
        assertTrue(registry.has("claude"));
        assertTrue(registry.has("codex"));
        assertTrue(registry.has("opencode"));
        assertTrue(registry.has("grok"));
        assertTrue(registry.has("kimi"));
        assertTrue(registry.has("pi"));
        assertTrue(registry.has("omp"));
        assertTrue(registry.has("dsh"));
        assertEquals("Claude", registry.get("claude").displayLabel());
    }

    @Test
    public void registryCustomOverridesBuiltinAndAddsNew() {
        ProviderDescriptor overrideClaude = new ProviderDescriptor("CLAUDE", "Claude Override", "claude", "claude.cmd",
                EnumSet.of(ProviderCapability.STREAMING));
        ProviderDescriptor acme = new ProviderDescriptor("acme", "Acme", "acme", "acme.cmd",
                EnumSet.of(ProviderCapability.CLI_SESSION));
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry(List.of(overrideClaude, acme));

        // 覆盖:claude 被自定义覆盖(override 只声明 STREAMING,内置全能力含 MCP → 不再支持 MCP)
        assertEquals("Claude Override", registry.get("claude").displayLabel());
        assertFalse(registry.get("claude").supports(ProviderCapability.MCP));
        // 新增:acme(8 内置 + acme = 9)
        assertTrue(registry.has("acme"));
        assertEquals(9, registry.all().size());
    }

    @Test
    public void registryGetUnknownReturnsNullWithoutThrowing() {
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry();
        assertNull(registry.get("unknown-provider"));
        assertFalse(registry.has("unknown-provider"));
    }

    @Test
    public void registryWithCapabilityFiltersInOrder() {
        ProviderDescriptor acme = new ProviderDescriptor("acme", "Acme", "acme", "acme.cmd",
                EnumSet.of(ProviderCapability.CLI_SESSION));
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry(List.of(acme));

        // claude/codex/opencode 声明 MCP(全能力);cliBuiltin 与 acme 只声明 CLI_SESSION
        List<ProviderDescriptor> mcpProviders = registry.withCapability(ProviderCapability.MCP);
        assertEquals(3, mcpProviders.size());
        // 8 内置(全部声明 CLI_SESSION)+ acme = 9
        List<ProviderDescriptor> cliOnlyProviders = registry.withCapability(ProviderCapability.CLI_SESSION);
        assertEquals(9, cliOnlyProviders.size());
    }
}
