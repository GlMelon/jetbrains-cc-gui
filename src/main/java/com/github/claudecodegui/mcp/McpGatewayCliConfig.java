package com.github.claudecodegui.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Provider-specific temporary config generated for one CLI turn.
 */
public record McpGatewayCliConfig(
        boolean enabled,
        boolean ready,
        long revision,
        Path configPath,
        Path stateFile,
        List<String> command,
        Map<String, String> environment,
        String diagnostic
) {
    public McpGatewayCliConfig {
        command = command != null ? List.copyOf(command) : List.of();
        environment = environment != null ? Map.copyOf(environment) : Map.of();
    }

    public static McpGatewayCliConfig disabled(String diagnostic) {
        return new McpGatewayCliConfig(false, false, 0L, null, null, List.of(), Map.of(), diagnostic);
    }

    public boolean usable() {
        return enabled && ready && configPath != null;
    }
}
