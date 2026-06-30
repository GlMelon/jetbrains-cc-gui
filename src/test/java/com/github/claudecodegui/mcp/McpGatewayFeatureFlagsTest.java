package com.github.claudecodegui.mcp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SDK 调用模式的特性开关独立于 CLI 开关:Gateway 总开关为前提,SDK 子开关单独控制,
 * 因此 CLI 与 SDK 可分别启用(典型场景:SDK 启用而 CLI 不启用)。
 */
public class McpGatewayFeatureFlagsTest {
    @Before
    public void clearFlags() {
        System.clearProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_CLI_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_SDK_ENABLED);
    }

    @After
    public void tearDown() {
        System.clearProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_CLI_ENABLED);
        System.clearProperty(McpGatewayConstants.FEATURE_SDK_ENABLED);
    }

    @Test
    public void sdkDisabledByDefault() {
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void sdkEnabledRequiresGatewayAndSdkFlags() {
        System.setProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED, "true");
        System.setProperty(McpGatewayConstants.FEATURE_SDK_ENABLED, "true");
        assertTrue(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void sdkDisabledWhenGatewayOnButSdkOff() {
        System.setProperty(McpGatewayConstants.FEATURE_GATEWAY_ENABLED, "true");
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }

    @Test
    public void sdkDisabledWhenSdkOnButGatewayOff() {
        System.setProperty(McpGatewayConstants.FEATURE_SDK_ENABLED, "true");
        assertFalse(McpGatewayFeatureFlags.isSdkEnabled());
    }
}
