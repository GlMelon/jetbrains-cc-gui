package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.settings.RuntimeSharedConfigService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-tab MCP 配置管理。
 * 每个 tab 拥有独立的 MCP 配置文件，通过 --mcp-config 传给 Claude CLI。
 */
public class CliMcpConfig {

    private static final Logger LOG = Logger.getInstance(CliMcpConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String tabId;
    private final Path configPath;
    // 懒加载:构造时不创建 RuntimeSharedConfigService(它会触发 CodemossSettingsService.getInstance(),
    // 依赖 IDE Application)。延迟到 doInitialize(运行时一定是 IDE 环境)再创建,使本类可在纯单元测试中实例化。
    private RuntimeSharedConfigService sharedConfigService;
    private JsonObject servers = new JsonObject();
    private volatile boolean initialized = false;

    public CliMcpConfig(String tabId) {
        this.tabId = tabId;
        Path base = new ConfigPathManager().getConfigDir().resolve("cli-mcp");
        // 纵深防御:tabId 可能源自前端,直接拼进文件名会带来路径穿越风险(如 "../x")。
        // 归一化为安全文件名,确保 resolve 后路径仍在 base 目录之内。
        this.configPath = base.resolve(safeConfigFileName(tabId));
    }

    /**
     * 将 tabId 归一化为安全的配置文件名:仅保留 {@code [A-Za-z0-9._-]},其余字符替换为
     * {@code '_'},并限制长度。这样 base.resolve(...) 永远不会解析到 base 目录之外。
     */
    private static String safeConfigFileName(String tabId) {
        String id = (tabId == null) ? "" : tabId.trim();
        if (id.isEmpty()) {
            id = "default";
        }
        String safe = id.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 128) {
            safe = safe.substring(0, 128);
        }
        return safe + ".json";
    }

    /**
     * 确保配置已初始化（懒加载）。
     * 首次调用时从磁盘或全局配置加载 MCP server 列表，后续调用为空操作。
     */
    public void ensureInitialized() {
        if (initialized) {
            return;
        }
        doInitialize();
    }

    /**
     * 从 Claude 全局 settings.json 中的 mcpServers 初始化 per-tab 配置。
     * 如果文件已存在则直接加载。
     */
    private void doInitialize() {
        try {
            if (sharedConfigService == null) {
                sharedConfigService = new RuntimeSharedConfigService();
            }
            if (Files.exists(configPath)) {
                String content = Files.readString(configPath, StandardCharsets.UTF_8);
                JsonObject existing = GSON.fromJson(content, JsonObject.class);
                if (existing != null) {
                    // 兼容旧格式（纯 servers）和新格式（mcpServers 包裹）
                    if (existing.has(CliConstants.MCP_SERVERS_KEY) && existing.get(CliConstants.MCP_SERVERS_KEY).isJsonObject()) {
                        servers = existing.getAsJsonObject(CliConstants.MCP_SERVERS_KEY);
                    } else {
                        servers = existing;
                    }
                    initialized = true;
                    return;
                }
            }
            servers = sharedConfigService.getSharedMcpServers(null);
            persist();
        } catch (Exception e) {
            LOG.warn("[CliMcpConfig] Failed to initialize MCP config for tab " + tabId, e);
        }
        initialized = true;
    }

    /** 返回配置文件路径，传给 --mcp-config。 */
    public String getConfigFilePath() {
        ensureInitialized();
        return configPath.toAbsolutePath().toString();
    }

    public boolean hasServers() {
        ensureInitialized();
        return servers.size() > 0;
    }

    public void cleanup() {
        try {
            Files.deleteIfExists(configPath);
        } catch (Exception ignored) {
        }
    }

    private void persist() {
        try {
            Files.createDirectories(configPath.getParent());
            // Claude CLI --mcp-config 要求 { "mcpServers": { ... } } 格式
            JsonObject wrapper = new JsonObject();
            wrapper.add(CliConstants.MCP_SERVERS_KEY, servers);
            Files.writeString(configPath, GSON.toJson(wrapper), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[CliMcpConfig] Failed to persist MCP config: " + e.getMessage());
        }
    }
}
