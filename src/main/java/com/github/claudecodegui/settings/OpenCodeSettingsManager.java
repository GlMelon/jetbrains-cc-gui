package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpCommandRiskEvaluator;
import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.mcp.McpInstallRejectedException;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * OpenCode 原生配置文件 ({@code ~/.config/opencode/opencode.json}) 的外科手术式读写器。
 *
 * <p>与 {@link CodexSettingsManager} 对称:codex 把活跃 provider 写入 {@code ~/.codex/auth.json} +
 * {@code config.toml};opencode 的原生配置为单一 JSON,结构为
 * {@code {provider:{...}, mcp:{...}, permission:{...}, ...}}。本类只<strong>外科手术式</strong>
 * 写入 {@code provider} 段,严格保留 {@code mcp}/{@code permission}/skill 等其他段(用户配置不丢失),
 * 这正是用户「外科手术式合并」决策的落点。
 *
 * <p>路径解析与 {@link com.github.claudecodegui.config.OpenCodeConfigReader} 同源
 * ({@link PlatformUtils#getHomeDirectory()}),保证「写回的就是读取的同一个文件」。
 *
 * <p>安全(J):写入含 apiKey 等敏感凭据,采用临时文件 + {@code ATOMIC_MOVE} + 0600 加固(范式
 * 同 {@link CodexSettingsManager#writeStringAtomically} 的私有实现)。
 *
 * <p>provider 段语义:opencode 原生 {@code provider.<key>} = {@code {name, models:{...}, apiKey?, baseURL?, ...}},
 * 不含插件专属字段。SSOT provider 对象(见 {@link OpenCodeProviderManager})是半 schema-less 的,
 * 可携带任意 opencode 原生字段;本类合并时仅剥离插件专属字段({@link #PLUGIN_ONLY_FIELDS})。
 */
public class OpenCodeSettingsManager {
    private static final Logger LOG = Logger.getInstance(OpenCodeSettingsManager.class);

    /**
     * 插件专属字段:SSOT 中存在但 opencode 原生 provider 对象不应包含的字段。
     * 合并写入时剥离,避免污染原生配置。
     */
    private static final Set<String> PLUGIN_ONLY_FIELDS = Set.of("id", "isActive", "createdAt");

    private final Gson gson;

    public OpenCodeSettingsManager(Gson gson) {
        this.gson = gson;
    }

    /**
     * 解析 opencode.json 路径。与 {@link com.github.claudecodegui.config.OpenCodeConfigReader} 同源,
     * 用 {@link PlatformUtils#getHomeDirectory()} 解析真实 OS home。
     */
    public Path getOpenCodeJsonPath() {
        String home = PlatformUtils.getHomeDirectory();
        if (home == null || home.isBlank()) {
            return null;
        }
        return Paths.get(home, CommonConstants.DIR_OPENCODE, CommonConstants.FILE_OPENCODE_JSON);
    }

    /**
     * opencode 配置目录 ({@code ~/.config/opencode})。
     */
    public Path getOpenCodeDir() {
        String home = PlatformUtils.getHomeDirectory();
        if (home == null || home.isBlank()) {
            return null;
        }
        return Paths.get(home, CommonConstants.DIR_OPENCODE);
    }

    /**
     * 确保配置目录存在。
     */
    public void ensureOpenCodeDirectory() throws IOException {
        Path dir = getOpenCodeDir();
        if (dir == null) {
            throw new IOException("Cannot resolve opencode config directory (home directory is blank)");
        }
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            LOG.info("[OpenCodeSettingsManager] Created opencode config directory: " + dir);
        }
    }

    /**
     * 读取完整原生 opencode.json。文件不存在返回空 JsonObject(非 null)。
     */
    public JsonObject readNativeConfig() throws IOException {
        Path configPath = getOpenCodeJsonPath();
        if (configPath == null || !Files.exists(configPath)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Failed to read opencode.json: " + e.getMessage(), e);
        }
    }

    /**
     * 读取原生 {@code provider} 段。无则返回空 JsonObject。
     */
    public JsonObject readNativeProviderSection() throws IOException {
        JsonObject root = readNativeConfig();
        if (root.has("provider") && root.get("provider").isJsonObject()) {
            return root.getAsJsonObject("provider");
        }
        return new JsonObject();
    }

    /**
     * 读取当前 opencode 配置(供「从配置文件授权」本地模式展示)。
     * 返回原生 {@code provider} 段的简化快照。
     */
    public JsonObject getCurrentOpenCodeConfig() throws IOException {
        return readNativeProviderSection();
    }

    /**
     * 外科手术式合并:用 SSOT 管理的 providers <strong>替换</strong> opencode.json 的 {@code provider} 段,
     * 严格保留 {@code mcp}/{@code permission}/skill 等其他所有段。
     *
     * <p>合并语义(用户「外科手术式合并」决策):
     * <ul>
     *   <li>{@code managedProviders} 是 SSOT opencode 段 providers map 的快照
     *       (key=providerKey, value=半 schema-less provider 对象)。</li>
     *   <li>对每个 entry,剥离 {@link #PLUGIN_ONLY_FIELDS} 后写入原生 {@code provider.<key>}。</li>
     *   <li>原 {@code provider} 段被整体替换(管理模式 = SSOT 为 provider 段唯一真相源);
     *       首次导入保护(见 {@link OpenCodeProviderManager#importNativeProvidersIfEmpty()})确保
     *       原生 provider 先入 SSOT,避免覆盖丢失用户配置。</li>
     *   <li>原子写入(临时文件 + {@code ATOMIC_MOVE} + 0600),防消费者观测到半写文件。</li>
     * </ul>
     *
     * @param managedProviders SSOT 管理的 providers map(providerKey → provider 对象)
     */
    public void writeProviderSectionSurgically(JsonObject managedProviders) throws IOException {
        Path configPath = getOpenCodeJsonPath();
        if (configPath == null) {
            throw new IOException("Cannot resolve opencode.json path (home directory is blank)");
        }
        ensureOpenCodeDirectory();

        // 读现存完整配置(保留 mcp/permission/skill 等段);不存在则从空开始
        JsonObject root;
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                LOG.warn("[OpenCodeSettingsManager] Existing opencode.json unparseable, rebuilding from managed providers: " + e.getMessage());
                root = new JsonObject();
            }
        } else {
            root = new JsonObject();
        }

        // 构造新的 provider 段:剥离插件专属字段
        JsonObject nativeProviderSection = toNativeProviderSection(managedProviders);
        root.add("provider", nativeProviderSection);

        writeStringAtomically(configPath, gson.toJson(root));
        LOG.info("[OpenCodeSettingsManager] Surgically merged provider section (" + nativeProviderSection.size()
                + " providers) into opencode.json, other sections preserved");
    }

    /**
     * Upsert(新增或更新)一个 MCP server 到 opencode.json 的 {@code mcp} 段(对称
     * {@link CodexMcpServerManager#upsertMcpServer},外科手术式只替换 {@code mcp.<id>} 条目)。
     *
     * <p>输入是前端 McpServer 嵌套形状({@code {id, server:{command:string, args, env, url, type}}}),
     * 经 {@link #toNativeMcpEntry} 转为 OpenCode 原生条目({@code command} 数组 + {@code environment})。
     *
     * <p>SEC-01 安全闸门:写盘前经 {@link McpCommandRiskEvaluator} 从 command/args 重算风险,危险则抛
     * {@link McpInstallRejectedException} 拒绝落盘(与 Codex/Claude 入口对称)。
     *
     * <p>⚠️与 {@link #writeProviderSectionSurgically} 的 rebuild-from-managed 行为刻意不同:mcp 段是
     * 用户手写文件,既有 opencode.json 解析失败时 <strong>拒写</strong>(readNativeConfig 抛
     * IOException 上传),绝不在解析失败时从空重建——否则用户手写的 mcp/其他段被清空。
     */
    public void upsertMcpServer(JsonObject server) throws IOException {
        if (server == null || !server.has("id") || server.get("id").getAsString().isBlank()) {
            throw new IllegalArgumentException("Server must have a non-blank id");
        }
        String serverId = server.get("id").getAsString();

        // SEC-01:前端嵌套 serverSpec(command:string + args)与 Codex 闸门输入同形状,直接复用
        JsonObject serverSpec = (server.has("server") && server.get("server").isJsonObject())
                ? server.getAsJsonObject("server") : null;
        if (McpCommandRiskEvaluator.shouldReject(serverSpec)) {
            throw new McpInstallRejectedException(
                    "MCP server '" + serverId + "' rejected: " + McpCommandRiskEvaluator.explainRisk(serverSpec));
        }

        Path configPath = requireConfigPath();
        ensureOpenCodeDirectory();
        JsonObject root = readNativeConfig();
        JsonObject mcpSection = root.has("mcp") && root.get("mcp").isJsonObject()
                ? root.getAsJsonObject("mcp") : new JsonObject();
        mcpSection.add(serverId, toNativeMcpEntry(server));
        root.add("mcp", mcpSection);
        writeStringAtomically(configPath, gson.toJson(root));
        LOG.info("[OpenCodeSettingsManager] Upserted OpenCode MCP server: " + serverId);
    }

    /**
     * 从 opencode.json 的 {@code mcp} 段删除一个 server(对称 {@link CodexMcpServerManager#deleteMcpServer})。
     *
     * @return serverId 不存在(或无 mcp 段)返回 false,文件不变
     */
    public boolean deleteMcpServer(String serverId) throws IOException {
        Path configPath = requireConfigPath();
        if (!Files.exists(configPath)) {
            return false;
        }
        JsonObject root = readNativeConfig();
        if (!root.has("mcp") || !root.get("mcp").isJsonObject()) {
            return false;
        }
        JsonObject mcpSection = root.getAsJsonObject("mcp");
        if (!mcpSection.has(serverId)) {
            return false;
        }
        mcpSection.remove(serverId);
        root.add("mcp", mcpSection);
        writeStringAtomically(configPath, gson.toJson(root));
        LOG.info("[OpenCodeSettingsManager] Deleted OpenCode MCP server: " + serverId);
        return true;
    }

    /**
     * 前端 McpServer 嵌套形状 → OpenCode 原生 mcp 条目(逆向 {@code OpenCodeMcpServerActionHandlers.
     * adaptToServerShape}):{@code command:string+args} 合并为数组、{@code env}→{@code environment}、
     * 前端 transport({@code stdio}/{@code sse}/{@code http})折叠回 {@code local}/{@code remote}。
     * 前端专属字段(apps/name/description/tags 等)不写入。
     */
    private JsonObject toNativeMcpEntry(JsonObject server) {
        JsonObject entry = new JsonObject();
        JsonObject spec = (server.has("server") && server.get("server").isJsonObject())
                ? server.getAsJsonObject("server") : new JsonObject();

        String type = spec.has("type") ? spec.get("type").getAsString() : null;
        boolean remote = CommonConstants.MCP_TRANSPORT_HTTP.equals(type)
                || CommonConstants.MCP_TRANSPORT_SSE.equals(type)
                || (!spec.has("command") && spec.has("url"));
        entry.addProperty("type", remote ? "remote" : "local");

        if (spec.has("command") && spec.get("command").isJsonPrimitive()) {
            JsonArray argv = new JsonArray();
            argv.add(spec.get("command").getAsString());
            if (spec.has("args") && spec.get("args").isJsonArray()) {
                for (JsonElement arg : spec.getAsJsonArray("args")) {
                    argv.add(arg);
                }
            }
            entry.add("command", argv);
        }
        if (spec.has(McpGatewayConstants.KEY_ENV) && spec.get(McpGatewayConstants.KEY_ENV).isJsonObject()) {
            entry.add(McpGatewayConstants.KEY_ENVIRONMENT_OPENCODE, spec.getAsJsonObject(McpGatewayConstants.KEY_ENV));
        }
        if (spec.has(McpGatewayConstants.KEY_URL) && spec.get(McpGatewayConstants.KEY_URL).isJsonPrimitive()) {
            entry.addProperty(McpGatewayConstants.KEY_URL, spec.get(McpGatewayConstants.KEY_URL).getAsString());
        }
        if (server.has("enabled")) {
            entry.addProperty("enabled", server.get("enabled").getAsBoolean());
        }
        return entry;
    }

    private Path requireConfigPath() throws IOException {
        Path configPath = getOpenCodeJsonPath();
        if (configPath == null) {
            throw new IOException("Cannot resolve opencode.json path (home directory is blank)");
        }
        return configPath;
    }

    /**
     * 把 SSOT providers map 转换为 opencode 原生 provider 段(剥离插件专属字段)。
     */
    private JsonObject toNativeProviderSection(JsonObject managedProviders) {
        JsonObject nativeSection = new JsonObject();
        if (managedProviders == null) {
            return nativeSection;
        }
        for (Map.Entry<String, JsonElement> entry : managedProviders.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject ssotProvider = entry.getValue().getAsJsonObject();
            nativeSection.add(key, toNativeProviderEntry(ssotProvider));
        }
        return nativeSection;
    }

    /**
     * 剥离插件专属字段,返回 opencode 原生 provider 对象。
     */
    private JsonObject toNativeProviderEntry(JsonObject ssotProvider) {
        JsonObject nativeEntry = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : ssotProvider.entrySet()) {
            String key = entry.getKey();
            if (PLUGIN_ONLY_FIELDS.contains(key)) {
                continue;
            }
            nativeEntry.add(key, entry.getValue());
        }
        return nativeEntry;
    }

    /**
     * 原子写入助手:写临时文件后原子替换目标,防消费者观测到半写文件。
     * 范式同 {@link CodexSettingsManager} 的私有 {@code writeStringAtomically}。
     */
    private void writeStringAtomically(Path target, String content) throws IOException {
        ensureOpenCodeDirectory();

        Path parent = target.getParent();
        if (parent == null) {
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return;
        }

        String prefix = target.getFileName() != null ? target.getFileName() + "-" : "opencode-";
        Path tmp = Files.createTempFile(parent, prefix, ".tmp");
        try {
            // 安全(J):写含 apiKey 等敏感凭据,先加 0600 再写,原子移动后权限保留到目标。
            hardenFilePermissions(tmp);
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception e) {
                LOG.debug("[OpenCodeSettingsManager] Failed to cleanup temp file: " + tmp + " (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * 尽力把文件限制为属主读写(0600)。非 POSIX 文件系统(如 Windows)无操作,由用户主目录 ACL 保护。
     */
    private static void hardenFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            LOG.debug("[OpenCodeSettingsManager] Could not set 0600 on " + path + ": " + e.getMessage());
        }
    }

    /**
     * 仅供测试/未来扩展暴露的插件专属字段集合(不可变)。
     */
    static Set<String> pluginOnlyFieldsView() {
        return new HashSet<>(PLUGIN_ONLY_FIELDS);
    }
}
