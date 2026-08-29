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
import java.util.stream.Collectors;

/**
 * 三 provider 的 gateway 注入载荷生成(2026-07-02 重构:统一免临时 home)。
 *
 * <ul>
 *   <li><b>Claude</b>:写额外 {@code mcp-gateway.json},命令行 {@code --mcp-config} 加载(env 不碰 {@code ~/.claude})。</li>
 *   <li><b>Codex</b>:{@code -c key=value} 命令行覆盖(扁平列表),spawn 原生 codex.exe argv 直传;
 *       CODEX_HOME 保持真实 {@code ~/.codex} → 零临时 home、零文件复制。</li>
 *   <li><b>OpenCode</b>:{@code OPENCODE_CONFIG_CONTENT} env 内联 JSON(运行时与真实 opencode.json 合并);
 *       HOME/XDG 保持真实 → 零临时 home。</li>
 * </ul>
 *
 * <p>Codex/OpenCode 共同模式:注入 melon_gateway 聚合入口 + 逐个禁用真实 mcp server
 * (合并语义:不禁则真实 server 仍被加载直连=慢,gateway 失去意义)。
 */
public class McpGatewayConfigWriter {
    private static final Logger LOG = Logger.getInstance(McpGatewayConfigWriter.class);
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();

    private final Path baseDir;

    public McpGatewayConfigWriter(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 该 provider 是否有 gateway 注入机制(claude/codex/opencode 三种)。
     * 调用方(McpGatewayService.buildCliConfig)须在 ensureStarted/refreshConfig <b>之前</b>
     * 查询:不支持时直接短路,避免为注定 disabled 的 provider 白付 gateway 冷启动/全局锁成本
     * (2026-08-29 审计:kimi 已接线 gatewayService 但恒 disabled,反付启动成本)。
     */
    public boolean supports(ProviderType provider) {
        return provider == ProviderType.CLAUDE
                || provider == ProviderType.CODEX
                || provider == ProviderType.OPENCODE;
    }

    public McpGatewayCliConfig write(ProviderType provider, String tabId, long revision,
                                     Path stateFile, List<String> gatewayCommand,
                                     List<String> realServerIds) throws IOException {
        Files.createDirectories(baseDir);
        List<String> command = withGatewayArgs(gatewayCommand, stateFile, revision);
        return switch (provider) {
            case CLAUDE -> {
                Path providerDir = baseDir.resolve(provider.value()).resolve(safeFilePart(tabId));
                Files.createDirectories(providerDir);
                yield writeClaude(providerDir, revision, stateFile, command);
            }
            // Codex/OpenCode 无文件产出(configPath=null),不需要 providerDir。
            case CODEX -> writeCodex(revision, stateFile, command, realServerIds);
            case OPENCODE -> writeOpenCode(revision, stateFile, command, realServerIds);
            // Grok/Kimi/Pi 等纯 CLI provider 无 MCP gateway 注入机制(三机制均 claude/codex/opencode 专属),
            // 返回 disabled:不注入 gateway、不产文件。
            // Kimi ACP 通道(session/new mcpServers)已验证 0.38 v1/v2 引擎均不生效(v2 源码注释
            // "v2 引擎无 caller mcpServers 通道";KIMI_CODE_LEGACY_FLAG=1 回退 v1 亦 -32602 拒绝),
            // 故 kimi MCP 注入为最终 disabled 定论,非临时——kimi 用内建工具(Bash 等)。
            default -> McpGatewayCliConfig.disabled(
                    "MCP gateway injection not configured for provider: " + provider.value());
        };
    }

    private McpGatewayCliConfig writeClaude(Path providerDir, long revision, Path stateFile,
                                            List<String> command) throws IOException {
        Path configPath = providerDir.resolve("mcp-gateway.json");
        JsonObject root = new JsonObject();
        JsonObject servers = new JsonObject();
        servers.add(McpGatewayConstants.GATEWAY_SERVER_ID, gatewayServerJson(command, true));
        root.add(CliConstants.MCP_SERVERS_KEY, servers);
        writeJson(configPath, root);
        return new McpGatewayCliConfig(true, true, revision, configPath, stateFile,
                command, Map.of(), List.of(), null);
    }

    /**
     * Codex:生成 {@code -c} 覆盖列表,env 为空(CODEX_HOME 保持真实 {@code ~/.codex})。
     * configPath=null(无文件)。spawn 时由 CodexCliSession 把 overrideArgs 注入 exec/resume 两路径。
     */
    private McpGatewayCliConfig writeCodex(long revision, Path stateFile,
                                           List<String> command, List<String> realServerIds) {
        List<String> overrideArgs = buildCodexOverrideArgs(command, realServerIds);
        LOG.debug("[McpGateway] Codex override args: " + overrideArgs.size() + " entries (real servers disabled="
                + (overrideArgs.size() / 2 - 4) + "), CODEX_HOME unchanged (no temp home)");
        return new McpGatewayCliConfig(true, true, revision, null, stateFile,
                command, Map.of(), overrideArgs, null);
    }

    /**
     * OpenCode:生成 {@code OPENCODE_CONFIG_CONTENT} inline JSON,HOME/XDG 保持真实。
     * configPath=null(无文件);env 仅含这一项(运行时与真实 opencode.json 合并)。
     */
    private McpGatewayCliConfig writeOpenCode(long revision, Path stateFile,
                                              List<String> command, List<String> realServerIds) {
        String configContent = buildOpenCodeConfigContent(command, realServerIds);
        Map<String, String> env = Map.of(CliConstants.ENV_OPENCODE_CONFIG_CONTENT, configContent);
        return new McpGatewayCliConfig(true, true, revision, null, stateFile,
                command, env, List.of(), null);
    }

    private static List<String> withGatewayArgs(List<String> baseCommand, Path stateFile, long revision) {
        if (baseCommand == null || baseCommand.isEmpty()) {
            throw new IllegalArgumentException("Gateway command required");
        }
        List<String> result = new ArrayList<>(baseCommand);
        result.add(McpGatewayConstants.ARG_STATE_FILE);
        result.add(stateFile.toAbsolutePath().toString());
        result.add(McpGatewayConstants.ARG_REVISION);
        result.add(Long.toString(revision));
        return result;
    }

    private static JsonObject gatewayServerJson(List<String> command, boolean stdioType) {
        JsonObject obj = new JsonObject();
        if (stdioType) {
            obj.addProperty(McpGatewayConstants.KEY_TYPE, McpGatewayConstants.TRANSPORT_STDIO);
        }
        obj.addProperty(McpGatewayConstants.KEY_COMMAND, command.get(0));
        com.google.gson.JsonArray args = new com.google.gson.JsonArray();
        command.subList(1, command.size()).forEach(args::add);
        obj.add(McpGatewayConstants.KEY_ARGS, args);
        return obj;
    }

    private static void writeJson(Path path, JsonObject root) throws IOException {
        Files.writeString(path, PRETTY_GSON.toJson(root), StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 免临时 home 纯函数(包级 static,供单测):Codex -c 覆盖 / OpenCode inline JSON
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * 生成 Codex {@code -c} 命令行覆盖扁平列表。注入 melon_gateway 聚合入口定义 + 逐个禁用真实 server。
     * 经原生 codex.exe argv 直传(不经 cmd 转义),元素由 {@link #tomlString} 编码为 TOML literal
     * 字符串(单引号)——这是 codex {@code -c} 唯一能可靠承载反斜杠路径的形式(见 tomlString 文档)。
     *
     * @param command       完整 gateway 命令(含 --state-file/--revision);command[0]=二进制,其余=args
     * @param realServerIds 真实 mcp server 名(来自 collector);自动过滤 melon_gateway 自身
     * @return 扁平列表,每 2 元素一对("-c", value)
     */
    static List<String> buildCodexOverrideArgs(List<String> command, List<String> realServerIds) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Gateway command required");
        }
        List<String> args = new ArrayList<>();
        String prefix = "mcp_servers." + McpGatewayConstants.GATEWAY_SERVER_ID + ".";
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + "command=" + tomlString(command.get(0)));
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + "args=" + tomlArray(command.subList(1, command.size())));
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + "enabled=true");
        args.add(CliConstants.CODEX_ARG_C_CONFIG);
        args.add(prefix + "startup_timeout_sec=1");
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
     * 注入 melon_gateway 聚合入口 + 逐个禁用真实 server;HOME/XDG 保持真实。
     *
     * @param command       完整 gateway 命令(含 --state-file/--revision)
     * @param realServerIds 真实 mcp server 名(来自 collector);自动过滤 melon_gateway 自身
     * @return compact JSON 字符串,root 仅含 {@code mcp} 键
     */
    static String buildOpenCodeConfigContent(List<String> command, List<String> realServerIds) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Gateway command required");
        }
        JsonObject root = new JsonObject();
        JsonObject mcp = new JsonObject();
        JsonObject gateway = new JsonObject();
        gateway.addProperty(McpGatewayConstants.KEY_TYPE, "local");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        command.forEach(arr::add);
        gateway.add(McpGatewayConstants.KEY_COMMAND, arr);
        gateway.addProperty(McpGatewayConstants.KEY_ENABLED, true);
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

    private static String tomlArray(List<String> values) {
        return "[" + values.stream().map(McpGatewayConfigWriter::tomlString)
                .collect(Collectors.joining(", ")) + "]";
    }

    /**
     * 把单个字符串编码为 TOML 值,优先用 literal 字符串(单引号,不处理转义)。
     *
     * <p><b>必须用 literal 字符串(codex {@code -c} 路径):</b>codex 的 {@code -c key=value}
     * 在 TOML 解析前会对 value 做一次 {@code \\→\} 反转义。若用基本字符串 {@code "D:\\project"},
     * 这里把 {@code \} 加倍成 {@code \\} 会被 codex 还原成 {@code \},致 {@code "D:\project"} 出现
     * 非法 TOML 转义 {@code \p} → TOML 解析失败 → codex 退回把整个值当字符串 →
     * {@code "invalid type: string, expected a sequence in mcp_servers.melon_gateway.args"}。
     * literal 字符串不含 {@code \\} 序列,codex 的预反转义是空操作,TOML 正确解析数组。
     *
     * <p>2026-07-03 实测确认(直接 spawn 原生 codex.exe):基本字符串带反斜杠路径必崩,
     * literal 字符串 exec 与 exec resume 均正常。详见
     * {@code docs/codex-gateway-config-injection-refactor.md} §5。
     *
     * <p>literal 字符串不能含单引号/换行;含则回退基本字符串(双引号 + 标准转义)。
     * gateway 的 args/command 元素(node 脚本路径/--flag/state 路径/二进制名)实践中不含,
     * 回退仅为防御性完整覆盖。
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
