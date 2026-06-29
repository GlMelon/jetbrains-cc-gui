package com.github.claudecodegui.config;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 读取 OpenCode 配置文件 ({@code ~/.config/opencode/opencode.json}) 中的模型配置。
 *
 * <p>配置文件格式示例:
 * <pre>
 * {
 *   "provider": {
 *     "mimo": {
 *       "name": "Xiaomi MiMo Token Plan (China)",
 *       "models": {
 *         "mimo-v2.5": {
 *           "name": "MiMo V2.5",
 *           "limit": {
 *             "context": 1048576,
 *             "output": 131072
 *           }
 *         }
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
public final class OpenCodeConfigReader {
    private static final Logger LOG = Logger.getLogger(OpenCodeConfigReader.class.getName());

    private OpenCodeConfigReader() {
    }

    /**
     * 从 OpenCode 配置文件读取所有模型配置。
     *
     * @return 模型配置列表，读取失败时返回空列表
     */
    public static List<ModelConfig> readModels() {
        return readModels(resolveConfigPath());
    }

    /**
     * 从指定配置文件读取模型配置(包级,供测试注入路径,对称 {@link #readMcpServers(Path)})。
     */
    static List<ModelConfig> readModels(Path configPath) {
        List<ModelConfig> models = new ArrayList<>();
        try {
            if (configPath == null || !Files.exists(configPath)) {
                return models;
            }

            String content = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();

            // 解析 provider 配置
            JsonObject providers = root.getAsJsonObject("provider");
            if (providers == null) {
                return models;
            }

            for (Map.Entry<String, JsonElement> providerEntry : providers.entrySet()) {
                String providerName = providerEntry.getKey();
                JsonObject providerConfig = providerEntry.getValue().getAsJsonObject();

                // 获取 provider 显示名称
                String providerDisplayName = providerConfig.has("name")
                        ? providerConfig.get("name").getAsString()
                        : providerName;

                // 解析该 provider 下的所有模型
                JsonObject modelsObj = providerConfig.getAsJsonObject("models");
                if (modelsObj == null) {
                    continue;
                }

                for (Map.Entry<String, JsonElement> modelEntry : modelsObj.entrySet()) {
                    String modelId = modelEntry.getKey();
                    JsonObject modelConfig = modelEntry.getValue().getAsJsonObject();

                    ModelConfig model = parseModelConfig(modelId, modelConfig, providerName, providerDisplayName);
                    if (model != null) {
                        models.add(model);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read OpenCode config", e);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse OpenCode config", e);
        }
        return models;
    }

    /**
     * 从 OpenCode 配置文件读取 MCP server 配置列表(对称 {@link #readModels()} 的 SSOT 读取)。
     *
     * <p>§15.9 B22:MCP 透传。OpenCode 的 MCP 工具在会话中按需透传({@code message.part.updated}
     * type=tool → tool_use,见 ai-bridge event-mapper),opencode 无"列工具"命令/SDK API;
     * 本方法仅暴露 server 配置(id/type/command/url/enabled)让"配置了哪些 server"可达。
     *
     * <p>配置格式示例(真实 {@code ~/.config/opencode/opencode.json}):
     * <pre>
     * { "mcp": { "serverId": { "type": "local", "command": ["npx","pkg"], "enabled": true } } }
     * </pre>
     *
     * @return server 配置列表(每个为 JsonObject:id,type,enabled,command?/url?),读取失败返回空列表
     */
    public static List<JsonObject> readMcpServers() {
        return readMcpServers(resolveConfigPath());
    }

    /**
     * 从指定配置文件读取 MCP server 配置(包级,供测试注入路径)。
     */
    static List<JsonObject> readMcpServers(Path configPath) {
        List<JsonObject> servers = new ArrayList<>();
        try {
            if (configPath == null || !Files.exists(configPath)) {
                return servers;
            }
            String content = Files.readString(configPath);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            JsonObject mcp = root.getAsJsonObject("mcp");
            if (mcp == null) {
                return servers;
            }
            for (Map.Entry<String, JsonElement> entry : mcp.entrySet()) {
                String id = entry.getKey();
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject server = entry.getValue().getAsJsonObject();
                JsonObject info = new JsonObject();
                info.addProperty("id", id);
                info.addProperty("type", server.has("type") ? server.get("type").getAsString() : "local");
                info.addProperty("enabled", !server.has("enabled") || server.get("enabled").getAsBoolean());
                if (server.has("command")) {
                    info.add("command", server.get("command"));
                }
                if (server.has("url")) {
                    info.addProperty("url", server.get("url").getAsString());
                }
                if (server.has("environment")) {
                    info.add("environment", server.get("environment"));
                }
                servers.add(info);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read OpenCode MCP servers", e);
        }
        return servers;
    }

    /**
     * 解析单个模型配置。
     */
    private static ModelConfig parseModelConfig(String modelId, JsonObject modelConfig,
                                                String providerName, String providerDisplayName) {
        // 获取模型显示名称
        String modelName = modelConfig.has("name")
                ? modelConfig.get("name").getAsString()
                : modelId;

        // 获取 context window 限制
        int contextWindow = CommonConstants.DEFAULT_CONTEXT_WINDOW;
        JsonObject limit = modelConfig.getAsJsonObject("limit");
        if (limit != null && limit.has("context")) {
            contextWindow = limit.get("context").getAsInt();
        }

        // 获取 output token 限制（目前未使用，但保留用于将来）
        // int outputLimit = limit != null && limit.has("output")
        //         ? limit.get("output").getAsInt()
        //         : 0;

        // 构建描述
        String description = "只读 · 来自 ~/.config/opencode/opencode.json (" + providerDisplayName + ")";

        // OpenCode 模型使用 role 字段存储 provider 名称。actualModel 是 CLI --model 透传值,
        // opencode --model 要求 provider/model 格式(如 openglm/glm-5.2);传裸名(如 glm-5.2)
        // 会触发 "Unexpected server error",表现为 OpenCode CLI「Generating response 后无回复无错误」。
        // 故 actualModel 拼成 provider/model;canonical id(modelId)保持裸名作选择键/去重(前端零改)。
        return new ModelConfig(
                modelId,
                CommonConstants.PROVIDER_OPENCODE,
                providerName,             // role 存储 provider 名称
                modelName,
                withProviderPrefix(providerName, modelId),  // actualModel = provider/model(CLI -m 透传)
                description,
                contextWindow,
                false,                    // supports1MContext
                true,                     // enabled
                true                      // readOnly
        );
    }

    /**
     * 将裸模型 id 规整为 opencode {@code --model} 所需的 {@code provider/model} 格式。
     * <p>opencode 配置中模型恒嵌套于 provider 下(modelId 为裸名,如 {@code glm-5.2}),
     * 故拼 {@code providerName + "/" + modelId};若 modelId 已含 "/"(已限定的边界场景),
     * 原样返回避免重复前缀。
     */
    private static String withProviderPrefix(String providerName, String modelId) {
        if (modelId != null && modelId.contains("/")) {
            return modelId;
        }
        return providerName + "/" + modelId;
    }

    /**
     * 解析 OpenCode 配置文件路径。
     * <p>对称 {@link com.github.claudecodegui.cli.common.CliSettings}(Claude {@code ~/.claude}、
     * Codex {@code ~/.codex}):统一用 {@link PlatformUtils#getHomeDirectory()} 解析真实 OS home
     * (Windows %USERPROFILE% / Unix $HOME),而非 {@code System.getProperty("user.home")}。
     * 后者在 IDEA 覆盖 user.home 时读错位置,且测试 {@code setCachedHomeDirectory}(反射设
     * {@code PlatformUtils.cachedRealHomeDir})无法隔离 → 只读默认混入真实 opencode.json 模型污染 registry 校验。
     */
    private static Path resolveConfigPath() {
        String home = PlatformUtils.getHomeDirectory();
        if (home == null || home.isBlank()) {
            return null;
        }
        return Path.of(home, CommonConstants.DIR_OPENCODE, CommonConstants.FILE_OPENCODE_JSON);
    }
}
