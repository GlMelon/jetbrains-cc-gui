package com.github.claudecodegui.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Provider-specific gateway injection payload for one CLI/SDK turn.
 *
 * <p>三种注入机制(按 provider):
 * <ul>
 *   <li><b>Claude</b>:文件机制 —— {@link #configPath()} 指向 mcp-gateway.json,经 {@code --mcp-config} 加载。</li>
 *   <li><b>Codex</b>:命令行覆盖 —— {@link #overrideArgs()} 是 {@code -c key=value} 扁平列表
 *       (mel­on_gateway 定义 + 逐个禁真实 server),spawn 原生 codex.exe 时 argv 直传(不经 cmd 转义)。</li>
 *   <li><b>OpenCode</b>:env 内联 —— {@link #environment()} 含 {@code OPENCODE_CONFIG_CONTENT}
 *       (JSON 合并语义;HOME/XDG 保持真实,零临时 home)。</li>
 * </ul>
 * 三者都不再造临时 home / 复制配置文件。{@link #usable()} 仅看 {@code enabled && ready}
 * (不再要求 configPath:Codex/OpenCode 无文件)。
 */
public record McpGatewayCliConfig(
        boolean enabled,
        boolean ready,
        long revision,
        Path configPath,
        Path stateFile,
        List<String> command,
        Map<String, String> environment,
        List<String> overrideArgs,
        String diagnostic
) {
    public McpGatewayCliConfig {
        command = command != null ? List.copyOf(command) : List.of();
        environment = environment != null ? Map.copyOf(environment) : Map.of();
        overrideArgs = overrideArgs != null ? List.copyOf(overrideArgs) : List.of();
    }

    public static McpGatewayCliConfig disabled(String diagnostic) {
        return new McpGatewayCliConfig(false, false, 0L, null, null, List.of(), Map.of(), List.of(), diagnostic);
    }

    public boolean usable() {
        return enabled && ready;
    }
}
