package com.github.claudecodegui.mcp;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes per-turn provider CLI configuration that contains only the Gateway MCP.
 */
public class McpGatewayConfigWriter {
    private static final Logger LOG = Logger.getInstance(McpGatewayConfigWriter.class);
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baseDir;

    public McpGatewayConfigWriter(Path baseDir) {
        this.baseDir = baseDir;
    }

    public McpGatewayCliConfig write(ProviderType provider, String tabId, long revision,
                                     Path stateFile, List<String> gatewayCommand) throws IOException {
        Files.createDirectories(baseDir);
        String safeTab = safeFilePart(tabId);
        Path providerDir = baseDir.resolve(provider.value()).resolve(safeTab);
        Files.createDirectories(providerDir);

        List<String> command = withGatewayArgs(gatewayCommand, stateFile, revision);
        return switch (provider) {
            case CLAUDE -> writeClaude(providerDir, revision, stateFile, command);
            case CODEX -> writeCodex(providerDir, revision, stateFile, command);
            case OPENCODE -> writeOpenCode(providerDir, revision, stateFile, command);
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
        return new McpGatewayCliConfig(true, true, revision, configPath, stateFile, command, Map.of(), null);
    }

    private McpGatewayCliConfig writeCodex(Path providerDir, long revision, Path stateFile,
                                           List<String> command) throws IOException {
        Path codexHome = providerDir.resolve("home");
        Files.createDirectories(codexHome);
        Path configPath = codexHome.resolve("config.toml");
        copyIfExists(Path.of(NodeDetector.resolveHomeForFileOps(), CommonConstants.DIR_CODEX, CommonConstants.FILE_AUTH_JSON),
                codexHome.resolve(CommonConstants.FILE_AUTH_JSON));
        // 复制真实 ~/.codex/config.toml 的稳定段(model/model_provider/model_reasoning_effort/
        // [model_providers.*]/proxy 等),否则临时 CODEX_HOME 只有 [mcp_servers.melon_gateway] →
        // codex 用默认 model(自定义 provider 502/卡死)+ 无 reasoning → 思考内容丢失。
        Path sourceConfig = Path.of(NodeDetector.resolveHomeForFileOps(),
                CommonConstants.DIR_CODEX, "config.toml");
        String stable = copyCodexStableSections(sourceConfig);
        String content = stable
                + "\n[mcp_servers." + McpGatewayConstants.GATEWAY_SERVER_ID + "]\n"
                + "command = " + tomlString(command.get(0)) + "\n"
                + "args = " + tomlArray(command.subList(1, command.size())) + "\n"
                + "enabled = true\n"
                + "startup_timeout_sec = 1\n";
        Files.writeString(configPath, content, StandardCharsets.UTF_8);
        Map<String, String> env = Map.of(CliConstants.ENV_CODEX_HOME, codexHome.toAbsolutePath().toString());
        return new McpGatewayCliConfig(true, true, revision, configPath, stateFile, command, env, null);
    }

    private McpGatewayCliConfig writeOpenCode(Path providerDir, long revision, Path stateFile,
                                              List<String> command) throws IOException {
        Path home = providerDir.resolve("home");
        Path configDir = home.resolve(CommonConstants.DIR_OPENCODE);
        Files.createDirectories(configDir);
        Path configPath = configDir.resolve(CommonConstants.FILE_OPENCODE_JSON);
        JsonObject root = new JsonObject();
        copyOpenCodeStableSections(root);
        JsonObject mcp = new JsonObject();
        JsonObject gateway = new JsonObject();
        gateway.addProperty(McpGatewayConstants.KEY_TYPE, "local");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        command.forEach(arr::add);
        gateway.add(McpGatewayConstants.KEY_COMMAND, arr);
        gateway.addProperty(McpGatewayConstants.KEY_ENABLED, true);
        mcp.add(McpGatewayConstants.GATEWAY_SERVER_ID, gateway);
        root.add(McpGatewayConstants.KEY_MCP_OPENCODE, mcp);
        writeJson(configPath, root);

        Map<String, String> env = new LinkedHashMap<>();
        String homeString = home.toAbsolutePath().toString();
        env.put(McpGatewayConstants.KEY_HOME, homeString);
        env.put(McpGatewayConstants.KEY_USERPROFILE, homeString);
        env.put(McpGatewayConstants.KEY_XDG_CONFIG_HOME, home.resolve(".config").toString());
        env.put(McpGatewayConstants.KEY_XDG_DATA_HOME, home.resolve(".local/share").toString());
        env.put(McpGatewayConstants.KEY_XDG_CACHE_HOME, home.resolve(".cache").toString());
        env.put(McpGatewayConstants.KEY_XDG_STATE_HOME, home.resolve(".local/state").toString());
        return new McpGatewayCliConfig(true, true, revision, configPath, stateFile, command, env, null);
    }

    private static List<String> withGatewayArgs(List<String> baseCommand, Path stateFile, long revision) {
        if (baseCommand == null || baseCommand.isEmpty()) {
            throw new IllegalArgumentException("Gateway command required");
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>(baseCommand);
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

    private static void copyIfExists(Path source, Path target) {
        try {
            if (Files.exists(source)) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    private static void copyOpenCodeStableSections(JsonObject target) {
        try {
            Path source = Path.of(PlatformUtils.getHomeDirectory(),
                    CommonConstants.DIR_OPENCODE, CommonConstants.FILE_OPENCODE_JSON);
            if (!Files.exists(source)) {
                return;
            }
            JsonObject original = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
            copySection(original, target, "provider");
            copySection(original, target, "permission");
            copySection(original, target, "agent");
            copySection(original, target, "model");
            copySection(original, target, "auth");
        } catch (Exception e) {
            // gateway 路径下若 provider 等稳定段复制失败,记录告警而非静默丢弃
            // (否则 opencode 临时 home 缺 provider 段 → CLI 启动失败/无 provider)。
            LOG.warn("[McpGateway] Failed to copy OpenCode stable config sections: " + e.getMessage(), e);
        }
    }

    /**
     * 复制真实 {@code ~/.codex/config.toml} 的稳定段——除 {@code [mcp_servers.*]} 外的全部内容,
     * 保留 model/model_provider/model_reasoning_effort/{@code [model_providers.*]}/proxy 等用户配置。
     * gateway 路径下临时 CODEX_HOME 不能只写 [mcp_servers.melon_gateway] 段,否则 codex 用默认
     * model(自定义 provider 502/卡死)+ 无 reasoning → 思考内容消失。{@code mcp_servers} 段由 gateway
     * 聚合提供,故剥离以免与 gateway 段重复。
     *
     * <p>纯文本行扫描(非 TOML 解析器),保留原格式与注释;遇下一个非 mcp_servers 段时恢复复制。
     *
     * @param source 真实 config.toml 路径;不存在或入参 null 时返回空串
     * @return 稳定段文本(以换行结尾,便于追加 gateway 段)
     */
    static String copyCodexStableSections(Path source) {
        if (source == null || !Files.exists(source)) {
            return "";
        }
        try {
            String original = Files.readString(source, StandardCharsets.UTF_8);
            StringBuilder stable = new StringBuilder();
            boolean skippingMcpServers = false;
            for (String line : original.split("\\r?\\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    // 进入新 section;[mcp_servers.*] 段由 gateway 聚合提供,跳过
                    skippingMcpServers = trimmed.startsWith("[mcp_servers");
                }
                if (!skippingMcpServers) {
                    stable.append(line).append('\n');
                }
            }
            return stable.toString();
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to copy Codex stable config sections: " + e.getMessage(), e);
            return "";
        }
    }

    private static void copySection(JsonObject source, JsonObject target, String key) {
        if (source.has(key)) {
            target.add(key, source.get(key).deepCopy());
        }
    }

    private static String safeFilePart(String value) {
        String raw = value == null || value.isBlank() ? CommonConstants.DEFAULT_TAB_ID : value.trim();
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() > 128 ? safe.substring(0, 128) : safe;
    }

    private static String tomlArray(List<String> values) {
        return "[" + values.stream().map(McpGatewayConfigWriter::tomlString)
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    private static String tomlString(String value) {
        return "\"" + (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
