package com.github.claudecodegui.settings;

import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Claude、Codex 与 OpenCode Provider 配置领域 Service。
 *
 * <p>持有三类 Provider manager，并收口本地配置授权与运行时接入模式。
 * config.json 访问仅依赖 {@link ConfigStore}，Facade 只保留兼容调用面。
 */
public final class ProviderSettingsService {
    private static final Logger LOG = Logger.getInstance(ProviderSettingsService.class);

    private final ConfigStore configStore;
    private final ProviderManager providerManager;
    private final CodexProviderManager codexProviderManager;
    private final OpenCodeProviderManager openCodeProviderManager;
    private final CodexSettingsManager codexSettingsManager;

    public ProviderSettingsService(
            ConfigStore configStore,
            Gson gson,
            ConfigPathManager pathManager,
            ClaudeSettingsManager claudeSettingsManager,
            CodexSettingsManager codexSettingsManager,
            OpenCodeSettingsManager openCodeSettingsManager) {
        this.configStore = configStore;
        this.providerManager = new ProviderManager(
                gson,
                reader(configStore),
                writer(configStore),
                pathManager,
                claudeSettingsManager
        );
        this.codexProviderManager = new CodexProviderManager(
                reader(configStore),
                writer(configStore),
                pathManager,
                codexSettingsManager
        );
        this.openCodeProviderManager = new OpenCodeProviderManager(
                gson,
                reader(configStore),
                writer(configStore),
                pathManager,
                openCodeSettingsManager
        );
        this.codexSettingsManager = codexSettingsManager;
    }

    private static Function<Void, JsonObject> reader(ConfigStore configStore) {
        return (ignored) -> {
            try {
                return configStore.read();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Consumer<JsonObject> writer(ConfigStore configStore) {
        return (config) -> {
            try {
                configStore.write(config);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    // ==================== Claude Provider Management ====================

    public List<JsonObject> getClaudeProviders() throws IOException {
        return providerManager.getClaudeProviders();
    }

    public JsonObject getActiveClaudeProvider() throws IOException {
        return providerManager.getActiveClaudeProvider();
    }

    public void addClaudeProvider(JsonObject provider) throws IOException {
        providerManager.addClaudeProvider(provider);
    }

    public void updateClaudeProvider(String id, JsonObject updates) throws IOException {
        providerManager.updateClaudeProvider(id, updates);
    }

    public DeleteResult deleteClaudeProvider(String id) {
        return providerManager.deleteClaudeProvider(id);
    }

    public void switchClaudeProvider(String id) throws IOException {
        providerManager.switchClaudeProvider(id);
    }

    public void deactivateClaudeProvider() throws IOException {
        providerManager.deactivateClaudeProvider();
    }

    public List<JsonObject> parseProvidersFromCcSwitchDb(String dbPath) throws IOException {
        return providerManager.parseProvidersFromCcSwitchDb(dbPath);
    }

    public List<JsonObject> parseProvidersFromCcSwitchDb(String dbPath, String appType) throws IOException {
        return providerManager.parseProvidersFromCcSwitchDb(dbPath, appType);
    }

    public int saveProviders(List<JsonObject> providers) throws IOException {
        return providerManager.saveProviders(providers);
    }

    public void saveProviderOrder(List<String> orderedIds) throws IOException {
        providerManager.saveProviderOrder(orderedIds);
    }

    public int saveCodexProviders(List<JsonObject> providers) throws IOException {
        return codexProviderManager.saveProviders(providers);
    }

    public boolean isLocalProviderActive() {
        return providerManager.isLocalProviderActive();
    }

    public boolean setAlwaysThinkingEnabledInActiveProvider(boolean enabled) throws IOException {
        return providerManager.setAlwaysThinkingEnabledInActiveProvider(enabled);
    }

    public boolean repairActiveProviderToClaudeSettings() throws IOException {
        return providerManager.repairActiveProviderToClaudeSettings();
    }

    public void applyActiveProviderToClaudeSettings() throws IOException {
        providerManager.applyActiveProviderToClaudeSettings();
    }

    // ==================== Codex Provider Management ====================

    public List<JsonObject> getCodexProviders() throws IOException {
        return codexProviderManager.getCodexProviders();
    }

    public JsonObject getActiveCodexProvider() throws IOException {
        return codexProviderManager.getActiveCodexProvider();
    }

    public void addCodexProvider(JsonObject provider) throws IOException {
        codexProviderManager.addCodexProvider(provider);
    }

    public void updateCodexProvider(String id, JsonObject updates) throws IOException {
        codexProviderManager.updateCodexProvider(id, updates);
    }

    public DeleteResult deleteCodexProvider(String id) {
        return codexProviderManager.deleteCodexProvider(id);
    }

    public void switchCodexProvider(String id) throws IOException {
        codexProviderManager.switchCodexProvider(id);
    }

    public void applyActiveProviderToCodexSettings() throws IOException {
        codexProviderManager.applyActiveProviderToCodexSettings();
    }

    public JsonObject getCurrentCodexConfig() throws IOException {
        if (!isCodexLocalConfigAuthorized()) {
            return new JsonObject();
        }
        return codexProviderManager.getCurrentCodexConfig();
    }

    public boolean isCodexCliLoginAvailable() {
        try {
            if (!isCodexLocalConfigAuthorized()) {
                return false;
            }
            return codexSettingsManager.isCodexCliLoginAvailable();
        } catch (IOException e) {
            LOG.warn("[ProviderSettings] Failed to check Codex local authorization: " + e.getMessage());
            return false;
        }
    }

    public JsonObject readCodexCliLoginAccountInfo() {
        try {
            if (!isCodexLocalConfigAuthorized()) {
                return null;
            }
            return codexSettingsManager.readCodexCliLoginAccountInfo();
        } catch (IOException e) {
            LOG.warn("[ProviderSettings] Failed to read Codex local authorization state: " + e.getMessage());
            return null;
        }
    }

    public boolean isCodexLocalConfigAuthorized() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(ProviderType.CODEX.value()) || !config.get(ProviderType.CODEX.value()).isJsonObject()) {
            return false;
        }
        JsonObject codex = config.getAsJsonObject(ProviderType.CODEX.value());
        return codex.has("localConfigAuthorized")
                && !codex.get("localConfigAuthorized").isJsonNull()
                && codex.get("localConfigAuthorized").getAsBoolean();
    }

    public void setCodexLocalConfigAuthorized(boolean authorized) throws IOException {
        configStore.update(config -> {
            JsonObject codex;
            if (config.has(ProviderType.CODEX.value())
                    && config.get(ProviderType.CODEX.value()).isJsonObject()) {
                codex = config.getAsJsonObject(ProviderType.CODEX.value());
            } else {
                codex = new JsonObject();
                codex.add("providers", new JsonObject());
                codex.addProperty("current", "");
                config.add(ProviderType.CODEX.value(), codex);
            }
            codex.addProperty("localConfigAuthorized", authorized);
        });
    }

    public String getCodexRuntimeAccessMode() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(ProviderType.CODEX.value()) || !config.get(ProviderType.CODEX.value()).isJsonObject()) {
            return ProviderRuntimeAccessMode.INACTIVE.value();
        }

        JsonObject codex = config.getAsJsonObject(ProviderType.CODEX.value());
        String currentId = codex.has("current") && !codex.get("current").isJsonNull()
                ? codex.get("current").getAsString().trim()
                : "";

        if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(currentId)) {
            return isCodexLocalConfigAuthorized()
                    ? ProviderRuntimeAccessMode.CLI_LOGIN.value()
                    : ProviderRuntimeAccessMode.INACTIVE.value();
        }

        if (!currentId.isEmpty()
                && codex.has("providers")
                && codex.get("providers").isJsonObject()
                && codex.getAsJsonObject("providers").has(currentId)) {
            return ProviderRuntimeAccessMode.MANAGED.value();
        }

        return ProviderRuntimeAccessMode.INACTIVE.value();
    }

    public void saveCodexProviderOrder(List<String> orderedIds) throws IOException {
        codexProviderManager.saveProviderOrder(orderedIds);
    }

    // ==================== OpenCode Provider Management ==================== (对称 codex 段)

    public List<JsonObject> getOpenCodeProviders() throws IOException {
        return openCodeProviderManager.getOpenCodeProviders();
    }

    public JsonObject getActiveOpenCodeProvider() throws IOException {
        return openCodeProviderManager.getActiveOpenCodeProvider();
    }

    public void addOpenCodeProvider(JsonObject provider) throws IOException {
        openCodeProviderManager.addOpenCodeProvider(provider);
    }

    public void updateOpenCodeProvider(String id, JsonObject updates) throws IOException {
        openCodeProviderManager.updateOpenCodeProvider(id, updates);
    }

    public DeleteResult deleteOpenCodeProvider(String id) {
        return openCodeProviderManager.deleteOpenCodeProvider(id);
    }

    public void switchOpenCodeProvider(String id) throws IOException {
        openCodeProviderManager.switchOpenCodeProvider(id);
    }

    public void applyActiveProviderToOpenCodeSettings() throws IOException {
        openCodeProviderManager.applyActiveProviderToOpenCodeSettings();
    }

    public JsonObject getCurrentOpenCodeConfig() throws IOException {
        if (!isOpencodeLocalConfigAuthorized()) {
            return new JsonObject();
        }
        return openCodeProviderManager.getCurrentOpenCodeConfig();
    }

    public boolean isOpencodeLocalConfigAuthorized() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(ProviderType.OPENCODE.value()) || !config.get(ProviderType.OPENCODE.value()).isJsonObject()) {
            return false;
        }
        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        return opencode.has("localConfigAuthorized")
                && !opencode.get("localConfigAuthorized").isJsonNull()
                && opencode.get("localConfigAuthorized").getAsBoolean();
    }

    public void setOpencodeLocalConfigAuthorized(boolean authorized) throws IOException {
        configStore.update(config -> {
            JsonObject opencode;
            if (config.has(ProviderType.OPENCODE.value())
                    && config.get(ProviderType.OPENCODE.value()).isJsonObject()) {
                opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
            } else {
                opencode = new JsonObject();
                opencode.add("providers", new JsonObject());
                opencode.addProperty("current", "");
                config.add(ProviderType.OPENCODE.value(), opencode);
            }
            opencode.addProperty("localConfigAuthorized", authorized);
        });
    }

    public String getOpenCodeRuntimeAccessMode() throws IOException {
        JsonObject config = configStore.read();
        if (!config.has(ProviderType.OPENCODE.value()) || !config.get(ProviderType.OPENCODE.value()).isJsonObject()) {
            return ProviderRuntimeAccessMode.INACTIVE.value();
        }

        JsonObject opencode = config.getAsJsonObject(ProviderType.OPENCODE.value());
        String currentId = opencode.has("current") && !opencode.get("current").isJsonNull()
                ? opencode.get("current").getAsString().trim()
                : "";

        if (OpenCodeProviderManager.OPENCODE_LOCAL_CONFIG_PROVIDER_ID.equals(currentId)) {
            return isOpencodeLocalConfigAuthorized()
                    ? ProviderRuntimeAccessMode.CLI_LOGIN.value()
                    : ProviderRuntimeAccessMode.INACTIVE.value();
        }

        if (!currentId.isEmpty()
                && opencode.has("providers")
                && opencode.get("providers").isJsonObject()
                && opencode.getAsJsonObject("providers").has(currentId)) {
            return ProviderRuntimeAccessMode.MANAGED.value();
        }

        return ProviderRuntimeAccessMode.INACTIVE.value();
    }

    public void saveOpenCodeProviderOrder(List<String> orderedIds) throws IOException {
        openCodeProviderManager.saveProviderOrder(orderedIds);
    }
}
