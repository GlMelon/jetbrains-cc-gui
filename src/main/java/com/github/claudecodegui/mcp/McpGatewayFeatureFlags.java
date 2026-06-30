package com.github.claudecodegui.mcp;

/**
 * Feature gates for the MCP Gateway rollout.
 */
public final class McpGatewayFeatureFlags {
    private McpGatewayFeatureFlags() {
    }

    public static boolean isGatewayEnabled() {
        return Boolean.getBoolean(McpGatewayConstants.FEATURE_GATEWAY_ENABLED);
    }

    public static boolean isCliEnabled() {
        return isGatewayEnabled() && Boolean.getBoolean(McpGatewayConstants.FEATURE_CLI_ENABLED);
    }

    public static boolean isSdkEnabled() {
        return isGatewayEnabled() && Boolean.getBoolean(McpGatewayConstants.FEATURE_SDK_ENABLED);
    }
}
