package com.github.claudecodegui.settings;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpCommandRiskEvaluator;
import com.github.claudecodegui.mcp.McpInstallRejectedException;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * MCP Server Manager.
 *
 * <p>全局 SSOT:{@code ~/.codemoss/config.json} 的 {@code mcpServers} 数组(provider 无关)。
 * 增删在写全局 SSOT 后,写穿三家原生配置文件:claude({@code ~/.claude.json},本类自持,
 * 含 projectPath 项目级合并与 syncMcpToClaudeSettings)、codex / opencode(经
 * {@link McpNativeConfigWriteThrough},best-effort)。
 *
 * <p>首次访问时做一次性迁移(标记键 {@link #MCP_MIGRATED_TO_GLOBAL_KEY}):把三家原生配置
 * 按 claude → codex → opencode 优先级导入全局数组,按 id 去重,绝不删除原生文件内容。
 */
public class McpServerManager {
    private static final Logger LOG = Logger.getInstance(McpServerManager.class);

    /** 一次性迁移标记:config.json 中为 true 表示三家原生 MCP 配置已导入全局 SSOT。 */
    private static final String MCP_MIGRATED_TO_GLOBAL_KEY = "mcpMigratedToGlobal";

    private final Gson gson;
    private final Function<Void, JsonObject> configReader;
    private final java.util.function.Consumer<JsonObject> configWriter;
    private final ClaudeSettingsManager claudeSettingsManager;
    /** codex / opencode 原生配置写穿协调器(best-effort)。 */
    private final McpNativeConfigWriteThrough writeThrough;

    public McpServerManager(
            Gson gson,
            Function<Void, JsonObject> configReader,
            java.util.function.Consumer<JsonObject> configWriter,
            ClaudeSettingsManager claudeSettingsManager,
            CodexMcpServerManager codexMcpServerManager,
            OpenCodeSettingsManager openCodeSettingsManager) {
        this.gson = gson;
        this.configReader = configReader;
        this.configWriter = configWriter;
        this.claudeSettingsManager = claudeSettingsManager;
        this.writeThrough = new McpNativeConfigWriteThrough(codexMcpServerManager, openCodeSettingsManager);
    }

    /**
     * Get all MCP servers.
     * Reads the global store (~/.codemoss/config.json mcpServers, the SSOT).
     */
    public List<JsonObject> getMcpServers() throws IOException {
        return getMcpServersWithProjectPath(null);
    }

    /**
     * Get all MCP servers (with project path support).
     *
     * <p>主存储为全局 SSOT(~/.codemoss/config.json mcpServers 数组);projectPath 非空时保留
     * claude 项目级合并语义:~/.claude.json {@code projects.<path>.mcpServers} 覆盖同 id 全局条目,
     * {@code projects.<path>.disabledMcpServers} 仍禁用。~/.claude.json 全局级 mcpServers 不再在此
     * 合并——改由一次性迁移导入全局 SSOT(见 {@link #readClaudeNativeMcpServers})。
     *
     * @param projectPath the project path, used to read project-level MCP configuration
     */
    public List<JsonObject> getMcpServersWithProjectPath(String projectPath) throws IOException {
        migrateNativeMcpServersToGlobal();

        // 1. 全局 SSOT:~/.codemoss/config.json mcpServers 数组
        List<JsonObject> result = new ArrayList<>();
        JsonObject config = configReader.apply(null);
        if (config.has("mcpServers") && config.get("mcpServers").isJsonArray()) {
            for (JsonElement elem : config.getAsJsonArray("mcpServers")) {
                if (elem.isJsonObject()) {
                    result.add(elem.getAsJsonObject().deepCopy());
                }
            }
        }

        // 2. claude 项目级合并(仅 projectPath 非空;读 ~/.claude.json 的 projects 段)
        if (projectPath != null) {
            mergeClaudeProjectLevel(result, projectPath);
        }

        LOG.info("[McpServerManager] Loaded " + result.size()
                         + " MCP servers from global store (projectPath: "
                         + (projectPath != null ? projectPath : "(none)") + ")");
        return result;
    }

    /**
     * 读取 claude 原生 MCP 配置(迁移前的旧读路径):~/.claude.json 全局 mcpServers +
     * 项目级合并 + disabledMcpServers,转前端嵌套形状。供一次性迁移与 MCP Gateway
     * collector 直读使用;文件缺失 / 解析失败返回空表(不抛)。
     *
     * @param projectPath the project path, used to merge project-level MCP configuration
     */
    public List<JsonObject> readClaudeNativeMcpServers(String projectPath) {
        List<JsonObject> result = new ArrayList<>();

        JsonObject claudeJson = readClaudeJsonQuietly();
        if (claudeJson == null
                || !claudeJson.has("mcpServers") || !claudeJson.get("mcpServers").isJsonObject()) {
            return result;
        }
        JsonObject globalMcpServers = claudeJson.getAsJsonObject("mcpServers");

        // Merge global and project mcpServers (project config overrides servers with the same name)
        JsonObject mergedServers = new JsonObject();
        for (String key : globalMcpServers.keySet()) {
            mergedServers.add(key, globalMcpServers.get(key));
        }

        JsonObject projectConfig = readClaudeProjectConfig(claudeJson, projectPath);
        if (projectConfig != null && projectConfig.has("mcpServers")
                && projectConfig.get("mcpServers").isJsonObject()) {
            JsonObject projectMcpServers = projectConfig.getAsJsonObject("mcpServers");
            for (String key : projectMcpServers.keySet()) {
                mergedServers.add(key, projectMcpServers.get(key));
            }
            LOG.info("[McpServerManager] Merged project-level MCP servers from: " + projectPath);
        }

        // Read the globally disabled servers list
        Set<String> disabledServers = new HashSet<>();
        if (claudeJson.has("disabledMcpServers") && claudeJson.get("disabledMcpServers").isJsonArray()) {
            JsonArray disabledArray = claudeJson.getAsJsonArray("disabledMcpServers");
            for (JsonElement elem : disabledArray) {
                if (elem.isJsonPrimitive()) {
                    disabledServers.add(elem.getAsString());
                }
            }
        }

        // Read project-level disabled servers list (if project path is provided)
        if (projectConfig != null && projectConfig.has("disabledMcpServers")
                && projectConfig.get("disabledMcpServers").isJsonArray()) {
            JsonArray projectDisabledArray = projectConfig.getAsJsonArray("disabledMcpServers");
            for (JsonElement elem : projectDisabledArray) {
                if (elem.isJsonPrimitive()) {
                    disabledServers.add(elem.getAsString());
                }
            }
            LOG.info("[McpServerManager] Merged project-level disabled servers from: " + projectPath);
        }

        // Convert merged servers to list format
        for (String serverId : mergedServers.keySet()) {
            JsonElement serverElem = mergedServers.get(serverId);
            if (serverElem.isJsonObject()) {
                result.add(toFrontendServerShape(serverId, serverElem.getAsJsonObject(), disabledServers));
            }
        }

        LOG.info("[McpServerManager] Loaded " + result.size()
                         + " MCP servers from ~/.claude.json (disabled: " + disabledServers.size() + ")");
        return result;
    }

    /**
     * Upsert (update or insert) an MCP server.
     * Writes the global store (~/.codemoss/config.json mcpServers, the SSOT),
     * then writes through to the providers' native config files.
     */
    public void upsertMcpServer(JsonObject server) throws IOException {
        upsertMcpServer(server, null);
    }

    /**
     * Upsert (update or insert) an MCP server (with project path support).
     *
     * <p>写入顺序:SEC-01 闸门(任何写盘之前)→ 一次性迁移 → 全局 SSOT 数组 upsert →
     * claude 原生写(失败仅 warn)→ codex / opencode 写穿(best-effort)。
     *
     * @param projectPath the project path, used to update project-level disabledMcpServers (Claude CLI merges global and project-level disabled lists)
     */
    public void upsertMcpServer(JsonObject server, String projectPath) throws IOException {
        if (!server.has("id")) {
            throw new IllegalArgumentException("Server must have an id");
        }

        String serverId = server.get("id").getAsString();

        // SEC-01 安全闸门:必须在任何写盘(含一次性迁移与原生写穿)之前触发,拒绝异常直接上抛。
        // 基于「合并现有同名 spec 后」的最终 command/args 重算 riskLevel
        // (UPDATE 只改 args、command 来自旧配置时,入口重算会漏判,故必须 merge 后重算)。
        enforceRiskGate(serverId, server);

        // 一次性迁移:把三家原生配置导入全局 SSOT(仅首次,见标记键)
        migrateNativeMcpServersToGlobal();

        boolean isEnabled = !server.has("enabled") || server.get("enabled").getAsBoolean();

        // 1. 写全局 SSOT:~/.codemoss/config.json mcpServers 数组 upsert
        JsonObject config = configReader.apply(null);
        JsonArray servers;

        if (config.has("mcpServers") && config.get("mcpServers").isJsonArray()) {
            servers = config.getAsJsonArray("mcpServers");
        } else {
            servers = new JsonArray();
            config.add("mcpServers", servers);
        }

        boolean found = false;

        // Find and update
        for (int i = 0; i < servers.size(); i++) {
            JsonObject s = servers.get(i).getAsJsonObject();
            if (s.has("id") && s.get("id").getAsString().equals(serverId)) {
                servers.set(i, server); // Replace
                found = true;
                break;
            }
        }

        if (!found) {
            servers.add(server);
        }

        configWriter.accept(config);
        LOG.info("[McpServerManager] Upserted MCP server in global store (~/.codemoss/config.json): " + serverId);

        // 2. claude 原生写:~/.claude.json 存在时外科手术式更新(merge + disabledMcpServers +
        //    syncMcpToClaudeSettings);失败仅 warn,不影响全局 SSOT 已落盘的结果
        upsertClaudeNativeMcpServer(server, serverId, isEnabled, projectPath);

        // 3. codex / opencode 原生写穿(best-effort)
        writeThrough.upsert(server);
    }

    /**
     * SEC-01 安全闸门:基于「合并现有同名 server spec 后」的最终 command/args 重算
     * riskLevel,危险(unverified-command)则抛 {@link McpInstallRejectedException} 阻止落盘。
     * <p>在 {@code upsertMcpServer} 任何写盘之前调用,确保拒绝异常不被内部 catch(Exception) 吞掉。
     * 现有 spec 先查全局 SSOT(config.json),无则回退 ~/.claude.json;读取失败按 best-effort
     * 用传入 spec 判定,不阻塞正常安装流程。
     */
    private void enforceRiskGate(String serverId, JsonObject incoming) {
        JsonObject incomingSpec = (incoming.has("server") && incoming.get("server").isJsonObject())
                ? incoming.getAsJsonObject("server").deepCopy() : new JsonObject();

        JsonObject finalSpec = incomingSpec;
        try {
            JsonObject existingSpec = findExistingSpec(serverId);
            if (existingSpec != null) {
                // 与 upsert 写盘同语义:现有 spec 为底,incoming 字段覆盖
                for (String key : incomingSpec.keySet()) {
                    existingSpec.add(key, incomingSpec.get(key));
                }
                finalSpec = existingSpec;
            }
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Risk gate could not read existing server, using incoming spec: "
                    + e.getMessage());
        }

        if (McpCommandRiskEvaluator.shouldReject(finalSpec)) {
            throw new McpInstallRejectedException(
                    "MCP server '" + serverId + "' rejected: " + McpCommandRiskEvaluator.explainRisk(finalSpec));
        }
    }

    /**
     * 查现有同名 server spec:先全局 SSOT(config.json mcpServers 数组的 server 字段),
     * 无则回退 ~/.claude.json 全局 mcpServers;均无返回 null。
     */
    private JsonObject findExistingSpec(String serverId) {
        try {
            JsonObject config = configReader.apply(null);
            if (config.has("mcpServers") && config.get("mcpServers").isJsonArray()) {
                for (JsonElement elem : config.getAsJsonArray("mcpServers")) {
                    if (!elem.isJsonObject()) {
                        continue;
                    }
                    JsonObject s = elem.getAsJsonObject();
                    if (s.has("id") && s.get("id").getAsString().equals(serverId)
                            && s.has("server") && s.get("server").isJsonObject()) {
                        return s.getAsJsonObject("server").deepCopy();
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Risk gate could not read global store, trying ~/.claude.json: "
                    + e.getMessage());
        }

        JsonObject claudeJson = readClaudeJsonQuietly();
        if (claudeJson != null && claudeJson.has("mcpServers") && claudeJson.get("mcpServers").isJsonObject()) {
            JsonObject mcpServers = claudeJson.getAsJsonObject("mcpServers");
            if (mcpServers.has(serverId) && mcpServers.get(serverId).isJsonObject()) {
                return mcpServers.getAsJsonObject(serverId).deepCopy();
            }
        }
        return null;
    }

    /**
     * 一次性迁移:config.json 无 {@link #MCP_MIGRATED_TO_GLOBAL_KEY} 标记时,把三家原生 MCP 配置
     * 按 claude → codex → opencode 优先级导入全局 SSOT 数组(按 id 去重,跳过全局数组已有 id,
     * 剥离读时合成的 {@code apps} 字段),随后写入标记。单源读取失败仅 LOG.warn 继续其余源;
     * 绝不删除原生文件内容。
     */
    private void migrateNativeMcpServersToGlobal() {
        JsonObject config;
        try {
            config = configReader.apply(null);
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Migration could not read config.json: " + e.getMessage());
            return;
        }
        if (config.has(MCP_MIGRATED_TO_GLOBAL_KEY) && config.get(MCP_MIGRATED_TO_GLOBAL_KEY).isJsonPrimitive()) {
            try {
                if (config.get(MCP_MIGRATED_TO_GLOBAL_KEY).getAsBoolean()) {
                    return;
                }
            } catch (Exception e) {
                // 标记值非布尔,按未迁移处理
            }
        }

        Set<String> knownIds = new HashSet<>();
        JsonArray servers;
        if (config.has("mcpServers") && config.get("mcpServers").isJsonArray()) {
            servers = config.getAsJsonArray("mcpServers");
            for (JsonElement elem : servers) {
                if (elem.isJsonObject() && elem.getAsJsonObject().has("id")) {
                    knownIds.add(elem.getAsJsonObject().get("id").getAsString());
                }
            }
        } else {
            servers = new JsonArray();
            config.add("mcpServers", servers);
        }

        int imported = 0;
        imported += importNativeServers(servers, knownIds, CommonConstants.PROVIDER_CLAUDE,
                () -> readClaudeNativeMcpServers(null));
        imported += importNativeServers(servers, knownIds, ProviderType.CODEX.value(),
                writeThrough::readCodex);
        imported += importNativeServers(servers, knownIds, ProviderType.OPENCODE.value(),
                writeThrough::readOpenCode);

        config.addProperty(MCP_MIGRATED_TO_GLOBAL_KEY, true);
        try {
            configWriter.accept(config);
            LOG.info("[McpServerManager] Migrated " + imported
                             + " native MCP servers into global store (marker set)");
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Migration could not write config.json: " + e.getMessage());
        }
    }

    /**
     * 迁移单源导入:读取某 provider 原生 servers,按 id 去重追加到全局数组(剥离 apps 字段)。
     *
     * @return 实际导入条数
     */
    private int importNativeServers(JsonArray servers, Set<String> knownIds, String source,
                                    Supplier<List<JsonObject>> reader) {
        List<JsonObject> nativeServers;
        try {
            nativeServers = reader.get();
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Migration: failed to read " + source
                    + " native MCP servers: " + e.getMessage());
            return 0;
        }
        int count = 0;
        for (JsonObject server : nativeServers) {
            if (server == null || !server.has("id")) {
                continue;
            }
            String id = server.get("id").getAsString();
            if (knownIds.contains(id)) {
                continue;
            }
            JsonObject copy = server.deepCopy();
            copy.remove("apps"); // apps 是读时合成字段,不入全局 SSOT
            servers.add(copy);
            knownIds.add(id);
            count++;
        }
        return count;
    }

    /**
     * claude 项目级合并:~/.claude.json {@code projects.<path>.mcpServers} 覆盖同 id 全局条目
     * (项目独有条目追加),{@code projects.<path>.disabledMcpServers} 禁用对应条目。
     */
    private void mergeClaudeProjectLevel(List<JsonObject> result, String projectPath) {
        JsonObject claudeJson = readClaudeJsonQuietly();
        JsonObject projectConfig = claudeJson != null ? readClaudeProjectConfig(claudeJson, projectPath) : null;
        if (projectConfig == null) {
            return;
        }

        // Read project-level disabled servers list
        Set<String> disabledServers = new HashSet<>();
        if (projectConfig.has("disabledMcpServers") && projectConfig.get("disabledMcpServers").isJsonArray()) {
            JsonArray projectDisabledArray = projectConfig.getAsJsonArray("disabledMcpServers");
            for (JsonElement elem : projectDisabledArray) {
                if (elem.isJsonPrimitive()) {
                    disabledServers.add(elem.getAsString());
                }
            }
        }

        // Project-level servers override same-id global entries; project-only entries are appended
        if (projectConfig.has("mcpServers") && projectConfig.get("mcpServers").isJsonObject()) {
            JsonObject projectMcpServers = projectConfig.getAsJsonObject("mcpServers");
            for (String serverId : projectMcpServers.keySet()) {
                JsonElement serverElem = projectMcpServers.get(serverId);
                if (!serverElem.isJsonObject()) {
                    continue;
                }
                JsonObject shaped = toFrontendServerShape(serverId, serverElem.getAsJsonObject(), disabledServers);
                boolean replaced = false;
                for (int i = 0; i < result.size(); i++) {
                    JsonObject existing = result.get(i);
                    if (existing.has("id") && existing.get("id").getAsString().equals(serverId)) {
                        result.set(i, shaped);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) {
                    result.add(shaped);
                }
            }
            LOG.info("[McpServerManager] Merged project-level MCP servers from: " + projectPath);
        }

        // Project-level disabled list also disables entries coming from the global store
        if (!disabledServers.isEmpty()) {
            for (JsonObject s : result) {
                if (s.has("id") && disabledServers.contains(s.get("id").getAsString())) {
                    s.addProperty("enabled", false);
                }
            }
            LOG.info("[McpServerManager] Merged project-level disabled servers from: " + projectPath);
        }
    }

    /**
     * 读取 ~/.claude.json 的 {@code projects.<projectPath>} 段;projectPath 为 null 或段缺失返回 null。
     */
    private JsonObject readClaudeProjectConfig(JsonObject claudeJson, String projectPath) {
        if (projectPath == null
                || !claudeJson.has("projects") || !claudeJson.get("projects").isJsonObject()) {
            return null;
        }
        JsonObject projects = claudeJson.getAsJsonObject("projects");
        if (!projects.has(projectPath) || !projects.get(projectPath).isJsonObject()) {
            return null;
        }
        return projects.getAsJsonObject(projectPath);
    }

    /**
     * 读取并解析 ~/.claude.json;文件缺失或任何读取 / 解析失败返回 null(仅 LOG.warn)。
     */
    private JsonObject readClaudeJsonQuietly() {
        try {
            String homeDir = NodeDetector.resolveHomeForFileOps();
            File claudeJsonFile = Paths.get(homeDir, ".claude.json").toFile();
            if (!claudeJsonFile.exists()) {
                return null;
            }
            try (FileReader reader = new FileReader(claudeJsonFile, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Failed to read ~/.claude.json: " + e.getMessage());
            return null;
        }
    }

    /**
     * 把 claude 原生 server spec 转前端嵌套形状:确保 id / name 字段,把 type / command / args / env 等
     * 包装进 server 字段,按禁用表置 enabled。
     */
    private static JsonObject toFrontendServerShape(String serverId, JsonObject server, Set<String> disabledServers) {
        JsonObject shaped = server.deepCopy();

        // Ensure id and name fields exist
        if (!shaped.has("id")) {
            shaped.addProperty("id", serverId);
        }
        if (!shaped.has("name")) {
            shaped.addProperty("name", serverId);
        }

        // Wrap type, command, args, env, etc. into the server field
        if (!shaped.has("server")) {
            JsonObject serverSpec = new JsonObject();

            // Copy all fields to the server spec (except special fields)
            Set<String> excludedFields = new HashSet<>();
            excludedFields.add("id");
            excludedFields.add("name");
            excludedFields.add("enabled");
            excludedFields.add("apps");
            excludedFields.add("server");

            for (String key : shaped.keySet()) {
                if (!excludedFields.contains(key)) {
                    serverSpec.add(key, shaped.get(key));
                }
            }

            shaped.add("server", serverSpec);
        }

        // Set enabled/disabled status
        shaped.addProperty("enabled", !disabledServers.contains(serverId));
        return shaped;
    }

    /**
     * claude 原生写:~/.claude.json 存在时外科手术式 upsert(merge 现有 spec + 维护
     * global / project-level disabledMcpServers + syncMcpToClaudeSettings);文件缺失跳过,
     * 任何失败仅 LOG.warn(write-through 语义,不影响全局 SSOT 主流程)。
     */
    private void upsertClaudeNativeMcpServer(JsonObject server, String serverId, boolean isEnabled,
                                             String projectPath) {
        try {
            String homeDir = NodeDetector.resolveHomeForFileOps();
            Path claudeJsonPath = Paths.get(homeDir, ".claude.json");
            File claudeJsonFile = claudeJsonPath.toFile();

            if (!claudeJsonFile.exists()) {
                return;
            }

            try (FileReader reader = new FileReader(claudeJsonFile, StandardCharsets.UTF_8)) {
                JsonObject claudeJson = JsonParser.parseReader(reader).getAsJsonObject();

                // Ensure mcpServers object exists
                if (!claudeJson.has("mcpServers") || !claudeJson.get("mcpServers").isJsonObject()) {
                    claudeJson.add("mcpServers", new JsonObject());
                }
                JsonObject mcpServers = claudeJson.getAsJsonObject("mcpServers");

                // Extract server spec
                JsonObject serverSpec;
                if (server.has("server") && server.get("server").isJsonObject()) {
                    serverSpec = server.getAsJsonObject("server").deepCopy();
                } else {
                    serverSpec = new JsonObject();
                }

                // If the server already exists, merge with existing config (preserve fields not specified in new config)
                if (mcpServers.has(serverId) && mcpServers.get(serverId).isJsonObject()) {
                    JsonObject existingSpec = mcpServers.getAsJsonObject(serverId).deepCopy();
                    // Merge new config onto existing config (new values override matching fields)
                    for (String key : serverSpec.keySet()) {
                        existingSpec.add(key, serverSpec.get(key));
                    }
                    serverSpec = existingSpec;
                }

                // Update or add the server
                mcpServers.add(serverId, serverSpec);

                // Update the disabledMcpServers list
                if (!claudeJson.has("disabledMcpServers") || !claudeJson.get("disabledMcpServers").isJsonArray()) {
                    claudeJson.add("disabledMcpServers", new JsonArray());
                }
                JsonArray disabledArray = claudeJson.getAsJsonArray("disabledMcpServers");

                if (projectPath == null) {
                    JsonArray newDisabled = new JsonArray();
                    for (JsonElement elem : disabledArray) {
                        if (!elem.getAsString().equals(serverId)) {
                            newDisabled.add(elem);
                        }
                    }
                    if (!isEnabled) {
                        newDisabled.add(serverId);
                    }
                    claudeJson.add("disabledMcpServers", newDisabled);
                } else if (isEnabled) {
                    JsonArray newDisabled = new JsonArray();
                    for (JsonElement elem : disabledArray) {
                        if (!elem.getAsString().equals(serverId)) {
                            newDisabled.add(elem);
                        }
                    }
                    claudeJson.add("disabledMcpServers", newDisabled);
                }

                if (projectPath != null) {
                    if (!claudeJson.has("projects") || !claudeJson.get("projects").isJsonObject()) {
                        claudeJson.add("projects", new JsonObject());
                    }
                    JsonObject projects = claudeJson.getAsJsonObject("projects");
                    if (!projects.has(projectPath) || !projects.get(projectPath).isJsonObject()) {
                        projects.add(projectPath, new JsonObject());
                    }
                    JsonObject projectConfig = projects.getAsJsonObject(projectPath);
                    if (!projectConfig.has("disabledMcpServers") || !projectConfig.get("disabledMcpServers").isJsonArray()) {
                        projectConfig.add("disabledMcpServers", new JsonArray());
                    }
                    JsonArray projectDisabledArray = projectConfig.getAsJsonArray("disabledMcpServers");

                    JsonArray newProjectDisabled = new JsonArray();
                    for (JsonElement elem : projectDisabledArray) {
                        if (!elem.getAsString().equals(serverId)) {
                            newProjectDisabled.add(elem);
                        }
                    }
                    if (!isEnabled) {
                        newProjectDisabled.add(serverId);
                    }
                    projectConfig.add("disabledMcpServers", newProjectDisabled);
                }

                // Write back to file
                try (FileWriter writer = new FileWriter(claudeJsonFile, StandardCharsets.UTF_8)) {
                    gson.toJson(claudeJson, writer);
                    writer.flush();  // Ensure data is fully flushed to disk
                }

                LOG.info("[McpServerManager] Upserted MCP server in ~/.claude.json: " + serverId
                                 + " (enabled: " + isEnabled + ", projectPath: " + (projectPath != null ? projectPath : "(global)") + ")");

                // Sync to settings.json (after file write is complete)
                try {
                    claudeSettingsManager.syncMcpToClaudeSettings();
                } catch (Exception syncError) {
                    LOG.warn("[McpServerManager] Failed to sync MCP to settings.json: " + syncError.getMessage());
                    // Sync failure should not affect the main operation
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Error updating ~/.claude.json: " + e.getMessage());
        }
    }

    /**
     * Delete an MCP server.
     * Deletes from the global store (~/.codemoss/config.json mcpServers, the SSOT),
     * then best-effort from the providers' native config files.
     *
     * @return true if the server was removed from any store
     */
    public boolean deleteMcpServer(String serverId) throws IOException {
        // 一次性迁移:把三家原生配置导入全局 SSOT(仅首次,见标记键)
        migrateNativeMcpServersToGlobal();

        boolean removed = false;

        // 1. 全局 SSOT 删除
        JsonObject config = configReader.apply(null);
        if (config.has("mcpServers") && config.get("mcpServers").isJsonArray()) {
            JsonArray servers = config.getAsJsonArray("mcpServers");
            JsonArray newServers = new JsonArray();

            boolean removedFromGlobal = false;
            for (JsonElement elem : servers) {
                JsonObject s = elem.getAsJsonObject();
                if (s.has("id") && s.get("id").getAsString().equals(serverId)) {
                    removedFromGlobal = true;
                } else {
                    newServers.add(s);
                }
            }

            if (removedFromGlobal) {
                config.add("mcpServers", newServers);
                configWriter.accept(config);
                LOG.info("[McpServerManager] Deleted MCP server from global store (~/.codemoss/config.json): "
                                 + serverId);
            }
            removed |= removedFromGlobal;
        }

        // 2. claude 原生删除(best-effort,保留 syncMcpToClaudeSettings)
        removed |= deleteClaudeNativeMcpServer(serverId);

        // 3. codex / opencode 原生写穿删除(best-effort)
        removed |= writeThrough.delete(serverId);

        return removed;
    }

    /**
     * claude 原生删除:~/.claude.json 存在且含该 server 时删除(并清理 disabledMcpServers +
     * syncMcpToClaudeSettings);任何失败仅 LOG.warn。
     *
     * @return 确实从 ~/.claude.json 删除了条目则 true
     */
    private boolean deleteClaudeNativeMcpServer(String serverId) {
        try {
            String homeDir = NodeDetector.resolveHomeForFileOps();
            Path claudeJsonPath = Paths.get(homeDir, ".claude.json");
            File claudeJsonFile = claudeJsonPath.toFile();

            if (!claudeJsonFile.exists()) {
                return false;
            }

            try (FileReader reader = new FileReader(claudeJsonFile, StandardCharsets.UTF_8)) {
                JsonObject claudeJson = JsonParser.parseReader(reader).getAsJsonObject();

                if (claudeJson.has("mcpServers") && claudeJson.get("mcpServers").isJsonObject()) {
                    JsonObject mcpServers = claudeJson.getAsJsonObject("mcpServers");

                    if (mcpServers.has(serverId)) {
                        // Delete the server
                        mcpServers.remove(serverId);

                        // Also remove from disabledMcpServers (if present)
                        if (claudeJson.has("disabledMcpServers") && claudeJson.get("disabledMcpServers").isJsonArray()) {
                            JsonArray disabledServers = claudeJson.getAsJsonArray("disabledMcpServers");
                            JsonArray newDisabled = new JsonArray();
                            for (JsonElement elem : disabledServers) {
                                if (!elem.getAsString().equals(serverId)) {
                                    newDisabled.add(elem);
                                }
                            }
                            claudeJson.add("disabledMcpServers", newDisabled);
                        }

                        // Write back to file
                        try (FileWriter writer = new FileWriter(claudeJsonFile, StandardCharsets.UTF_8)) {
                            gson.toJson(claudeJson, writer);
                            writer.flush();  // Ensure data is fully flushed to disk
                        }

                        LOG.info("[McpServerManager] Deleted MCP server from ~/.claude.json: " + serverId);

                        // Sync to settings.json (after file write is complete)
                        try {
                            claudeSettingsManager.syncMcpToClaudeSettings();
                        } catch (Exception syncError) {
                            LOG.warn("[McpServerManager] Failed to sync MCP to settings.json: " + syncError.getMessage());
                        }

                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[McpServerManager] Error deleting from ~/.claude.json: " + e.getMessage());
        }
        return false;
    }

    /**
     * Validate MCP server configuration.
     */
    public Map<String, Object> validateMcpServer(JsonObject server) {
        List<String> errors = new ArrayList<>();

        if (!server.has("name") || server.get("name").getAsString().isEmpty()) {
            errors.add("Server name must not be empty");
        }

        if (server.has("server")) {
            JsonObject serverSpec = server.getAsJsonObject("server");
            String type = serverSpec.has("type") ? serverSpec.get("type").getAsString() : "stdio";

            if (CommonConstants.MCP_TRANSPORT_STDIO.equals(type)) {
                if (!serverSpec.has("command") || serverSpec.get("command").getAsString().isEmpty()) {
                    errors.add("Command must not be empty");
                }
            } else if (CommonConstants.MCP_TRANSPORT_HTTP.equals(type) || CommonConstants.MCP_TRANSPORT_SSE.equals(type)) {
                if (!serverSpec.has("url") || serverSpec.get("url").getAsString().isEmpty()) {
                    errors.add("URL must not be empty");
                } else {
                    String url = serverSpec.get("url").getAsString();
                    try {
                        new java.net.URI(url).toURL();
                    } catch (Exception e) {
                        errors.add("Invalid URL format");
                    }
                }
            } else {
                errors.add("Unsupported connection type: " + type);
            }
        } else {
            errors.add("Missing server configuration details");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        return result;
    }
}
