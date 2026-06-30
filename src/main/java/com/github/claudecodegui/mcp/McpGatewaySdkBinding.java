package com.github.claudecodegui.mcp;

import java.util.List;

/**
 * Provider-specific SDK binding generated for one SDK turn.
 * <p>
 * Unlike {@link McpGatewayCliConfig}, no temporary config file is written: the
 * command (a {@code node gateway-stdio-client.js} invocation with state-file
 * and revision args) is handed to the Node side (mcp-gateway-binding.js) so the
 * SDK spawns the single aggregated {@code melon_gateway} server directly.
 */
public record McpGatewaySdkBinding(
        boolean enabled,
        boolean ready,
        long revision,
        List<String> command,
        String diagnostic
) {
    public McpGatewaySdkBinding {
        command = command != null ? List.copyOf(command) : List.of();
    }

    public static McpGatewaySdkBinding disabled(String diagnostic) {
        return new McpGatewaySdkBinding(false, false, 0L, List.of(), diagnostic);
    }

    public boolean usable() {
        return enabled && ready;
    }
}
