package com.github.claudecodegui.provider;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * F1 / S4-1C+ ProviderDescriptor 配置驱动扩展地基的契约测试。
 */
public class ProviderDescriptorContractTest {

    @Test
    public void builtinsReturnsThreeProvidersWithFullCapabilities() {
        List<ProviderDescriptor> builtins = ProviderDescriptor.builtins();
        assertEquals(3, builtins.size());
        for (ProviderDescriptor d : builtins) {
            assertEquals("expected all 7 capabilities for builtin " + d.providerId(),
                    EnumSet.allOf(ProviderCapability.class), d.capabilities());
            assertTrue(d.supports(RuntimeType.SDK));
            assertTrue(d.supports(RuntimeType.CLI));
        }
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
    public void supportsChecksCapabilityAndRuntime() {
        ProviderDescriptor d = ProviderDescriptor.claude();
        assertTrue(d.supports(ProviderCapability.MCP));
        assertFalse(d.supports(RuntimeType.valueOf("SDK")) ? false : true); // SDK 支持
        assertTrue(d.supports(RuntimeType.SDK));
    }

    @Test
    public void descriptorRejectsBlankProviderId() {
        try {
            new ProviderDescriptor("  ", "X", "x", "x.cmd", EnumSet.noneOf(ProviderCapability.class),
                    EnumSet.of(RuntimeType.CLI));
            org.junit.Assert.fail("expected IllegalArgumentException for blank providerId");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void descriptorNormalizesProviderIdToLowercase() {
        ProviderDescriptor d = new ProviderDescriptor("Gemini", "Gemini", "gemini", "gemini.cmd",
                EnumSet.of(ProviderCapability.CLI_SESSION), EnumSet.of(RuntimeType.CLI));
        assertEquals("gemini", d.providerId());
    }

    @Test
    public void registryHasBuiltinProvidersByDefault() {
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry();
        assertEquals(3, registry.all().size());
        assertTrue(registry.has("claude"));
        assertTrue(registry.has("codex"));
        assertTrue(registry.has("opencode"));
        assertEquals("Claude", registry.get("claude").displayLabel());
    }

    @Test
    public void registryCustomOverridesBuiltinAndAddsNew() {
        ProviderDescriptor overrideClaude = new ProviderDescriptor("CLAUDE", "Claude Override", "claude", "claude.cmd",
                EnumSet.of(ProviderCapability.SDK_SESSION), EnumSet.of(RuntimeType.SDK));
        ProviderDescriptor gemini = new ProviderDescriptor("gemini", "Gemini", "gemini", "gemini.cmd",
                EnumSet.of(ProviderCapability.CLI_SESSION), EnumSet.of(RuntimeType.CLI));
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry(List.of(overrideClaude, gemini));

        // 覆盖:claude 被自定义覆盖(大小写不敏感归一化后同 id)
        assertEquals("Claude Override", registry.get("claude").displayLabel());
        assertFalse(registry.get("claude").supports(RuntimeType.CLI));
        // 新增:gemini
        assertTrue(registry.has("gemini"));
        assertEquals(4, registry.all().size());
    }

    @Test
    public void registryGetUnknownReturnsNullWithoutThrowing() {
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry();
        assertNull(registry.get("unknown-provider"));
        assertFalse(registry.has("unknown-provider"));
    }

    @Test
    public void registryWithCapabilityFiltersInOrder() {
        ProviderDescriptor gemini = new ProviderDescriptor("gemini", "Gemini", "gemini", "gemini.cmd",
                EnumSet.of(ProviderCapability.CLI_SESSION), EnumList());
        ProviderDescriptorRegistry registry = new ProviderDescriptorRegistry(List.of(gemini));

        // 三内置都声明 MCP,gemini 只声明 CLI_SESSION
        List<ProviderDescriptor> mcpProviders = registry.withCapability(ProviderCapability.MCP);
        assertEquals(3, mcpProviders.size());
        List<ProviderDescriptor> cliOnlyProviders = registry.withCapability(ProviderCapability.CLI_SESSION);
        assertEquals(4, cliOnlyProviders.size());
    }

    private static EnumSet<RuntimeType> EnumList() {
        return EnumSet.of(RuntimeType.CLI);
    }
}
