package com.github.claudecodegui.mcp;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SDK binding 是一次 SDK 轮次的 MCP Gateway 装配结果:不落配置文件,直接把
 * 生成 melon_gateway 所需的命令交给 Node 端(见 mcp-gateway-binding.js)。
 */
public class McpGatewaySdkBindingTest {
    @Test
    public void disabledFactoryIsNotUsable() {
        McpGatewaySdkBinding binding = McpGatewaySdkBinding.disabled("reason");
        assertFalse(binding.enabled());
        assertFalse(binding.ready());
        assertEquals(0L, binding.revision());
        assertTrue(binding.command().isEmpty());
        assertFalse(binding.usable());
        assertEquals("reason", binding.diagnostic());
    }

    @Test
    public void usableWhenEnabledAndReady() {
        McpGatewaySdkBinding binding = new McpGatewaySdkBinding(
                true, true, 5L, List.of("node", "client.js"), null);
        assertTrue(binding.usable());
        assertEquals(5L, binding.revision());
        assertEquals(List.of("node", "client.js"), binding.command());
    }

    @Test
    public void notUsableWhenReadyFalse() {
        McpGatewaySdkBinding binding = new McpGatewaySdkBinding(
                true, false, 5L, List.of("node"), null);
        assertFalse(binding.usable());
    }

    @Test
    public void nullCommandBecomesEmptyImmutableCopy() {
        McpGatewaySdkBinding binding = new McpGatewaySdkBinding(true, true, 1L, null, null);
        assertNotNull(binding.command());
        assertTrue(binding.command().isEmpty());
    }
}
