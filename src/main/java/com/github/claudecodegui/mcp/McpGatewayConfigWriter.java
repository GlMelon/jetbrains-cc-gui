package com.github.claudecodegui.mcp;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 四 provider 的 gateway 注入载荷生成(2026-09 改造:Streamable HTTP url 直连,替代 stdio 代理进程)。
 *
 * <ul>
 *   <li><b>Claude</b>:写额外 {@code mcp-gateway.json}({@code type:"http"} + url + headers
 *       {@code Authorization: Bearer ${MELON_MCP_GATEWAY_TOKEN}} 变量引用),命令行 {@code --mcp-config} 加载。</li>
 *   <li><b>Codex</b>:{@code -c key=value} 命令行覆盖(扁平列表):{@code url} +
 *       {@code bearer_token_env_var='MELON_MCP_GATEWAY_TOKEN'} + {@code enabled=true};
 *       CODEX_HOME 保持真实 {@code ~/.codex} → 零临时 home。</li>
 *   <li><b>OpenCode</b>:{@code OPENCODE_CONFIG_CONTENT} env 内联 JSON({@code type:"remote"} + url +
 *       headers {@code {env:MELON_MCP_GATEWAY_TOKEN}} 引用),运行时与真实 opencode.json 合并。</li>
 *   <li><b>Kimi</b>:无文件产出;ACP {@code session/new} 的 {@code mcpServers} 参数动态注入
 *       {@code {type:"http", url, headers}} 条目,由 {@code KimiAcpCliSession.buildMcpServers} 组装。</li>
 * </ul>
 *
 * <p>token 一律经 CLI 进程 env 注入({@link McpGatewayConstants#ENV_GATEWAY_TOKEN}),配置体只写
 * 各 CLI 的变量引用语法,明文不进 argv / 配置文件 / 进程列表(Kimi 例外:ACP 协议头值只接受
 * 字面值,token 经 JSON-RPC stdin 消息传递,不落盘、不进 argv)。
 *
 * <p>Codex/OpenCode 共同模式:注入 melon_gateway 聚合入口 + 逐个禁用真实 mcp server
 * (合并语义:不禁则真实 server 仍被加载直连=慢,gateway 失去意义)。
 */
public class McpGatewayConfigWriter {
    private static final Logger LOG = Logger.getInstance(McpGatewayConfigWriter.class);
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();

    /** Claude `${VAR}` 环境变量展开语法(claude mcp json headers 官方支持)。 */
    private static final String CLAUDE_TOKEN_REF = "${" + McpGatewayConstants.ENV_GATEWAY_TOKEN + "}";
    /** OpenCode `{env:VAR}` 环境变量引用语法(与 claude 的 ${VAR} 不同,opencode 官方契约)。 */
    private static final String OPENCODE_TOKEN_REF = "{env:" + McpGatewayConstants.ENV_GATEWAY_TOKEN + "}";
    /** OpenCode remote MCP 的请求超时(ms):默认 5000ms 对慢工具调用太短,对齐原 stdio 代理的 60s 预算。 */
    private static final int OPENCODE_REMOTE_TIMEOUT_MS = 60_000;

    private final Path baseDir;

    public McpGatewayConfigWriter(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 该 provider 是否有 gateway 注入机制(claude/codex/opencode/kimi 四种)。
     * 调用方(McpGatewayService.buildCliConfig)须在 ensureStarted/refreshConfig <b>之前</b>
     * 查询:不支持时直接短路,避免为注定 disabled 的 provider 白付 gateway 冷启动/全局锁成本
     * (2026-08-29 审计)。
     */
    public boolean supports(ProviderType provider) {
        return provider == ProviderType.CLAUDE
                || provider == ProviderType.CODEX
                || provider == ProviderType.OPENCODE
                || provider == ProviderType.KIMI;
    }

    /**
     * @param endpoint      gateway 的 Streamable HTTP 端点({@code http://127.0.0.1:<port>/mcp})
     * @param token         gateway bearer token;经 environment() 注入,配置体写变量引用
     *                      (Kimi 例外:ACP 头值只接受字面值,由会话层组装进 stdin JSON-RPC)
     * @param realServerIds 真实 mcp server 名(来自 collector),Codex/OpenCode 逐个禁用
     */
    public McpGatewayCliConfig write(ProviderType provider, String tabId, long revision,
                                     String endpoint, String token,
                                     List<String> realServerIds) throws IOException {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Gateway endpoint required");
        }
        Files.createDirectories(baseDir);
        return switch (provider) {
            case CLAUDE -> {
                Path providerDir = baseDir.resolve(provider.value()).resolve(safeFilePart(tabId));
                Files.createDirectories(providerDir);
                yield writeClaude(providerDir, revision, endpoint, token);
            }
            // Codex/OpenCode 无文件产出(configPath=null),不需要 providerDir。
            case CODEX -> writeCodex(revision, endpoint, token, realServerIds);
            case OPENCODE -> writeOpenCode(revision, endpoint, token, realServerIds);
            // Kimi ACP 通道:session/new 的 mcpServers 参数传 http 条目动态注入
            // (2026-09-03 实测 kimi 0.38.0:initialize 声明 mcpCapabilities.http=true,
            // session/new 传 {type:"http",url,headers} 可发现并成功调用工具;
            // 此前"不生效"定论只覆盖 stdio 形态)。无文件产出,token 随 environment()
            // 注入进程 env,由 KimiAcpCliSession.buildMcpServers 组装进 ACP 消息。
            case KIMI -> new McpGatewayCliConfig(true, true, revision, null, endpoint,
                    tokenEnvironment(token), List.of(), null);
            // Grok/Pi 等其余纯 CLI provider 无 MCP gateway 注入机制,返回 disabled:不注入、不产文件。
            default -> McpGatewayCliConfig.disabled(
                    "MCP gateway injection not configured for provider: " + provider.value());
        };
    }

    private McpGatewayCliConfig writeClaude(Path providerDir, long revision,
                                            String endpoint, String token) throws IOException {
        Path configPath = providerDir.resolve("mcp-gateway.json");
        JsonObject root = new JsonObject();
        JsonObject servers = new JsonObject();
        servers.add(McpGatewayConstants.GATEWAY_SERVER_ID, claudeGatewayServerJson(endpoint));
        root.add(CliConstants.MCP_SERVERS_KEY, servers);
        writeJson(configPath, root);
        return new McpGatewayCliConfig(true, true, revision, configPath, endpoint,
                tokenEnvironment(token), List.of(), null);
    }

    /**
     * Claude 远程 MCP 条目:{@code type:"http"} 在 url 存在时必填(否则被当 stdio 报错跳过);
     * header 用 ${VAR} 变量引用,token 明文不落配置文件。
     */
    private static JsonObject claudeGatewayServerJson(String endpoint) {
        JsonObject obj = new JsonObject();
        obj.addProperty(McpGatewayConstants.KEY_TYPE, McpGatewayConstants.TRANSPORT_HTTP);
        obj.addProperty(McpGatewayConstants.KEY_URL, endpoint);
        JsonObject headers = new JsonObject();
        headers.addProperty(McpGatewayConstants.HEADER_AUTHORIZATION, "Bearer " + CLAUDE_TOKEN_REF);
        obj.add(McpGatewayConstants.KEY_HEADERS, headers);
        return obj;
    }

    /**
     * Codex:生成 {@code -c} 覆盖列表(url + bearer_token_env_var + enabled),token 经 env 注入。
     * configPath=null(无文件)。spawn 时由 CodexCliSession 把 overrideArgs 注入 exec/resume 两路径、
     * environment 并入进程 env。
     */
    private McpGatewayCliConfig writeCodex(long revision, String endpoint, String token,
                                           List<String> realServerIds) {
        List<String> overrideArgs = buildCodexOverrideArgs(endpoint, realServerIds);
        LOG.debug("[McpGateway] Codex override args: " + overrideArgs.size() + " entries, CODEX_HOME unchanged");
        return new McpGatewayCliConfig(true, true, revision, null, endpoint,
                tokenEnvironment(token), overrideArgs, null);
    }

    /**
     * OpenCode:生成 {@code OPENCODE_CONFIG_CONTENT} inline JSON(运行时与真实 opencode.json 合并),
     * token 变量一并入 environment。configPath=null(无文件)。
     */
    private McpGatewayCliConfig writeOpenCode(long revision, String endpoint, String token,
                                              List<String> realServerIds) {
        String configContent = buildOpenCodeConfigContent(endpoint, realServerIds);
        Map<String, String> env = Map.of(
                CliConstants.ENV_OPENCODE_CONFIG_CONTENT, configContent,
                McpGatewayConstants.ENV_GATEWAY_TOKEN, token);
        return new McpGatewayCliConfig(true, true, revision, null, endpoint, env, List.of(), null);
    }

    private static Map<String, String> tokenEnvironment(String token) {
        return Map.of(McpGatewayConstants.ENV_GATEWAY_TOKEN, token == null ? "" : token);
    }

    private static void writeJson(Path path, JsonObject root) throws IOException {
        Files.writeString(path, PRETTY_GSON.toJson(root), StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 免临时 home 纯函数(包级 static,供单测):Codex -c 覆盖 / OpenCode inline JSON
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * 生成 Codex {@code -c} 命令行覆盖扁平列表。注入 melon_gateway 聚合入口(url 形态)+ 逐个禁用真实 server。
     * 经原生 codex.exe argv 直传(不经 cmd 转义),元素由 {@link #tomlString} 编码为 TOML literal
     * 字符串(单引号)——这是 codex {@code -c} 唯一能可靠承载反斜杠路径的形式(见 tomlString 文档)。
     *
     * @param endpoint      gateway Streamable HTTP 端点 url
     * @param realServerIds 真实 mcp server 名(来自 collector);自动过滤 melon_gateway 自身
     * @return 扁平列表,每 2 元素一对("-c", value)
     */
    static List<String> buildCodexOverrideArgs(String endpoint, List<String> realServerIds) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Gateway endpoint required");
        }
        List<String> args = new ArrayList<>();
        String prefix = "mcp_servers." + McpGatewayConstants.GATEWAY_SERVER_ID + ".";
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + McpGatewayConstants.KEY_URL + "=" + tomlString(endpoint));
        // token 不落 argv:bearer_token_env_var 只引用 env 变量名,值由 spawn env 注入。
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + "bearer_token_env_var=" + tomlString(McpGatewayConstants.ENV_GATEWAY_TOKEN));
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + McpGatewayConstants.KEY_ENABLED + "=true");
        // 合并语义:不禁真实 server 则仍被加载直连=慢;逐个 enabled=false 关停。
        for (String id : realServerIds == null ? List.<String>of() : realServerIds) {
            if (id == null || id.isBlank() || id.equals(McpGatewayConstants.GATEWAY_SERVER_ID)) {
                continue;
            }
            args.add(CliConstants.CODEX_ARG_C_CONFIG);
            args.add("mcp_servers." + id + ".enabled=false");
        }
        return args;
    }

    /**
     * 生成 OpenCode {@code OPENCODE_CONFIG_CONTENT} inline JSON(运行时与真实 opencode.json 合并)。
     * 注入 melon_gateway 聚合入口(remote 形态)+ 逐个禁用真实 server;HOME/XDG 保持真实。
     *
     * @param endpoint      gateway Streamable HTTP 端点 url
     * @param realServerIds 真实 mcp server 名(来自 collector);自动过滤 melon_gateway 自身
     * @return compact JSON 字符串,root 仅含 {@code mcp} 键
     */
    static String buildOpenCodeConfigContent(String endpoint, List<String> realServerIds) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Gateway endpoint required");
        }
        JsonObject root = new JsonObject();
        JsonObject mcp = new JsonObject();
        JsonObject gateway = new JsonObject();
        gateway.addProperty(McpGatewayConstants.KEY_TYPE, McpGatewayConstants.OPENCODE_MCP_TYPE_REMOTE);
        gateway.addProperty(McpGatewayConstants.KEY_URL, endpoint);
        gateway.addProperty(McpGatewayConstants.KEY_ENABLED, true);
        gateway.addProperty(McpGatewayConstants.KEY_TIMEOUT, OPENCODE_REMOTE_TIMEOUT_MS);
        JsonObject headers = new JsonObject();
        headers.addProperty(McpGatewayConstants.HEADER_AUTHORIZATION, "Bearer " + OPENCODE_TOKEN_REF);
        gateway.add(McpGatewayConstants.KEY_HEADERS, headers);
        mcp.add(McpGatewayConstants.GATEWAY_SERVER_ID, gateway);
        for (String id : realServerIds == null ? List.<String>of() : realServerIds) {
            if (id == null || id.isBlank() || id.equals(McpGatewayConstants.GATEWAY_SERVER_ID)) {
                continue;
            }
            JsonObject disabled = new JsonObject();
            disabled.addProperty(McpGatewayConstants.KEY_ENABLED, false);
            mcp.add(id, disabled);
        }
        root.add(McpGatewayConstants.KEY_MCP_OPENCODE, mcp);
        return COMPACT_GSON.toJson(root);
    }

    // ════════════════════════════════════════════════════════════════════════════

    private static String safeFilePart(String value) {
        String raw = value == null || value.isBlank() ? CommonConstants.DEFAULT_TAB_ID : value.trim();
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }

    /**
     * 把单个字符串编码为 TOML 值,优先用 literal 字符串(单引号,不处理转义)。
     *
     * <p><b>必须用 literal 字符串(codex {@code -c} 路径):</b>codex 的 {@code -c key=value}
     * 在 TOML 解析前会对 value 做一次 {@code \\→\} 反转义。若用基本字符串 {@code "D:\\project"},
     * 这里把 {@code \} 加倍成 {@code \\} 会被 codex 还原成 {@code \},致 {@code "D:\project"} 出现
     * 非法 TOML 转义 {@code \p} → TOML 解析失败 → codex 退回把整个值当字符串 → 类型错误。
     * literal 字符串不含 {@code \\} 序列,codex 的预反转义是空操作,TOML 正确解析。
     *
     * <p>literal 字符串不能含单引号/换行;含则回退基本字符串(双引号 + 标准转义)。
     * gateway 的 url / env 变量名实践中不含,回退仅为防御性完整覆盖。
     */
    private static String tomlString(String value) {
        String safe = value == null ? "" : value;
        if (!safe.contains("'") && !safe.contains("\n") && !safe.contains("\r")) {
            return "'" + safe + "'";
        }
        return "\"" + safe
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
