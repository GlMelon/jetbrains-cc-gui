package com.github.claudecodegui.settings;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.model.DeleteResult;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * OpenCode Provider Manager — 管理 {@code ~/.codemoss/config.json} 的 opencode 段(SSOT),
 * 并把活跃(管理型)provider 经 {@link OpenCodeSettingsManager} 外科手术式合并到
 * {@code ~/.config/opencode/opencode.json}。
 *
 * <p>对称 {@link CodexProviderManager}(codex SSOT 段 + 写 {@code ~/.codex/}):
 * <ul>
 *   <li>SSOT 段结构镜像 codex:{@code {providers:{}, current, providerOrder, localConfigAuthorized}}。</li>
 *   <li>provider 对象半 schema-less:至少 {@code id}/{@code name},可携带任意 opencode 原生字段
 *       ({@code apiKey}/{@code baseURL}/{@code models} 等),合并时仅剥离插件专属字段。</li>
 *   <li>特殊 id {@link #OPENCODE_LOCAL_CONFIG_PROVIDER_ID} =「从配置文件授权」本地模式(对称 codex
 *       的 {@code __codex_cli_login__}):该模式以 opencode.json 原生为权威(只读),不合并写入。</li>
 *   <li>{@link #importNativeProvidersIfEmpty()} 首次导入保护:管理模式首次接触时把原生 opencode.json
 *       的 provider 导入 SSOT,避免合并覆盖丢失用户配置。</li>
 * </ul>
 *
 * <p>模型注册表集成:管理型 provider 经合并写入 opencode.json 后,现有
 * {@link com.github.claudecodegui.config.OpenCodeConfigReader#readModels()} 会自动读回其模型
 * (opencode.json 是模型 SSOT),故无需独立可写 registry。
 */
public class OpenCodeProviderManager {
    private static final Logger LOG = Logger.getInstance(OpenCodeProviderManager.class);
    private static final String BACKUP_FILE_NAME = "config.json.bak";

    /** 「从配置文件授权」本地模式虚拟 provider id(对称 codex 的 {@code __codex_cli_login__})。 */
    public static final String OPENCODE_LOCAL_CONFIG_PROVIDER_ID = "__opencode_local_config__";

    private final Gson gson;
    private final Function<Void, JsonObject> configReader;
    private final Consumer<JsonObject> configWriter;
    private final ConfigPathManager pathManager;
    private final OpenCodeSettingsManager openCodeSettingsManager;

    public OpenCodeProviderManager(
            Gson gson,
            Function<Void, JsonObject> configReader,
            Consumer<JsonObject> configWriter,
            ConfigPathManager pathManager,
            OpenCodeSettingsManager openCodeSettingsManager) {
        this.gson = gson;
        this.configReader = configReader;
        this.configWriter = configWriter;
        this.pathManager = pathManager;
        this.openCodeSettingsManager = openCodeSettingsManager;
    }

    /**
     * 取所有 OpenCode provider(含本地配置虚拟 provider 置顶)。
     * 触发首次导入保护:SSOT providers 为空时从原生 opencode.json 导入。
     */
    public List<JsonObject> getOpenCodeProviders() {
        try {
            importNativeProvidersIfEmpty();
        } catch (IOException e) {
            LOG.warn("[OpenCodeProviderManager] First-import guard failed: " + e.getMessage());
        }

        JsonObject config = configReader.apply(null);
        List<JsonObject> result = new ArrayList<>();

        String currentId = null;
        if (config.has(ProviderType.OPENCODE.value()) && config.get(ProviderType.OPENCODE.value()).isJsonObject()) {
            JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
            if (opencode.has("current") && !opencode.get("current").isJsonNull()) {
                currentId = opencode.get("current").getAsString();
            }
        }
        boolean localConfigAuthorized = isOpenCodeLocalConfigAuthorized(config);

        // 本地配置虚拟 provider 置顶
        result.add(createLocalConfigProviderObject(
                OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(currentId) && localConfigAuthorized));

        if (!config.has(ProviderType.OPENCODE.value())) {
            return result;
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        if (!opencode.has("providers")) {
            return result;
        }

        JsonObject providers = opencode.getAsJsonObject("providers");

        List<String> orderedIds = ProviderOrderHelper.getProviderOrder(opencode, providers.keySet());

        for (String id : orderedIds) {
            if (providers.has(id)) {
                JsonObject provider = providers.getAsJsonObject(id).deepCopy();
                if (!provider.has("id")) {
                    provider.addProperty("id", id);
                }
                provider.addProperty("isActive", id.equals(currentId));
                result.add(provider);
            }
        }

        return result;
    }

    /**
     * 保存 provider 排序顺序。
     */
    public void saveProviderOrder(List<String> orderedIds) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(ProviderType.OPENCODE.value())) {
            JsonObject opencode = new JsonObject();
            opencode.add("providers", new JsonObject());
            opencode.addProperty("current", "");
            config.add(ProviderType.OPENCODE.value(), opencode);
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        ProviderOrderHelper.setProviderOrder(opencode, orderedIds);

        configWriter.accept(config);
        LOG.info("[OpenCodeProviderManager] Saved provider order: " + orderedIds);
    }

    /**
     * 取当前活跃 OpenCode provider。
     */
    public JsonObject getActiveOpenCodeProvider() {
        JsonObject config = configReader.apply(null);

        if (!config.has(ProviderType.OPENCODE.value())) {
            return null;
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        if (!opencode.has("current")) {
            return null;
        }

        String currentId = opencode.get("current").getAsString();
        if (currentId == null || currentId.isEmpty()) {
            return null;
        }

        // 本地配置虚拟 provider
        if (OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(currentId)) {
            if (!isOpenCodeLocalConfigAuthorized(config)) {
                return null;
            }
            return createLocalConfigProviderObject(true);
        }

        if (!opencode.has("providers")) {
            return null;
        }

        JsonObject providers = opencode.getAsJsonObject("providers");

        if (providers.has(currentId)) {
            JsonObject provider = providers.getAsJsonObject(currentId);
            if (!provider.has("id")) {
                provider.addProperty("id", currentId);
            }
            provider.addProperty("isActive", true);
            return provider;
        }

        return null;
    }

    /**
     * 新增 OpenCode provider。
     */
    public void addOpenCodeProvider(JsonObject provider) throws IOException {
        if (!provider.has("id")) {
            throw new IllegalArgumentException("Provider must have an id");
        }

        JsonObject config = configReader.apply(null);

        if (!config.has(ProviderType.OPENCODE.value())) {
            JsonObject opencode = new JsonObject();
            opencode.add("providers", new JsonObject());
            opencode.addProperty("current", "");
            config.add(ProviderType.OPENCODE.value(), opencode);
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        JsonObject providers = opencode.getAsJsonObject("providers");

        String id = provider.get("id").getAsString();

        if (providers.has(id)) {
            throw new IllegalArgumentException("Provider with id '" + id + "' already exists");
        }

        if (!provider.has("createdAt")) {
            provider.addProperty("createdAt", System.currentTimeMillis());
        }

        providers.add(id, provider);

        configWriter.accept(config);
        LOG.info("[OpenCodeProviderManager] Added provider: " + id);
    }

    /**
     * 更新现存 OpenCode provider(合并 updates,id 不可改)。
     */
    public void updateOpenCodeProvider(String id, JsonObject updates) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(ProviderType.OPENCODE.value())) {
            throw new IllegalArgumentException("No opencode configuration found");
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        JsonObject providers = opencode.getAsJsonObject("providers");

        if (!providers.has(id)) {
            throw new IllegalArgumentException("Provider with id '" + id + "' not found");
        }

        JsonObject provider = providers.getAsJsonObject(id);

        for (String key : updates.keySet()) {
            if (key.equals("id")) {
                continue;
            }
            if (updates.get(key).isJsonNull()) {
                provider.remove(key);
            } else {
                provider.add(key, updates.get(key));
            }
        }

        configWriter.accept(config);
        LOG.info("[OpenCodeProviderManager] Updated provider: " + id);
    }

    /**
     * 删除 OpenCode provider。删活跃 provider 时自动切到首个可用;备份+失败回滚。
     */
    public DeleteResult deleteOpenCodeProvider(String id) {
        Path configFilePath = null;
        Path backupFilePath = null;

        try {
            JsonObject config = configReader.apply(null);
            configFilePath = pathManager.getConfigFilePath();
            backupFilePath = pathManager.getConfigDir().resolve(BACKUP_FILE_NAME);

            if (!config.has(ProviderType.OPENCODE.value())) {
                return DeleteResult.failure(
                        DeleteResult.ErrorType.FILE_NOT_FOUND,
                        "No opencode configuration found",
                        configFilePath.toString(),
                        "Please add at least one OpenCode provider first"
                );
            }

            JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
            JsonObject providers = opencode.getAsJsonObject("providers");

            if (!providers.has(id)) {
                return DeleteResult.failure(
                        DeleteResult.ErrorType.FILE_NOT_FOUND,
                        "Provider with id '" + id + "' not found",
                        null,
                        "Please check if the provider ID is correct"
                );
            }

            try {
                Files.copy(configFilePath, backupFilePath, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("[OpenCodeProviderManager] Created backup: " + backupFilePath);
            } catch (IOException e) {
                LOG.warn("[OpenCodeProviderManager] Warning: Failed to create backup: " + e.getMessage());
            }

            providers.remove(id);

            String currentId = opencode.has("current") ? opencode.get("current").getAsString() : null;
            if (id.equals(currentId)) {
                if (providers.size() > 0) {
                    String firstKey = providers.keySet().iterator().next();
                    opencode.addProperty("current", firstKey);
                    LOG.info("[OpenCodeProviderManager] Switched to provider: " + firstKey);
                } else {
                    opencode.addProperty("current", "");
                    LOG.info("[OpenCodeProviderManager] No remaining providers");
                }
            }

            ProviderOrderHelper.removeFromOrder(opencode, id);

            configWriter.accept(config);
            LOG.info("[OpenCodeProviderManager] Deleted provider: " + id);

            // 删除影响 opencode.json provider 段 → 合并刷新
            applyManagedProvidersToNative();

            try {
                Files.deleteIfExists(backupFilePath);
            } catch (IOException e) {
                // Ignore backup deletion failure
            }

            return DeleteResult.success(id);

        } catch (Exception e) {
            if (backupFilePath != null && configFilePath != null) {
                try {
                    if (Files.exists(backupFilePath)) {
                        Files.copy(backupFilePath, configFilePath, StandardCopyOption.REPLACE_EXISTING);
                        LOG.info("[OpenCodeProviderManager] Restored from backup after failure");
                    }
                } catch (IOException restoreEx) {
                    LOG.warn("[OpenCodeProviderManager] Failed to restore backup: " + restoreEx.getMessage());
                }
            }

            return DeleteResult.fromException(e, configFilePath != null ? configFilePath.toString() : null);
        }
    }

    /**
     * 切换 OpenCode provider。管理型 provider 切换后触发外科手术式合并刷新;
     * 本地配置模式仅置 current(以 opencode.json 为权威)。
     */
    public void switchOpenCodeProvider(String id) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(ProviderType.OPENCODE.value())) {
            JsonObject opencodeSection = new JsonObject();
            opencodeSection.add("providers", new JsonObject());
            opencodeSection.addProperty("current", "");
            config.add(ProviderType.OPENCODE.value(), opencodeSection);
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());

        if (id == null || id.trim().isEmpty()) {
            opencode.addProperty("current", "");
            configWriter.accept(config);
            LOG.info("[OpenCodeProviderManager] Cleared active provider");
            return;
        }

        // 本地配置虚拟 provider —— 无需校验 providers map
        if (!OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(id)) {
            JsonObject providers = opencode.getAsJsonObject("providers");
            if (providers == null || !providers.has(id)) {
                throw new IllegalArgumentException("Provider with id '" + id + "' not found");
            }
        }

        opencode.addProperty("current", id);
        configWriter.accept(config);
        LOG.info("[OpenCodeProviderManager] Switched to provider: " + id);

        // 管理型 provider 切换 → 合并刷新 opencode.json(本地配置模式跳过,保持原生权威)
        if (!OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(id)) {
            applyManagedProvidersToNative();
        }
    }

    /**
     * 把活跃 provider 应用到 opencode.json。管理模式(活跃为管理型 provider)时合并刷新;
     * 本地配置/空模式跳过。
     */
    public void applyActiveProviderToOpenCodeSettings() throws IOException {
        JsonObject activeProvider = getActiveOpenCodeProvider();
        if (activeProvider == null) {
            LOG.info("[OpenCodeProviderManager] No active provider to sync to opencode.json");
            return;
        }
        String activeId = activeProvider.has("id") ? activeProvider.get("id").getAsString() : "";
        if (OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(activeId)) {
            LOG.info("[OpenCodeProviderManager] Local-config mode active; opencode.json left authoritative");
            return;
        }
        applyManagedProvidersToNative();
    }

    /**
     * 取当前 opencode 原生配置(本地模式展示)。
     */
    public JsonObject getCurrentOpenCodeConfig() throws IOException {
        return openCodeSettingsManager.getCurrentOpenCodeConfig();
    }

    /**
     * 创建本地配置虚拟 provider 对象(动态生成,不入 config)。
     */
    private JsonObject createLocalConfigProviderObject(boolean isActive) {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", OPENCODE_LOCAL_CONFIG_PROVIDER_ID);
        provider.addProperty("name", ClaudeCodeGuiBundle.message("provider.opencodeLocalConfig.name"));
        provider.addProperty("isActive", isActive);
        provider.addProperty("isOpenCodeLocalConfigProvider", true);
        return provider;
    }

    /**
     * 当前活跃 provider 是否为本地配置模式。
     */
    public boolean isLocalConfigProviderActive() {
        try {
            JsonObject config = configReader.apply(null);
            if (!config.has(ProviderType.OPENCODE.value())) {
                return false;
            }
            JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
            if (!opencode.has("current")) {
                return false;
            }
            return OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(opencode.get("current").getAsString())
                    && isOpenCodeLocalConfigAuthorized(config);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isOpenCodeLocalConfigAuthorized(JsonObject config) {
        if (config == null || !config.has(ProviderType.OPENCODE.value()) || !config.get(ProviderType.OPENCODE.value()).isJsonObject()) {
            return false;
        }
        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        return opencode.has("localConfigAuthorized")
                && !opencode.get("localConfigAuthorized").isJsonNull()
                && opencode.get("localConfigAuthorized").getAsBoolean();
    }

    /**
     * 首次导入保护:SSOT opencode 段 providers 为空时,把原生 opencode.json 的 provider 段
     * 导入 SSOT(每条加 id=providerKey + name),持久化。避免首次管理模式合并覆盖丢失用户配置。
     * 文件不存在 / 无 provider 段 → 无操作。
     */
    public void importNativeProvidersIfEmpty() throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(ProviderType.OPENCODE.value())) {
            JsonObject opencode = new JsonObject();
            opencode.add("providers", new JsonObject());
            opencode.addProperty("current", "");
            config.add(ProviderType.OPENCODE.value(), opencode);
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        JsonObject providers = opencode.getAsJsonObject("providers");
        if (providers != null && providers.size() > 0) {
            return; // SSOT 已有 provider,无需导入
        }

        JsonObject nativeProviders = openCodeSettingsManager.readNativeProviderSection();
        if (nativeProviders == null || nativeProviders.size() == 0) {
            return; // 原生无 provider,无可导入
        }

        if (providers == null) {
            providers = new JsonObject();
            opencode.add("providers", providers);
        }

        int imported = 0;
        for (Map.Entry<String, JsonElement> entry : nativeProviders.entrySet()) {
            String providerKey = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject nativeProvider = entry.getValue().getAsJsonObject().deepCopy();
            // 注入插件 id/name(若原生无 name,用 key)
            nativeProvider.addProperty("id", providerKey);
            if (!nativeProvider.has("name")) {
                nativeProvider.addProperty("name", providerKey);
            }
            if (!nativeProvider.has("createdAt")) {
                nativeProvider.addProperty("createdAt", System.currentTimeMillis());
            }
            providers.add(providerKey, nativeProvider);
            imported++;
        }

        if (imported > 0) {
            configWriter.accept(config);
            LOG.info("[OpenCodeProviderManager] First-import guard imported " + imported + " native providers into SSOT");
        }
    }

    /**
     * 把 SSOT 全部管理型 provider 经外科手术式合并写入 opencode.json(管理模式 = SSOT 为 provider 段真相源)。
     */
    private void applyManagedProvidersToNative() throws IOException {
        JsonObject config = configReader.apply(null);
        if (!config.has(ProviderType.OPENCODE.value())) {
            return;
        }
        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        if (!opencode.has("providers")) {
            return;
        }
        JsonObject providers = opencode.getAsJsonObject("providers");
        if (providers.size() == 0) {
            return;
        }
        openCodeSettingsManager.writeProviderSectionSurgically(providers);
    }
}
