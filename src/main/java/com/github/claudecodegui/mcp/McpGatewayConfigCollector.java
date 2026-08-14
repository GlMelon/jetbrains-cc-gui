package com.github.claudecodegui.mcp;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.OpenCodeConfigReader;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects provider MCP settings into a provider-neutral Gateway snapshot.
 */
public class McpGatewayConfigCollector {
    private static final Logger LOG = Logger.getInstance(McpGatewayConfigCollector.class);

    private final CodemossSettingsService settingsService;

    public McpGatewayConfigCollector(CodemossSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public McpGatewayConfigSnapshot collect(long revision, String projectPath) {
        List<McpGatewayServerSpec> servers = new ArrayList<>();
        collectClaude(projectPath, servers);
        collectCodex(servers);
        collectOpenCode(servers);
        return McpGatewayConfigSnapshot.create(revision, projectPath, servers);
    }

    private void collectClaude(String projectPath, List<McpGatewayServerSpec> servers) {
        try {
            for (JsonObject server : settingsService.getMcpServersWithProjectPath(projectPath)) {
                McpGatewayServerSpec spec = fromFrontendShape(CommonConstants.PROVIDER_CLAUDE, server);
                if (spec != null) {
                    servers.add(spec);
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to collect Claude MCP settings: " + e.getMessage());
        }
    }

    private void collectCodex(List<McpGatewayServerSpec> servers) {
        try {
            for (JsonObject server : settingsService.getCodexMcpServerManager().getMcpServers()) {
                McpGatewayServerSpec spec = fromFrontendShape(CommonConstants.PROVIDER_CODEX, server);
                if (spec != null) {
                    servers.add(spec);
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to collect Codex MCP settings: " + e.getMessage());
        }
    }

    private void collectOpenCode(List<McpGatewayServerSpec> servers) {
        try {
            for (JsonObject server : OpenCodeConfigReader.readMcpServers()) {
                String id = getString(server, CommonConstants.JSON_KEY_ID);
                if (id == null || id.isBlank()) {
                    continue;
                }
                boolean enabled = !server.has(McpGatewayConstants.KEY_ENABLED)
                        || server.get(McpGatewayConstants.KEY_ENABLED).getAsBoolean();
                JsonObject config = normalizeOpenCodeConfig(server);
                String transport = normalizeOpenCodeTransport(getString(server, McpGatewayConstants.KEY_TYPE));
                servers.add(new McpGatewayServerSpec(
                        CommonConstants.PROVIDER_OPENCODE,
                        id,
                        enabled,
                        transport,
                        config
                ));
            }
        } catch (Exception e) {
            LOG.warn("[McpGateway] Failed to collect OpenCode MCP settings: " + e.getMessage());
        }
    }

    private static McpGatewayServerSpec fromFrontendShape(String provider, JsonObject server) {
        if (server == null) {
            return null;
        }
        String id = getString(server, CommonConstants.JSON_KEY_ID);
        if (id == null || id.isBlank()) {
            id = getString(server, CommonConstants.JSON_KEY_NAME);
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        boolean enabled = !server.has(McpGatewayConstants.KEY_ENABLED)
                || server.get(McpGatewayConstants.KEY_ENABLED).getAsBoolean();
        JsonObject config = server.has("server") && server.get("server").isJsonObject()
                ? server.getAsJsonObject("server").deepCopy()
                : server.deepCopy();
        String transport = normalizeTransport(getString(config, McpGatewayConstants.KEY_TYPE), config);
        return new McpGatewayServerSpec(provider, id, enabled, transport, config);
    }

    private static String normalizeTransport(String type, JsonObject config) {
        if (type != null) {
            String lower = type.trim().toLowerCase();
            if (CommonConstants.MCP_TRANSPORT_HTTP.equals(lower)) {
                return McpGatewayConstants.TRANSPORT_HTTP;
            }
            if (CommonConstants.MCP_TRANSPORT_SSE.equals(lower)) {
                return McpGatewayConstants.TRANSPORT_SSE;
            }
        }
        if (config != null && config.has(McpGatewayConstants.KEY_URL)) {
            return McpGatewayConstants.TRANSPORT_HTTP;
        }
        return McpGatewayConstants.TRANSPORT_STDIO;
    }

    private static String normalizeOpenCodeTransport(String type) {
        if (type == null || type.isBlank() || "local".equalsIgnoreCase(type)) {
            return McpGatewayConstants.TRANSPORT_STDIO;
        }
        return normalizeTransport(type, null);
    }

    /**
     * 归一化 OpenCode 原生 MCP 配置到 Gateway 消费形状(对称 Claude/Codex 的 {@link #fromFrontendShape})。
     * <p>OpenCode 原生格式({@code opencode.json} 的 {@code mcp} 字段,经 {@link OpenCodeConfigReader#readMcpServers}
     * 透传)与 Gateway 协议(StdioMcpClient)差异:
     * <ul>
     *   <li>{@code command} 为字符串数组(如 {@code ["npx","-y","pkg"]}),Gateway 期望
     *       {@code command:string} + {@code args:string[]} —— 不转换则 spawn(数组) 直接失败,
     *       全部 server 进 BACKOFF(面板全红)。</li>
     *   <li>环境变量键为 {@code environment},Gateway 期望 {@code env}。</li>
     * </ul>
     * id/type/enabled 由调用方单独处理;此处只产出 Gateway config 消费的字段(command/args/env/url)。
     */
    static JsonObject normalizeOpenCodeConfig(JsonObject server) {
        JsonObject config = new JsonObject();
        JsonElement command = server.get(McpGatewayConstants.KEY_COMMAND);
        if (command != null && !command.isJsonNull()) {
            if (command.isJsonArray()) {
                JsonArray argv = command.getAsJsonArray();
                if (!argv.isEmpty() && argv.get(0).isJsonPrimitive()) {
                    config.addProperty(McpGatewayConstants.KEY_COMMAND, argv.get(0).getAsString());
                    if (argv.size() > 1) {
                        JsonArray args = new JsonArray();
                        for (int i = 1; i < argv.size(); i++) {
                            args.add(argv.get(i));
                        }
                        config.add(McpGatewayConstants.KEY_ARGS, args);
                    }
                }
            } else if (command.isJsonPrimitive()) {
                // 防御:command 已是 string(手工编辑/格式演进)则原样保留
                config.addProperty(McpGatewayConstants.KEY_COMMAND, command.getAsString());
                JsonElement args = server.get(McpGatewayConstants.KEY_ARGS);
                if (args != null && args.isJsonArray()) {
                    config.add(McpGatewayConstants.KEY_ARGS, args);
                }
            }
        }
        JsonElement environment = server.get(McpGatewayConstants.KEY_ENVIRONMENT_OPENCODE);
        if (environment != null && environment.isJsonObject()) {
            config.add(McpGatewayConstants.KEY_ENV, environment.getAsJsonObject().deepCopy());
        }
        JsonElement url = server.get(McpGatewayConstants.KEY_URL);
        if (url != null && !url.isJsonNull()) {
            config.add(McpGatewayConstants.KEY_URL, url.deepCopy());
        }
        return config;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.isJsonPrimitive() ? element.getAsString() : GsonHolder.GSON.toJson(element);
    }
}
