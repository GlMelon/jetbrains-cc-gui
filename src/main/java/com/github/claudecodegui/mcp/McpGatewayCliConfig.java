package com.github.claudecodegui.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Provider-specific gateway injection payload for one CLI turn.
 *
 * <p>三种注入机制(按 provider,2026-09 起统一为 Streamable HTTP url 直连,替代 stdio 代理进程):
 * <ul>
 *   <li><b>Claude</b>:文件机制 —— {@link #configPath()} 指向 mcp-gateway.json
 *       ({@code type:"http"} + url + {@code ${MELON_MCP_GATEWAY_TOKEN}} header 引用),经 {@code --mcp-config} 加载。</li>
 *   <li><b>Codex</b>:命令行覆盖 —— {@link #overrideArgs()} 是 {@code -c key=value} 扁平列表
 *       (melon_gateway 的 url + bearer_token_env_var + enabled,再逐个禁真实 server),argv 直传。</li>
 *   <li><b>OpenCode</b>:env 内联 —— {@link #environment()} 含 {@code OPENCODE_CONFIG_CONTENT}
 *       ({@code type:"remote"} + url + {@code {env:...}} header 引用)与 token 变量。</li>
 * </ul>
 * 三者的 token 都经 {@link #environment()} 以 env 变量注入 CLI 进程,配置体只写变量引用;
 * {@link #endpoint()} 是 gateway 的 {@code http://127.0.0.1:<port>/mcp},供长驻进程指纹使用。
 * {@link #usable()} 仅看 {@code enabled && ready}(不再要求 configPath:Codex/OpenCode 无文件)。
 */
public record McpGatewayCliConfig(
        boolean enabled,
        boolean ready,
        long revision,
        Path configPath,
        String endpoint,
        Map<String, String> environment,
        List<String> overrideArgs,
        String diagnostic
) {
    public McpGatewayCliConfig {
        environment = environment != null ? Map.copyOf(environment) : Map.of();
        overrideArgs = overrideArgs != null ? List.copyOf(overrideArgs) : List.of();
    }

    public static McpGatewayCliConfig disabled(String diagnostic) {
        return new McpGatewayCliConfig(false, false, 0L, null, null, Map.of(), List.of(), diagnostic);
    }

    public boolean usable() {
        return enabled && ready;
    }
}
