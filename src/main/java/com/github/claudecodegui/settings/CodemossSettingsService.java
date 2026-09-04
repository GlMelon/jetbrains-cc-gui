package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfigValidator;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.config.RuntimePolicyConfig;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.model.PromptScope;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.settings.credentials.IntelliJPasswordSafeBackend;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.github.claudecodegui.watcher.ConfigFileWatcherService;
import com.google.gson.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Codemoss configuration service (Facade pattern).
 * Delegates specific functionality to specialized managers.
 */
public class CodemossSettingsService {

    public static CodemossSettingsService getInstance() {
        return ApplicationManager.getApplication().getService(CodemossSettingsService.class);
    }

    private static final Logger LOG = Logger.getInstance(CodemossSettingsService.class);
    private static final String CLAUDE_CLI_PATH_KEY = "claudeCliPath";
    public static final String CODEX_RUNTIME_ACCESS_INACTIVE = ProviderRuntimeAccessMode.INACTIVE.value();
    public static final String CODEX_RUNTIME_ACCESS_MANAGED = ProviderRuntimeAccessMode.MANAGED.value();
    public static final String CODEX_RUNTIME_ACCESS_CLI_LOGIN = ProviderRuntimeAccessMode.CLI_LOGIN.value();
    private static final String COMMIT_AI_KEY = "commitAi";
    private static final String PROMPT_ENHANCER_KEY = "promptEnhancer";
    private static final String AI_FEATURE_PROVIDER_KEY = "provider";
    private static final String AI_FEATURE_MODELS_KEY = "models";
    private static final String AI_FEATURE_EFFECTIVE_PROVIDER_KEY = "effectiveProvider";
    private static final String AI_FEATURE_RESOLUTION_SOURCE_KEY = "resolutionSource";
    private static final String AI_FEATURE_AVAILABILITY_KEY = "availability";
    private static final String AI_FEATURE_RESOLUTION_MANUAL = "manual";
    private static final String AI_FEATURE_RESOLUTION_AUTO = "auto";
    private static final String AI_FEATURE_RESOLUTION_UNAVAILABLE = "unavailable";
    private static final String DEFAULT_PROMPT_ENHANCER_CLAUDE_MODEL = CommonConstants.DEFAULT_MODEL;
    private static final String DEFAULT_PROMPT_ENHANCER_CODEX_MODEL = "";
    private static final String DEFAULT_COMMIT_AI_CLAUDE_MODEL = CommonConstants.DEFAULT_MODEL;
    private static final String DEFAULT_COMMIT_AI_CODEX_MODEL = "";
    private static final String DEFAULT_AI_FEATURE_OPENCODE_MODEL = "";
    private static final String USER_LANGUAGE_CONFIG_KEY = "language";

    private final Gson gson;
    private final PasswordStore passwordStore;

    // Managers
    private final ConfigPathManager pathManager;
    /** config.json 的唯一存储实现；Facade 保留兼容读写调用面。 */
    private final ConfigRepository configRepository;
    /** 外观与字体领域 Service。 */
    private final AppearanceSettingsService appearanceSettingsService;
    /** AI 功能开关与 Smithery 凭证领域 Service。 */
    private final AiFeatureToggleSettingsService aiFeatureToggleSettingsService;
    /** Codex Sandbox Mode 领域 Service。 */
    private final CodexSandboxModeSettingsService codexSandboxModeSettingsService;
    /** 模型注册表领域 Service。 */
    private final ModelRegistrySettingsService modelRegistrySettingsService;
    private final ClaudeSettingsManager claudeSettingsManager;
    private final CodexSettingsManager codexSettingsManager;
    private final CodexMcpServerManager codexMcpServerManager;
    private final WorkingDirectoryManager workingDirectoryManager;
    private final AgentManager agentManager;
    /** MCP 服务器配置领域 Service。 */
    private final McpSettingsService mcpSettingsService;
    private final OpenCodeSettingsManager openCodeSettingsManager;
    /** Provider 配置领域 Service。 */
    private final ProviderSettingsService providerSettingsService;

    public CodemossSettingsService() {
        this(new PasswordStore(new IntelliJPasswordSafeBackend()));
    }

    CodemossSettingsService(PasswordStore passwordStore) {
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        this.passwordStore = passwordStore;

        // Initialize ConfigPathManager
        this.pathManager = new ConfigPathManager();

        // ConfigRepository owns migration, serialization, CAS, backup, and recovery.
        this.configRepository = new ConfigRepository(
                pathManager.getConfigDir(),
                gson,
                ConfigSchema::createDefaultConfig,
                ConfigSchema.createMigrationRegistry(passwordStore)
        );

        // Domain services depend on ConfigStore directly; this class remains the public Facade.
        this.appearanceSettingsService = new AppearanceSettingsService(configRepository);

        this.aiFeatureToggleSettingsService = new AiFeatureToggleSettingsService(configRepository, passwordStore);

        this.codexSandboxModeSettingsService = new CodexSandboxModeSettingsService(configRepository);

        this.modelRegistrySettingsService = new ModelRegistrySettingsService(configRepository);

        // Initialize ClaudeSettingsManager
        this.claudeSettingsManager = new ClaudeSettingsManager(gson, pathManager);

        // Initialize WorkingDirectoryManager
        this.workingDirectoryManager = new WorkingDirectoryManager(
                (ignored) -> {
                    try {
                        return configRepository.read();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        configRepository.write(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        // Initialize AgentManager
        this.agentManager = new AgentManager(gson, pathManager);

        // Initialize CodexSettingsManager
        this.codexSettingsManager = new CodexSettingsManager(gson);

        // Initialize CodexMcpServerManager
        this.codexMcpServerManager = new CodexMcpServerManager(codexSettingsManager);

        // Initialize OpenCodeSettingsManager(对称 claude/codex settings manager,native-file 依赖;构造后注入 ProviderSettingsService)
        this.openCodeSettingsManager = new OpenCodeSettingsManager(gson);

        // MCP domain:全局 SSOT 为 config.json mcpServers;codex/opencode manager 注入用于原生写穿与一次性迁移。
        this.mcpSettingsService = new McpSettingsService(
                configRepository,
                gson,
                claudeSettingsManager,
                codexMcpServerManager,
                openCodeSettingsManager
        );

        // Provider domain owns all provider managers and depends on ConfigStore only.
        this.providerSettingsService = new ProviderSettingsService(
                configRepository,
                gson,
                pathManager,
                claudeSettingsManager,
                codexSettingsManager,
                openCodeSettingsManager
        );

        // Watch external config changes (for example cc-switch) and refresh frontend state.
        try {
            ConfigFileWatcherService.getInstance().ensureStarted(pathManager.getConfigDir().toString());
        } catch (Exception e) {
            LOG.debug("[CodemossSettings] ConfigFileWatcherService not started (no Application context): "
                    + e.getMessage());
        }
    }

    // ==================== Basic Config Management ====================

    /**
     * Get config file path (~/.codemoss/config.json).
     */
    public String getConfigPath() {
        return pathManager.getConfigPath();
    }

    /**
     * Read the config file.
     *
     * <p>不加缓存:config.json 会被外部工具(cc-switch)修改,任何 TTL 缓存都会导致外部切换
     * provider/模型后插件读到写前快照(用旧配置 send)。配置即时性优先于 ~20ms 的重复 IO 收益。
     * 委托 {@link ConfigRepository#load()} 完成原子读、malformed quarantine 与 backup 自动回退,
     * 不再静默用 default 覆盖损坏文件。</p>
     */
    public JsonObject readConfig() throws IOException {
        return configRepository.read();
    }

    /**
     * Write the config file.
     *
     * <p>委托 {@link ConfigRepository#save(JsonObject)} 完成原子写(temp+ATOMIC_MOVE+fsync)、
     * write-time mtime CAS(防 cc-switch / 外部编辑 lost update)+ 多版本 backup。CAS 冲突抛
     * {@link ConfigRepository.ConfigConflictException}。</p>
     */
    public void writeConfig(JsonObject config) throws IOException {
        configRepository.write(config);
    }

    // ==================== Language Config Management ====================

    /**
     * Get the manually configured UI language.
     *
     * @return configured language code, or null when the UI should follow the IDE language
     */
    public String getUserLanguage() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(USER_LANGUAGE_CONFIG_KEY) || config.get(USER_LANGUAGE_CONFIG_KEY).isJsonNull()) {
            return null;
        }
        String language = config.get(USER_LANGUAGE_CONFIG_KEY).getAsString();
        return language == null || language.trim().isEmpty() ? null : language.trim();
    }

    /**
     * Persist the manually configured UI language.
     *
     * @param language supported UI language code
     */
    public void setUserLanguage(String language) throws IOException {
        JsonObject config = readConfig();
        config.addProperty(USER_LANGUAGE_CONFIG_KEY, language);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set user language: " + language);
    }

    /**
     * Clear the manual UI language override so the webview follows the IDE language.
     */
    public void clearUserLanguage() throws IOException {
        JsonObject config = readConfig();
        config.remove(USER_LANGUAGE_CONFIG_KEY);
        writeConfig(config);
        LOG.info("[CodemossSettings] Cleared user language override");
    }

    // ==================== Appearance Config Management ====================

    /**
     * Get persisted appearance config (theme preference / font size / diff theme /
     * per-theme colors). Serves as the cold-cache hydration source so the webview
     * can restore these settings after the IDE cache is invalidated.
     *
     * @return normalized appearance config
     */
    public JsonObject getAppearanceConfig() throws IOException {
        return appearanceSettingsService.getAppearanceConfig();
    }

    /**
     * Persist appearance config (called from the webview via {@code set_appearance_config}).
     *
     * @param rawConfig raw appearance config payload from the webview
     */
    public void setAppearanceConfig(JsonObject rawConfig) throws IOException {
        appearanceSettingsService.setAppearanceConfig(rawConfig);
    }

    /** Appearance config as a JSON string (for webview injection); never throws. */
    public static String getAppearanceConfigJson(CodemossSettingsService service) {
        try {
            return service.getAppearanceConfig().toString();
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read appearance config: " + e.getMessage());
            return new JsonObject().toString();
        }
    }

    // ==================== Claude Settings Management ====================

    public JsonObject getCurrentClaudeConfig() throws IOException {
        JsonObject currentConfig = claudeSettingsManager.getCurrentClaudeConfig();

        // If codemossProviderId exists, try to get provider name from codemoss config
        if (currentConfig.has("providerId")) {
            String providerId = currentConfig.get("providerId").getAsString();
            try {
                JsonObject config = readConfig();
                if (config.has(CommonConstants.PROVIDER_CLAUDE)) {
                    JsonObject claude = config.getAsJsonObject(CommonConstants.PROVIDER_CLAUDE);
                    if (claude.has("providers")) {
                        JsonObject providers = claude.getAsJsonObject("providers");
                        if (providers.has(providerId)) {
                            JsonObject provider = providers.getAsJsonObject(providerId);
                            if (provider.has("name")) {
                                currentConfig.addProperty("providerName", provider.get("name").getAsString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore error - provider name is optional
            }
        }

        return currentConfig;
    }

    public JsonObject readClaudeSettings() throws IOException {
        return claudeSettingsManager.readClaudeSettings();
    }

    public Boolean getAlwaysThinkingEnabledFromClaudeSettings() throws IOException {
        return claudeSettingsManager.getAlwaysThinkingEnabled();
    }

    public void setAlwaysThinkingEnabledInClaudeSettings(boolean enabled) throws IOException {
        claudeSettingsManager.setAlwaysThinkingEnabled(enabled);
    }

    public boolean setAlwaysThinkingEnabledInActiveProvider(boolean enabled) throws IOException {
        return providerSettingsService.setAlwaysThinkingEnabledInActiveProvider(enabled);
    }

    public void applyProviderToClaudeSettings(JsonObject provider) throws IOException {
        claudeSettingsManager.applyProviderToClaudeSettings(provider);
    }

    public void applyCliLoginToClaudeSettings() throws IOException {
        claudeSettingsManager.applyCliLoginToClaudeSettings();
    }

    public void removeCliLoginFromClaudeSettings() throws IOException {
        claudeSettingsManager.removeCliLoginFromClaudeSettings();
    }

    public JsonObject readCliLoginAccountInfo() {
        return claudeSettingsManager.readCliLoginAccountInfo();
    }

    public void applyActiveProviderToClaudeSettings() throws IOException {
        providerSettingsService.applyActiveProviderToClaudeSettings();
    }

    /**
     * Startup-time repair pass: only fills in provider-managed fields that are
     * missing from {@code ~/.claude/settings.json}, never overwrites existing
     * values. See {@link ProviderManager#repairActiveProviderToClaudeSettings()}.
     */
    public boolean repairActiveProviderToClaudeSettings() throws IOException {
        return providerSettingsService.repairActiveProviderToClaudeSettings();
    }

    // ==================== Working Directory Management ====================

    public String getCustomWorkingDirectory(String projectPath) throws IOException {
        return workingDirectoryManager.getCustomWorkingDirectory(projectPath);
    }

    public void setCustomWorkingDirectory(String projectPath, String customWorkingDir) throws IOException {
        workingDirectoryManager.setCustomWorkingDirectory(projectPath, customWorkingDir);
    }

    public String getEffectiveWorkingDirectory(String projectPath) {
        return workingDirectoryManager.resolveEffectiveWorkingDirectory(projectPath);
    }

    // ==================== Commit Prompt Config Management ====================

    /**
     * Get the commit AI prompt.
     *
     * @return commit prompt
     */
    public String getCommitPrompt() throws IOException {
        JsonObject config = readConfig();

        // Check for commitPrompt config
        if (config.has("commitPrompt")) {
            return config.get("commitPrompt").getAsString();
        }

        // Return default value (from i18n resource bundle)
        return ClaudeCodeGuiBundle.message("commit.defaultPrompt");
    }

    /**
     * Set the commit AI prompt.
     *
     * @param prompt commit prompt
     */
    public void setCommitPrompt(String prompt) throws IOException {
        JsonObject config = readConfig();

        // Save config
        config.addProperty("commitPrompt", prompt);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set commit prompt: " + prompt);
    }

    /**
     * Get project-level commit AI prompt.
     *
     * @param projectPath project path
     * @return project commit prompt, empty string if not configured
     */
    public String getProjectCommitPrompt(String projectPath) throws IOException {
        if (projectPath == null) {
            return "";
        }
        JsonObject config = readConfig();
        if (config.has("projectCommitPrompt")) {
            JsonObject projectPrompts = config.getAsJsonObject("projectCommitPrompt");
            if (projectPrompts.has(projectPath)) {
                return projectPrompts.get(projectPath).getAsString();
            }
        }
        return "";
    }

    /**
     * Set project-level commit AI prompt.
     *
     * @param projectPath project path
     * @param prompt commit prompt
     */
    public void setProjectCommitPrompt(String projectPath, String prompt) throws IOException {
        if (projectPath == null) {
            return;
        }
        JsonObject config = readConfig();
        JsonObject projectPrompts;
        if (config.has("projectCommitPrompt")) {
            projectPrompts = config.getAsJsonObject("projectCommitPrompt");
        } else {
            projectPrompts = new JsonObject();
            config.add("projectCommitPrompt", projectPrompts);
        }
        projectPrompts.addProperty(projectPath, prompt);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set project commit prompt for project: " + projectPath);
    }

    // ==================== UI Font Config Management ====================

    /**
     * Get persisted UI font configuration.
     *
     * @return normalized UI font configuration
     */
    public JsonObject getUiFontConfig() throws IOException {
        return appearanceSettingsService.getUiFontConfig();
    }

    /**
     * Persist UI font configuration.
     *
     * @param mode requested mode
     * @param customFontPath custom font path for custom file mode
     */
    public void setUiFontConfig(String mode, String customFontPath) throws IOException {
        appearanceSettingsService.setUiFontConfig(mode, customFontPath);
    }

    /**
     * Get persisted code font configuration.
     *
     * @return normalized code font configuration
     */
    public JsonObject getCodeFontConfig() throws IOException {
        return appearanceSettingsService.getCodeFontConfig();
    }

    /**
     * Persist code font configuration.
     *
     * @param mode requested mode
     * @param customFontPath custom font path for custom file mode
     */
    public void setCodeFontConfig(String mode, String customFontPath) throws IOException {
        appearanceSettingsService.setCodeFontConfig(mode, customFontPath);
    }

    // ==================== Permission Dialog Timeout Config Management ====================

    public static final int DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS =
            PermissionDialogTimeoutSettings.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS;
    public static final long PERMISSION_SAFETY_NET_BUFFER_SECONDS =
            PermissionDialogTimeoutSettings.PERMISSION_SAFETY_NET_BUFFER_SECONDS;

    public static int clampPermissionDialogTimeoutSeconds(int seconds) {
        return PermissionDialogTimeoutSettings.clampPermissionDialogTimeoutSeconds(seconds);
    }

    public int getPermissionDialogTimeoutSeconds() throws IOException {
        return PermissionDialogTimeoutSettings.getPermissionDialogTimeoutSeconds(this);
    }

    public void setPermissionDialogTimeoutSeconds(int seconds) throws IOException {
        PermissionDialogTimeoutSettings.setPermissionDialogTimeoutSeconds(this, seconds);
    }

    // ==================== Streaming Config Management ====================

    /**
     * Get streaming configuration.
     *
     * @param projectPath project path
     * @return whether streaming is enabled
     */
    public boolean getStreamingEnabled(String projectPath) throws IOException {
        JsonObject config = readConfig();

        // Check for streaming config
        if (!config.has("streaming")) {
            return true;
        }

        JsonObject streaming = config.getAsJsonObject("streaming");

        // Check project-specific config first
        if (projectPath != null && streaming.has(projectPath)) {
            return streaming.get(projectPath).getAsBoolean();
        }

        // Fall back to global default if no project-specific config
        if (streaming.has("default")) {
            return streaming.get("default").getAsBoolean();
        }

        return true;
    }

    /**
     * Set streaming configuration.
     *
     * @param projectPath project path
     * @param enabled     whether to enable
     */
    public void setStreamingEnabled(String projectPath, boolean enabled) throws IOException {
        JsonObject config = readConfig();

        // Ensure streaming object exists
        JsonObject streaming;
        if (config.has("streaming")) {
            streaming = config.getAsJsonObject("streaming");
        } else {
            streaming = new JsonObject();
            config.add("streaming", streaming);
        }

        // Save project-specific config (also serves as default)
        if (projectPath != null) {
            streaming.addProperty(projectPath, enabled);
        }
        streaming.addProperty("default", enabled);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set streaming enabled to " + enabled + " for project: " + projectPath);
    }

    // ==================== Show Thinking Config Management ====================

    /**
     * Get show-thinking configuration (思考区显示开关)。
     * <p>
     * 语义:控制是否推送/显示模型的 thinking 数据——不影响模型是否思考(后者由 reasoning effort 决定)。
     * 默认 true(显示思考区)。按项目存储于 config.showThinking,三层回退 projectPath → default → true。
     *
     * @param projectPath project path
     * @return whether thinking output should be shown
     */
    public boolean getShowThinkingEnabled(String projectPath) throws IOException {
        JsonObject config = readConfig();

        if (!config.has("showThinking")) {
            return true;
        }

        JsonObject showThinking = config.getAsJsonObject("showThinking");

        if (projectPath != null && showThinking.has(projectPath)) {
            return showThinking.get(projectPath).getAsBoolean();
        }

        if (showThinking.has("default")) {
            return showThinking.get("default").getAsBoolean();
        }

        return true;
    }

    /**
     * Set show-thinking configuration (思考区显示开关)。
     *
     * @param projectPath project path
     * @param enabled     whether to show thinking output
     */
    public void setShowThinkingEnabled(String projectPath, boolean enabled) throws IOException {
        JsonObject config = readConfig();

        JsonObject showThinking;
        if (config.has("showThinking")) {
            showThinking = config.getAsJsonObject("showThinking");
        } else {
            showThinking = new JsonObject();
            config.add("showThinking", showThinking);
        }

        if (projectPath != null) {
            showThinking.addProperty(projectPath, enabled);
        }
        showThinking.addProperty("default", enabled);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set showThinking enabled to " + enabled + " for project: " + projectPath);
    }

    // ==================== Auto Open File Config Management ====================

    /**
     * Get auto-open file configuration.
     *
     * @param projectPath project path
     * @return whether auto-open file is enabled
     */
    public boolean getAutoOpenFileEnabled(String projectPath) throws IOException {
        JsonObject config = readConfig();

        // Check for autoOpenFile config
        if (!config.has("autoOpenFile")) {
            return false;
        }

        JsonObject autoOpenFile = config.getAsJsonObject("autoOpenFile");

        // Check project-specific config first
        if (projectPath != null && autoOpenFile.has(projectPath)) {
            return autoOpenFile.get(projectPath).getAsBoolean();
        }

        // Fall back to global default if no project-specific config
        if (autoOpenFile.has("default")) {
            return autoOpenFile.get("default").getAsBoolean();
        }

        return false;
    }

    /**
     * Set auto-open file configuration.
     *
     * @param projectPath project path
     * @param enabled     whether to enable
     */
    public void setAutoOpenFileEnabled(String projectPath, boolean enabled) throws IOException {
        JsonObject config = readConfig();

        // Ensure autoOpenFile object exists
        JsonObject autoOpenFile;
        if (config.has("autoOpenFile")) {
            autoOpenFile = config.getAsJsonObject("autoOpenFile");
        } else {
            autoOpenFile = new JsonObject();
            config.add("autoOpenFile", autoOpenFile);
        }

        // Save project-specific config (also serves as default)
        if (projectPath != null) {
            autoOpenFile.addProperty(projectPath, enabled);
        }
        autoOpenFile.addProperty("default", enabled);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set auto open file enabled to " + enabled + " for project: " + projectPath);
    }

    // ==================== Codex Sandbox Mode Config Management (delegates to CodexSandboxModeSettingsService) ====================

    /**
     * Get Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @return sandbox mode (workspace-write or danger-full-access)
     */
    public String getCodexSandboxMode(String projectPath) throws IOException {
        return codexSandboxModeSettingsService.getCodexSandboxMode(projectPath);
    }

    /**
     * Set Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @param sandboxMode sandbox mode (workspace-write or danger-full-access)
     */
    public void setCodexSandboxMode(String projectPath, String sandboxMode) throws IOException {
        codexSandboxModeSettingsService.setCodexSandboxMode(projectPath, sandboxMode);
    }

    // ==================== Provider Management ====================

    public List<JsonObject> getClaudeProviders() throws IOException {
        return providerSettingsService.getClaudeProviders();
    }

    public JsonObject getActiveClaudeProvider() throws IOException {
        return providerSettingsService.getActiveClaudeProvider();
    }

    public void addClaudeProvider(JsonObject provider) throws IOException {
        providerSettingsService.addClaudeProvider(provider);
    }

    public void updateClaudeProvider(String id, JsonObject updates) throws IOException {
        providerSettingsService.updateClaudeProvider(id, updates);
    }

    public DeleteResult deleteClaudeProvider(String id) {
        return providerSettingsService.deleteClaudeProvider(id);
    }

    public void switchClaudeProvider(String id) throws IOException {
        providerSettingsService.switchClaudeProvider(id);
    }

    public void deactivateClaudeProvider() throws IOException {
        providerSettingsService.deactivateClaudeProvider();
    }

    public List<JsonObject> parseProvidersFromCcSwitchDb(String dbPath) throws IOException {
        return providerSettingsService.parseProvidersFromCcSwitchDb(dbPath);
    }

    /**
     * Parse Codex provider configurations from cc-switch.db.
     */
    public List<JsonObject> parseCodexProvidersFromCcSwitchDb(String dbPath) throws IOException {
        return providerSettingsService.parseProvidersFromCcSwitchDb(dbPath, CommonConstants.PROVIDER_CODEX);
    }

    public int saveProviders(List<JsonObject> providers) throws IOException {
        return providerSettingsService.saveProviders(providers);
    }

    /**
     * Save Codex provider configurations.
     *
     * @param providers list of Codex provider JSON objects
     * @return number of providers saved
     */
    public int saveCodexProviders(List<JsonObject> providers) throws IOException {
        return providerSettingsService.saveCodexProviders(providers);
    }

    public void saveProviderOrder(List<String> orderedIds) throws IOException {
        providerSettingsService.saveProviderOrder(orderedIds);
    }

    public boolean isLocalProviderActive() {
        return providerSettingsService.isLocalProviderActive();
    }

    // ==================== MCP Server Management ====================

    public List<JsonObject> getMcpServers() throws IOException {
        return mcpSettingsService.getMcpServers();
    }

    public List<JsonObject> getMcpServersWithProjectPath(String projectPath) throws IOException {
        return mcpSettingsService.getMcpServersWithProjectPath(projectPath);
    }

    /**
     * 读取 claude 原生 MCP 配置(~/.claude.json 直读,含项目级合并),供 MCP Gateway collector 使用;
     * 不走全局 SSOT。
     */
    public List<JsonObject> readClaudeNativeMcpServers(String projectPath) {
        return mcpSettingsService.readClaudeNativeMcpServers(projectPath);
    }

    public void upsertMcpServer(JsonObject server) throws IOException {
        mcpSettingsService.upsertMcpServer(server);
    }

    public void upsertMcpServer(JsonObject server, String projectPath) throws IOException {
        mcpSettingsService.upsertMcpServer(server, projectPath);
    }

    public boolean deleteMcpServer(String serverId) throws IOException {
        return mcpSettingsService.deleteMcpServer(serverId);
    }

    public Map<String, Object> validateMcpServer(JsonObject server) {
        return mcpSettingsService.validateMcpServer(server);
    }

    // ==================== Codex MCP Server Management ====================

    public CodexMcpServerManager getCodexMcpServerManager() {
        return codexMcpServerManager;
    }

    // ==================== Agents Management ====================

    public List<JsonObject> getAgents() throws IOException {
        return agentManager.getAgents();
    }

    public void addAgent(JsonObject agent) throws IOException {
        agentManager.addAgent(agent);
    }

    public void updateAgent(String id, JsonObject updates) throws IOException {
        agentManager.updateAgent(id, updates);
    }

    public boolean deleteAgent(String id) throws IOException {
        return agentManager.deleteAgent(id);
    }

    public JsonObject getAgent(String id) throws IOException {
        return agentManager.getAgent(id);
    }

    public String getSelectedAgentId() throws IOException {
        return agentManager.getSelectedAgentId();
    }

    public void setSelectedAgentId(String agentId) throws IOException {
        agentManager.setSelectedAgentId(agentId);
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    // ==================== Prompts Management ====================

    /**
     * Get a PromptManager for the specified scope.
     * Creates managers on-demand using PromptManagerFactory.
     *
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return An AbstractPromptManager instance for the specified scope
     */
    public AbstractPromptManager getPromptManager(PromptScope scope, Project project) {
        return PromptManagerFactory.create(scope, gson, pathManager, project);
    }

    /**
     * Get prompts from the specified scope.
     *
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return List of prompts
     * @throws IOException if reading fails
     */
    public List<JsonObject> getPrompts(PromptScope scope, Project project) throws IOException {
        return getPromptManager(scope, project).getPrompts();
    }

    /** Get prompts owned by one provider. Legacy prompts without a provider belong to Claude. */
    public List<JsonObject> getPrompts(PromptScope scope, Project project, String provider) throws IOException {
        ProviderType requestedProvider = ProviderType.fromString(provider);
        return getPromptManager(scope, project).getPrompts().stream()
                .filter(prompt -> requestedProvider == promptProvider(prompt))
                .toList();
    }

    /**
     * Add a prompt to the specified scope.
     *
     * @param prompt  The prompt to add
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @throws IOException if writing fails
     */
    public void addPrompt(JsonObject prompt, PromptScope scope, Project project) throws IOException {
        getPromptManager(scope, project).addPrompt(prompt);
    }

    /**
     * Update a prompt in the specified scope.
     *
     * @param id      The prompt ID
     * @param updates The updates to apply
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @throws IOException if writing fails
     */
    public void updatePrompt(String id, JsonObject updates, PromptScope scope, Project project) throws IOException {
        getPromptManager(scope, project).updatePrompt(id, updates);
    }

    /**
     * Delete a prompt from the specified scope.
     *
     * @param id      The prompt ID
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return true if deleted, false if not found
     * @throws IOException if writing fails
     */
    public boolean deletePrompt(String id, PromptScope scope, Project project) throws IOException {
        return getPromptManager(scope, project).deletePrompt(id);
    }

    /** Delete a prompt only when the caller owns the prompt's provider namespace. */
    public boolean deletePrompt(String id, PromptScope scope, Project project, String provider) throws IOException {
        AbstractPromptManager manager = getPromptManager(scope, project);
        JsonObject prompt = manager.getPrompt(id);
        if (prompt == null || ProviderType.fromString(provider) != promptProvider(prompt)) {
            return false;
        }
        return manager.deletePrompt(id);
    }

    private ProviderType promptProvider(JsonObject prompt) {
        if (prompt == null || !prompt.has(CommonConstants.JSON_KEY_PROVIDER)
                || prompt.get(CommonConstants.JSON_KEY_PROVIDER).isJsonNull()) {
            return ProviderType.CLAUDE;
        }
        return ProviderType.fromString(prompt.get(CommonConstants.JSON_KEY_PROVIDER).getAsString());
    }

    // ==================== Task Completion Notification Management ====================

    /**
     * Get whether task completion balloon notification is enabled.
     *
     * @return whether task completion notification is enabled, default is false (opt-in)
     */
    public boolean getTaskCompletionNotificationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("taskCompletionNotificationEnabled") && !config.get("taskCompletionNotificationEnabled").isJsonNull()) {
            return config.get("taskCompletionNotificationEnabled").getAsBoolean();
        }

        return false;
    }

    /**
     * Set whether task completion balloon notification is enabled.
     *
     * @param enabled whether to enable
     */
    public void setTaskCompletionNotificationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("taskCompletionNotificationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set task completion notification enabled: " + enabled);
    }

    // ==================== Ask User Question Notification Management ====================

    /**
     * Get whether the AskUserQuestion reminder notification is enabled.
     *
     * @return whether the reminder notification is enabled, default is false (opt-in)
     */
    public boolean getAskUserQuestionNotificationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("askUserQuestionNotificationEnabled") && !config.get("askUserQuestionNotificationEnabled").isJsonNull()) {
            return config.get("askUserQuestionNotificationEnabled").getAsBoolean();
        }

        return false;
    }

    /**
     * Set whether the AskUserQuestion reminder notification is enabled.
     *
     * @param enabled whether to enable
     */
    public void setAskUserQuestionNotificationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("askUserQuestionNotificationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set ask user question notification enabled: " + enabled);
    }

    // ==================== AI Feature Toggle Management ====================

    /**
     * Get whether AI commit message generation is enabled.
     *
     * @return whether commit generation is enabled, default is true
     */
    public boolean getCommitGenerationEnabled() throws IOException {
        return aiFeatureToggleSettingsService.getCommitGenerationEnabled();
    }

    /**
     * Set whether AI commit message generation is enabled.
     *
     * @param enabled whether to enable
     */
    public void setCommitGenerationEnabled(boolean enabled) throws IOException {
        aiFeatureToggleSettingsService.setCommitGenerationEnabled(enabled);
    }

    /**
     * Get whether MCP Gateway acceleration (resident MCP prewarm) is user-enabled.
     *
     * @return whether gateway is user-enabled, default is true
     */
    public boolean getMcpGatewayEnabled() throws IOException {
        return aiFeatureToggleSettingsService.getMcpGatewayEnabled();
    }

    /**
     * Set whether MCP Gateway acceleration is user-enabled. Closing this falls back to
     * direct MCP connections (slower first request); the gateway Node process is stopped.
     *
     * @param enabled whether to enable
     */
    public void setMcpGatewayEnabled(boolean enabled) throws IOException {
        aiFeatureToggleSettingsService.setMcpGatewayEnabled(enabled);
    }

    /**
     * Get whether CLI persistent sessions (long-lived CLI process per tab) are user-enabled.
     *
     * @return whether persistent sessions are user-enabled, default is true
     */
    public boolean getCliPersistentEnabled() throws IOException {
        return aiFeatureToggleSettingsService.getCliPersistentEnabled();
    }

    /**
     * Set whether CLI persistent sessions are user-enabled. Closing this falls back to
     * one-shot CLI processes (每轮重新启动 CLI,恢复现状冷启动开销).
     *
     * @param enabled whether to enable
     */
    public void setCliPersistentEnabled(boolean enabled) throws IOException {
        aiFeatureToggleSettingsService.setCliPersistentEnabled(enabled);
    }

    /**
     * Get the Smithery Registry API key (used by MCP market to search/fetch server configs).
     *
     * @return the API key, or empty string if not configured
     */
    public String getSmitheryApiKey() throws IOException {
        return aiFeatureToggleSettingsService.getSmitheryApiKey();
    }

    /**
     * Set the Smithery Registry API key. Empty/null clears it.
     * <p>Security: the key value itself is never logged — only the set/cleared state
     * is logged. {@code writeConfig} hardens the file to {@code 0600}.
     */
    public void setSmitheryApiKey(String apiKey) throws IOException {
        aiFeatureToggleSettingsService.setSmitheryApiKey(apiKey);
    }

    /**
     * Get whether status bar widget is enabled.
     *
     * @return whether status bar widget is enabled, default is true
     */
    public boolean getStatusBarWidgetEnabled() throws IOException {
        return aiFeatureToggleSettingsService.getStatusBarWidgetEnabled();
    }

    /**
     * Set whether status bar widget is enabled.
     *
     * @param enabled whether to enable
     */
    public void setStatusBarWidgetEnabled(boolean enabled) throws IOException {
        aiFeatureToggleSettingsService.setStatusBarWidgetEnabled(enabled);
    }

    /**
     * Get whether AI session title generation is enabled.
     *
     * @return whether AI title generation is enabled, default is true
     */
    public boolean getAiTitleGenerationEnabled() throws IOException {
        return aiFeatureToggleSettingsService.getAiTitleGenerationEnabled();
    }

    /**
     * Set whether AI session title generation is enabled.
     *
     * @param enabled whether to enable
     */
    public void setAiTitleGenerationEnabled(boolean enabled) throws IOException {
        aiFeatureToggleSettingsService.setAiTitleGenerationEnabled(enabled);
    }

    // ==================== Prompt Enhancer Config Management ====================

    /**
     * Get prompt enhancer configuration with resolved provider availability.
     *
     * <p>The returned object always includes:
     * <ul>
     *     <li>provider: manual override or null</li>
     *     <li>models: per-provider remembered models</li>
     *     <li>effectiveProvider: resolved runtime provider or null</li>
     *     <li>resolutionSource: manual/auto/unavailable</li>
     *     <li>availability: per-provider availability flags</li>
     * </ul>
     */
    public JsonObject getPromptEnhancerConfig() throws IOException {
        return getAiFeatureConfig(
                PROMPT_ENHANCER_KEY,
                DEFAULT_PROMPT_ENHANCER_CLAUDE_MODEL,
                DEFAULT_PROMPT_ENHANCER_CODEX_MODEL
        );
    }

    /**
     * Persist prompt enhancer provider override and per-provider models.
     *
     * @param provider manual provider override, null/blank to restore auto mode
     * @param claudeModel remembered Claude enhancer model
     * @param codexModel remembered Codex enhancer model
     */
    public void setPromptEnhancerConfig(String provider, String claudeModel, String codexModel, String opencodeModel) throws IOException {
        setAiFeatureConfig(
                PROMPT_ENHANCER_KEY,
                provider,
                claudeModel,
                codexModel,
                opencodeModel,
                DEFAULT_PROMPT_ENHANCER_CLAUDE_MODEL,
                DEFAULT_PROMPT_ENHANCER_CODEX_MODEL,
                "prompt enhancer"
        );
    }

    public JsonObject getCommitAiConfig() throws IOException {
        return getAiFeatureConfig(
                COMMIT_AI_KEY,
                DEFAULT_COMMIT_AI_CLAUDE_MODEL,
                DEFAULT_COMMIT_AI_CODEX_MODEL
        );
    }

    public void setCommitAiConfig(String provider, String claudeModel, String codexModel, String opencodeModel) throws IOException {
        setAiFeatureConfig(
                COMMIT_AI_KEY,
                provider,
                claudeModel,
                codexModel,
                opencodeModel,
                DEFAULT_COMMIT_AI_CLAUDE_MODEL,
                DEFAULT_COMMIT_AI_CODEX_MODEL,
                "commit AI"
        );
    }

    private JsonObject getAiFeatureConfig(
            String featureKey,
            String defaultClaudeModel,
            String defaultCodexModel
    ) throws IOException {
        JsonObject rootConfig = readConfig();
        JsonObject featureConfig = getAiFeatureRootObject(rootConfig, featureKey);
        String manualProvider = normalizeAiFeatureProvider(
                featureConfig.has(AI_FEATURE_PROVIDER_KEY) && !featureConfig.get(AI_FEATURE_PROVIDER_KEY).isJsonNull()
                        ? featureConfig.get(AI_FEATURE_PROVIDER_KEY).getAsString()
                        : null
        );
        JsonObject models = getNormalizedAiFeatureModels(featureConfig, defaultClaudeModel, defaultCodexModel);
        JsonObject availability = buildAiFeatureAvailability();
        boolean claudeAvailable = availability.get(CommonConstants.PROVIDER_CLAUDE).getAsBoolean();
        boolean codexAvailable = availability.get(CommonConstants.PROVIDER_CODEX).getAsBoolean();
        boolean opencodeAvailable = availability.get(CommonConstants.PROVIDER_OPENCODE).getAsBoolean();
        ResolvedAiFeatureProvider resolvedProvider = resolveAiFeatureProvider(
                manualProvider,
                claudeAvailable,
                codexAvailable,
                opencodeAvailable
        );

        JsonObject response = new JsonObject();
        if (manualProvider == null) {
            response.add(AI_FEATURE_PROVIDER_KEY, JsonNull.INSTANCE);
        } else {
            response.addProperty(AI_FEATURE_PROVIDER_KEY, manualProvider);
        }
        response.add(AI_FEATURE_MODELS_KEY, models);
        if (resolvedProvider.effectiveProvider() == null) {
            response.add(AI_FEATURE_EFFECTIVE_PROVIDER_KEY, JsonNull.INSTANCE);
        } else {
            response.addProperty(AI_FEATURE_EFFECTIVE_PROVIDER_KEY, resolvedProvider.effectiveProvider());
        }
        response.addProperty(AI_FEATURE_RESOLUTION_SOURCE_KEY, resolvedProvider.resolutionSource());
        response.add(AI_FEATURE_AVAILABILITY_KEY, availability);
        return response;
    }

    private void setAiFeatureConfig(
            String featureKey,
            String provider,
            String claudeModel,
            String codexModel,
            String opencodeModel,
            String defaultClaudeModel,
            String defaultCodexModel,
            String featureLabel
    ) throws IOException {
        JsonObject config = readConfig();
        JsonObject featureConfig = getAiFeatureRootObject(config, featureKey);
        String normalizedProvider = normalizeAiFeatureProvider(provider);
        if (normalizedProvider == null) {
            featureConfig.add(AI_FEATURE_PROVIDER_KEY, JsonNull.INSTANCE);
        } else {
            featureConfig.addProperty(AI_FEATURE_PROVIDER_KEY, normalizedProvider);
        }
        featureConfig.add(
                AI_FEATURE_MODELS_KEY,
                // 写入路径同样归一化 canonical→role id(M2):与读取路径 getNormalizedAiFeatureModels
                // 对称,让 config.json 持久化的即为 role id,避免绕过读取归一化的路径拿到 canonical。
                createAiFeatureModels(
                        normalizeAiFeatureClaudeModel(claudeModel),
                        codexModel,
                        opencodeModel,
                        defaultClaudeModel,
                        defaultCodexModel
                )
        );

        config.add(featureKey, featureConfig);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set " + featureLabel + " config: provider=" + normalizedProvider);
    }

    private JsonObject getAiFeatureRootObject(JsonObject rootConfig, String featureKey) {
        if (rootConfig.has(featureKey) && rootConfig.get(featureKey).isJsonObject()) {
            return rootConfig.getAsJsonObject(featureKey);
        }
        return new JsonObject();
    }

    private JsonObject buildAiFeatureAvailability() {
        JsonObject availability = new JsonObject();
        availability.addProperty(CommonConstants.PROVIDER_CLAUDE, isAiFeatureProviderAvailable(CommonConstants.PROVIDER_CLAUDE));
        availability.addProperty(CommonConstants.PROVIDER_CODEX, isAiFeatureProviderAvailable(CommonConstants.PROVIDER_CODEX));
        availability.addProperty(CommonConstants.PROVIDER_OPENCODE, isAiFeatureProviderAvailable(CommonConstants.PROVIDER_OPENCODE));
        return availability;
    }

    private boolean isAiFeatureProviderAvailable(String provider) {
        try {
            // CLI 模式下用可执行文件,不再依赖 npm SDK 包;provider 可用性由配置存在性判断,
            // exe 检测在 spawn 时完成(findCliExecutable)。
            if (CommonConstants.PROVIDER_CODEX.equals(provider)) {
                return getActiveCodexProvider() != null;
            }
            if (CommonConstants.PROVIDER_OPENCODE.equals(provider)) {
                return true;
            }
            return getActiveClaudeProvider() != null;
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to resolve AI feature availability for " + provider + ": " + e.getMessage());
            return false;
        }
    }

    private JsonObject getNormalizedAiFeatureModels(
            JsonObject featureConfig,
            String defaultClaudeModel,
            String defaultCodexModel
    ) {
        if (featureConfig != null
                && featureConfig.has(AI_FEATURE_MODELS_KEY)
                && featureConfig.get(AI_FEATURE_MODELS_KEY).isJsonObject()) {
            JsonObject rawModels = featureConfig.getAsJsonObject(AI_FEATURE_MODELS_KEY);
            String claudeModel = rawModels.has(CommonConstants.PROVIDER_CLAUDE) && !rawModels.get(CommonConstants.PROVIDER_CLAUDE).isJsonNull()
                    ? rawModels.get(CommonConstants.PROVIDER_CLAUDE).getAsString()
                    : null;
            String codexModel = rawModels.has(CommonConstants.PROVIDER_CODEX) && !rawModels.get(CommonConstants.PROVIDER_CODEX).isJsonNull()
                    ? rawModels.get(CommonConstants.PROVIDER_CODEX).getAsString()
                    : null;
            String opencodeModel = rawModels.has(CommonConstants.PROVIDER_OPENCODE) && !rawModels.get(CommonConstants.PROVIDER_OPENCODE).isJsonNull()
                    ? rawModels.get(CommonConstants.PROVIDER_OPENCODE).getAsString()
                    : null;
            return createAiFeatureModels(normalizeAiFeatureClaudeModel(claudeModel), codexModel, opencodeModel, defaultClaudeModel, defaultCodexModel);
        }
        return createAiFeatureModels(null, null, null, defaultClaudeModel, defaultCodexModel);
    }

    /**
     * 归一化 AI 功能(promptEnhancer / commitAi)记忆的 Claude 模型 id:把历史遗留的官方
     * canonical id(如 claude-sonnet-4-6)对齐到 registry 的 role id 体系(claude-role-sonnet),
     * 避免前端 AiFeatureProviderModelPanel 因 id 不一致触发兜底 prepend 而出现幽灵模型项。
     *
     * <p>安全网:已在 registry 中的 id(role id 或用户自定义模型)原样保留——这保证用户自定义的
     * {@code claude-sonnet-*} 同前缀模型不会被误归一化。仅当 canonical id 不在 registry 时才转换。
     *
     * @param rawModel 持久化的 Claude 模型 id(可为 null/空)
     * @return 归一化后的模型 id;null/空原样返回(交由 createAiFeatureModels 用默认值兜底)
     */
    private String normalizeAiFeatureClaudeModel(String rawModel) {
        if (rawModel == null || rawModel.trim().isEmpty()) {
            return rawModel;
        }
        String trimmed = rawModel.trim();
        if (getModelRegistry().find(CommonConstants.PROVIDER_CLAUDE, trimmed).isPresent()) {
            return trimmed;
        }
        String roleId = ClaudeRole.canonicalIdToRoleId(trimmed);
        return roleId != null ? roleId : trimmed;
    }

    private JsonObject createAiFeatureModels(
            String claudeModel,
            String codexModel,
            String opencodeModel,
            String defaultClaudeModel,
            String defaultCodexModel
    ) {
        JsonObject models = new JsonObject();
        models.addProperty(
                CommonConstants.PROVIDER_CLAUDE,
                normalizeAiFeatureModel(claudeModel, defaultClaudeModel)
        );
        models.addProperty(
                CommonConstants.PROVIDER_CODEX,
                normalizeAiFeatureModel(codexModel, defaultCodexModel)
        );
        models.addProperty(
                CommonConstants.PROVIDER_OPENCODE,
                normalizeAiFeatureModel(opencodeModel, DEFAULT_AI_FEATURE_OPENCODE_MODEL)
        );
        return models;
    }

    private ResolvedAiFeatureProvider resolveAiFeatureProvider(
            String manualProvider,
            boolean claudeAvailable,
            boolean codexAvailable,
            boolean opencodeAvailable
    ) {
        if (manualProvider != null) {
            boolean manualProviderAvailable = CommonConstants.PROVIDER_OPENCODE.equals(manualProvider)
                    ? opencodeAvailable
                    : CommonConstants.PROVIDER_CODEX.equals(manualProvider)
                            ? codexAvailable
                            : claudeAvailable;
            if (manualProviderAvailable) {
                return new ResolvedAiFeatureProvider(manualProvider, AI_FEATURE_RESOLUTION_MANUAL);
            }
            return new ResolvedAiFeatureProvider(null, AI_FEATURE_RESOLUTION_UNAVAILABLE);
        }
        if (codexAvailable) {
            return new ResolvedAiFeatureProvider(CommonConstants.PROVIDER_CODEX, AI_FEATURE_RESOLUTION_AUTO);
        }
        if (claudeAvailable) {
            return new ResolvedAiFeatureProvider(CommonConstants.PROVIDER_CLAUDE, AI_FEATURE_RESOLUTION_AUTO);
        }
        if (opencodeAvailable) {
            return new ResolvedAiFeatureProvider(CommonConstants.PROVIDER_OPENCODE, AI_FEATURE_RESOLUTION_AUTO);
        }
        return new ResolvedAiFeatureProvider(null, AI_FEATURE_RESOLUTION_UNAVAILABLE);
    }

    private String normalizeAiFeatureProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String normalized = provider.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }
        if (CommonConstants.PROVIDER_CLAUDE.equals(normalized)
                || CommonConstants.PROVIDER_CODEX.equals(normalized)
                || CommonConstants.PROVIDER_OPENCODE.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private String normalizeAiFeatureModel(String model, String defaultValue) {
        if (model == null) {
            return defaultValue;
        }
        String normalized = model.trim();
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    private record ResolvedAiFeatureProvider(String effectiveProvider, String resolutionSource) {
    }

    // ==================== Codex Provider Management ====================

    public List<JsonObject> getCodexProviders() throws IOException {
        return providerSettingsService.getCodexProviders();
    }

    public JsonObject getActiveCodexProvider() throws IOException {
        return providerSettingsService.getActiveCodexProvider();
    }

    public void addCodexProvider(JsonObject provider) throws IOException {
        providerSettingsService.addCodexProvider(provider);
    }

    public void updateCodexProvider(String id, JsonObject updates) throws IOException {
        providerSettingsService.updateCodexProvider(id, updates);
    }

    public DeleteResult deleteCodexProvider(String id) {
        return providerSettingsService.deleteCodexProvider(id);
    }

    public void switchCodexProvider(String id) throws IOException {
        providerSettingsService.switchCodexProvider(id);
    }

    public void applyActiveProviderToCodexSettings() throws IOException {
        providerSettingsService.applyActiveProviderToCodexSettings();
    }

    public JsonObject getCurrentCodexConfig() throws IOException {
        return providerSettingsService.getCurrentCodexConfig();
    }

    public boolean isCodexCliLoginAvailable() {
        return providerSettingsService.isCodexCliLoginAvailable();
    }

    public JsonObject readCodexCliLoginAccountInfo() {
        return providerSettingsService.readCodexCliLoginAccountInfo();
    }

    public boolean isCodexLocalConfigAuthorized() throws IOException {
        return providerSettingsService.isCodexLocalConfigAuthorized();
    }

    public void setCodexLocalConfigAuthorized(boolean authorized) throws IOException {
        providerSettingsService.setCodexLocalConfigAuthorized(authorized);
    }

    public String getCodexRuntimeAccessMode() throws IOException {
        return providerSettingsService.getCodexRuntimeAccessMode();
    }

    /**
     * Returns whether the plugin may manage the currently active Codex config.toml.
     * Managed providers own the active config written by the plugin, while local
     * CLI configuration still requires explicit authorization.
     * (Merged from upstream db9874a4b)
     */
    public boolean isCodexConfigManagementAllowed() throws IOException {
        String accessMode = getCodexRuntimeAccessMode();
        if (CODEX_RUNTIME_ACCESS_CLI_LOGIN.equals(accessMode)) {
            return isCodexLocalConfigAuthorized();
        }
        return CODEX_RUNTIME_ACCESS_MANAGED.equals(accessMode);
    }

    public String getClaudeCliPath() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CLAUDE_CLI_PATH_KEY) || config.get(CLAUDE_CLI_PATH_KEY).isJsonNull()) {
            return "";
        }
        String path = config.get(CLAUDE_CLI_PATH_KEY).getAsString();
        return path != null ? path.trim() : "";
    }

    public void setClaudeCliPath(String path) throws IOException {
        JsonObject config = readConfig();
        String normalized = path != null ? path.trim() : "";
        if (normalized.isEmpty()) {
            config.remove(CLAUDE_CLI_PATH_KEY);
        } else {
            config.addProperty(CLAUDE_CLI_PATH_KEY, normalized);
        }
        writeConfig(config);
        LOG.info("[CodemossSettings] Set Claude CLI path: " + (normalized.isEmpty() ? "(auto)" : normalized));
    }

    public void saveCodexProviderOrder(List<String> orderedIds) throws IOException {
        providerSettingsService.saveProviderOrder(orderedIds);
    }

    // ==================== OpenCode Provider Management ==================== (对称 codex 段)

    public List<JsonObject> getOpenCodeProviders() throws IOException {
        return providerSettingsService.getOpenCodeProviders();
    }

    public JsonObject getActiveOpenCodeProvider() throws IOException {
        return providerSettingsService.getActiveOpenCodeProvider();
    }

    public void addOpenCodeProvider(JsonObject provider) throws IOException {
        providerSettingsService.addOpenCodeProvider(provider);
    }

    public void updateOpenCodeProvider(String id, JsonObject updates) throws IOException {
        providerSettingsService.updateOpenCodeProvider(id, updates);
    }

    public DeleteResult deleteOpenCodeProvider(String id) {
        return providerSettingsService.deleteOpenCodeProvider(id);
    }

    public void switchOpenCodeProvider(String id) throws IOException {
        providerSettingsService.switchOpenCodeProvider(id);
    }

    public void applyActiveProviderToOpenCodeSettings() throws IOException {
        providerSettingsService.applyActiveProviderToOpenCodeSettings();
    }

    public JsonObject getCurrentOpenCodeConfig() throws IOException {
        return providerSettingsService.getCurrentOpenCodeConfig();
    }

    public boolean isOpencodeLocalConfigAuthorized() throws IOException {
        return providerSettingsService.isOpencodeLocalConfigAuthorized();
    }

    public void setOpencodeLocalConfigAuthorized(boolean authorized) throws IOException {
        providerSettingsService.setOpencodeLocalConfigAuthorized(authorized);
    }

    public String getOpenCodeRuntimeAccessMode() throws IOException {
        return providerSettingsService.getOpenCodeRuntimeAccessMode();
    }

    public void saveOpenCodeProviderOrder(List<String> orderedIds) throws IOException {
        providerSettingsService.saveProviderOrder(orderedIds);
    }

    // ==================== Model Registry Config Management (delegates to ModelRegistrySettingsService) ====================

    /**
     * Read the effective model registry = merge(persisted user layer, read-only defaults).
     * Read-only defaults (Claude 4 roles from settings.json + Codex from config.toml) are
     * computed at runtime and never persisted.
     */
    public ModelRegistryConfig getModelRegistry() {
        return modelRegistrySettingsService.getModelRegistry();
    }

    /**
     * Save the user-layer model registry. Read-only items are stripped (never persisted).
     * New entries conflicting with read-only default keys are rejected; validation runs on
     * the effective registry (user layer + read-only defaults) so the read-only roles
     * guarantee "at least one enabled" — an empty user layer is therefore valid.
     */
    public ModelConfigValidator.ValidationResult setModelRegistry(ModelRegistryConfig registry) {
        return modelRegistrySettingsService.setModelRegistry(registry);
    }

    /**
     * 序列化当前 effective registry 为 JSON 字符串,供提供商切换/登录后推送刷新(下发前端)。
     *
     * <p>必须复用 {@link ModelRegistryService#serialize} 以下发 supportedReasoningLevels 等
     * 派生字段;否则前端 ReasoningSelect 拿不到档位会整体隐藏(H3)。与写盘路径
     * {@code serializeModelRegistry}({@link #setModelRegistry},只持久化原始字段、派生字段不落盘)
     * 刻意区分:下发需派生字段,写盘不需要(避免双写)。
     */
    public String getModelRegistryJson() {
        return modelRegistrySettingsService.getModelRegistryJson();
    }

    // ==================== Runtime Policy Config Management ====================

    private static final String RUNTIME_POLICY_KEY = "runtime";

    /**
     * 读取路由策略配置。配置缺失或损坏时回退默认配置。
     */
    public com.github.claudecodegui.config.RuntimePolicyConfig getRuntimePolicy() {
        try {
            JsonObject config = readConfig();
            if (!config.has(RUNTIME_POLICY_KEY) || !config.get(RUNTIME_POLICY_KEY).isJsonObject()) {
                LOG.info("[CodemossSettings] No runtime policy config found, using default");
                return RuntimePolicyConfig.getDefault();
            }
            JsonObject runtimeObj = config.getAsJsonObject(RUNTIME_POLICY_KEY);
            // mergeWithDefaults: 向后兼容。存量 config.json 在新 provider(如 opencode)加入默认策略前
            // 持久化,缺该 provider → of(OPENCODE)=null → resolve 抛 "Provider disabled/unknown"。
            // 以默认补全缺失 provider,保留用户对已知 provider 的自定义。
            return parseRuntimePolicy(runtimeObj).mergeWithDefaults();
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read runtime policy, using default: " + e.getMessage());
            return com.github.claudecodegui.config.RuntimePolicyConfig.getDefault();
        }
    }

    private com.github.claudecodegui.config.RuntimePolicyConfig parseRuntimePolicy(JsonObject runtimeObj) {
        var config = new com.github.claudecodegui.config.RuntimePolicyConfig();
        var providers = new java.util.LinkedHashMap<ProviderType,
                com.github.claudecodegui.config.ProviderRuntimePolicy>();

        if (runtimeObj.has("providers") && runtimeObj.get("providers").isJsonObject()) {
            JsonObject providersObj = runtimeObj.getAsJsonObject("providers");
            for (String key : providersObj.keySet()) {
                // 严格解析:ProviderType.fromString 会把未知键静默降级为 CLAUDE,
                // 若配置里 codex 之后还有笔误的键(如 "cluade"),会把 claude 的策略覆盖掉。
                // 此处显式校验,无法识别的键告警并跳过。
                ProviderType pt = ProviderType.fromValue(
                        key.trim().toLowerCase(java.util.Locale.ROOT)
                ).orElse(null);
                if (pt == null) {
                    LOG.warn("[CodemossSettings] Unrecognized runtime policy provider key '"
                            + key + "', skipping");
                    continue;
                }
                if (providersObj.get(key).isJsonObject()) {
                    JsonObject policyObj = providersObj.getAsJsonObject(key);
                    // runtime 维度已消除:只读 enabled;legacy supported/default 字段忽略
                    // (向后兼容存量 config.json)。
                    boolean enabled = policyObj.has("enabled") && policyObj.get("enabled").getAsBoolean();
                    try {
                        providers.put(pt, new com.github.claudecodegui.config.ProviderRuntimePolicy(enabled));
                    } catch (Exception e) {
                        LOG.warn("[CodemossSettings] Invalid runtime policy for " + key + ": " + e.getMessage());
                    }
                }
            }
        }

        config.setProviders(providers);

        // 校验：损坏则回退默认
        var validationResult = com.github.claudecodegui.config.RuntimePolicyValidator.validate(config);
        if (!validationResult.isValid()) {
            LOG.warn("[CodemossSettings] Loaded runtime policy is invalid, falling back to default: "
                    + validationResult.errors());
            return com.github.claudecodegui.config.RuntimePolicyConfig.getDefault();
        }

        return config;
    }

    // ==================== Custom Model Pricing Management ====================

    public void setCustomModelPricing(String provider, Map<String, ModelPricing> pricing) throws IOException {
        JsonObject config = readConfig();

        JsonObject root;
        if (config.has("customModelPricing") && config.get("customModelPricing").isJsonObject()) {
            root = config.getAsJsonObject("customModelPricing");
        } else {
            root = new JsonObject();
            config.add("customModelPricing", root);
        }

        if (pricing == null || pricing.isEmpty()) {
            root.remove(provider);
        } else {
            JsonObject providerNode = new JsonObject();
            for (Map.Entry<String, ModelPricing> entry : pricing.entrySet()) {
                providerNode.add(entry.getKey(), serializeModelPricing(entry.getValue()));
            }
            root.add(provider, providerNode);
        }

        writeConfig(config);
        LOG.info("[CodemossSettings] Set user model pricing for " + provider
                + ": " + (pricing == null ? 0 : pricing.size()) + " models");
    }

    private JsonObject serializeModelPricing(ModelPricing pricing) {
        JsonObject node = new JsonObject();
        if (pricing.inputCostPer1M() != null) { node.addProperty("inputCostPer1M", pricing.inputCostPer1M()); }
        if (pricing.outputCostPer1M() != null) { node.addProperty("outputCostPer1M", pricing.outputCostPer1M()); }
        if (pricing.cacheWriteCostPer1M() != null) { node.addProperty("cacheWriteCostPer1M", pricing.cacheWriteCostPer1M()); }
        if (pricing.cacheReadCostPer1M() != null) { node.addProperty("cacheReadCostPer1M", pricing.cacheReadCostPer1M()); }
        return node;
    }

    public void setCustomModelContextWindows(String provider, Map<String, Integer> contextWindows) throws IOException {
        if (!CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(provider)) {
            LOG.warn("[CodemossSettings] Ignored custom context windows for unsupported provider: " + provider);
            return;
        }
        JsonObject config = readConfig();

        JsonObject root;
        if (config.has("customModelContextWindows") && config.get("customModelContextWindows").isJsonObject()) {
            root = config.getAsJsonObject("customModelContextWindows");
        } else {
            root = new JsonObject();
            config.add("customModelContextWindows", root);
        }

        if (contextWindows == null || contextWindows.isEmpty()) {
            root.remove(CommonConstants.PROVIDER_CODEX);
        } else {
            JsonObject providerNode = new JsonObject();
            for (Map.Entry<String, Integer> entry : contextWindows.entrySet()) {
                Integer value = entry.getValue();
                if (value != null && value >= 1_000 && value % 1_000 == 0) {
                    providerNode.addProperty(entry.getKey(), value);
                }
            }
            if (providerNode.size() == 0) {
                root.remove(CommonConstants.PROVIDER_CODEX);
            } else {
                root.add(CommonConstants.PROVIDER_CODEX, providerNode);
            }
        }

        writeConfig(config);
        LOG.info("[CodemossSettings] Set user model context windows for codex"
                + ": " + (contextWindows == null ? 0 : contextWindows.size()) + " models");
    }

    // ==================== Grok settings (merged from upstream db9874a4b) ====================

    public static final String GROK_AUTH_METHOD_AUTO = "auto";
    public static final String GROK_AUTH_METHOD_OAUTH = "oauth";
    public static final String GROK_AUTH_METHOD_API_KEY = "api_key";
    public static final String DEFAULT_GROK_AUTH_METHOD = GROK_AUTH_METHOD_OAUTH;

    public String getGrokAuthMethod() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_GROK) || config.get(CommonConstants.PROVIDER_GROK).isJsonNull()) {
            return DEFAULT_GROK_AUTH_METHOD;
        }
        JsonObject grok = config.getAsJsonObject(CommonConstants.PROVIDER_GROK);
        if (!grok.has("authMethod") || grok.get("authMethod").isJsonNull()) {
            return DEFAULT_GROK_AUTH_METHOD;
        }
        String method = grok.get("authMethod").getAsString();
        return normalizeGrokAuthMethod(method);
    }

    public void setGrokAuthMethod(String method) throws IOException {
        String normalized = normalizeGrokAuthMethod(method);
        JsonObject config = readConfig();
        JsonObject grok = config.has(CommonConstants.PROVIDER_GROK) && !config.get(CommonConstants.PROVIDER_GROK).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_GROK)
                : new JsonObject();
        grok.addProperty("authMethod", normalized);
        config.add(CommonConstants.PROVIDER_GROK, grok);
        writeConfig(config);
        LOG.info("[CodemossSettingsService] Set grok.authMethod=" + normalized);
    }

    public String getGrokApiKey() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_GROK) || config.get(CommonConstants.PROVIDER_GROK).isJsonNull()) {
            return "";
        }
        JsonObject grok = config.getAsJsonObject(CommonConstants.PROVIDER_GROK);
        if (!grok.has("apiKey") || grok.get("apiKey").isJsonNull()) {
            return "";
        }
        return grok.get("apiKey").getAsString();
    }

    public void setGrokApiKey(String apiKey) throws IOException {
        JsonObject config = readConfig();
        JsonObject grok = config.has(CommonConstants.PROVIDER_GROK) && !config.get(CommonConstants.PROVIDER_GROK).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_GROK)
                : new JsonObject();
        String value = apiKey != null ? apiKey.trim() : "";
        if (value.isEmpty()) {
            grok.remove("apiKey");
        } else {
            grok.addProperty("apiKey", value);
        }
        config.add(CommonConstants.PROVIDER_GROK, grok);
        writeConfig(config);
        LOG.info("[CodemossSettingsService] Updated grok.apiKey (present=" + !value.isEmpty() + ")");
    }

    public static String normalizeGrokAuthMethod(String method) {
        if (method == null || method.trim().isEmpty()) {
            return DEFAULT_GROK_AUTH_METHOD;
        }
        String m = method.trim().toLowerCase();
        if (GROK_AUTH_METHOD_API_KEY.equals(m) || "xai.api_key".equals(m) || "apikey".equals(m)) {
            return GROK_AUTH_METHOD_API_KEY;
        }
        if (GROK_AUTH_METHOD_AUTO.equals(m)) {
            return GROK_AUTH_METHOD_AUTO;
        }
        if (GROK_AUTH_METHOD_OAUTH.equals(m) || "cached_token".equals(m) || "cli_login".equals(m) || "grok.com".equals(m)) {
            return GROK_AUTH_METHOD_OAUTH;
        }
        return DEFAULT_GROK_AUTH_METHOD;
    }

    public String getGrokApiBaseUrl() throws IOException {
        return getGrokStringSetting("apiBaseUrl");
    }

    public void setGrokApiBaseUrl(String url) throws IOException {
        setGrokStringSetting("apiBaseUrl", url);
        LOG.info("[CodemossSettingsService] Set grok.apiBaseUrl=" + redactUrl(url));
    }

    public String getGrokOauthBaseUrl() throws IOException {
        return getGrokStringSetting("oauthBaseUrl");
    }

    public void setGrokOauthBaseUrl(String url) throws IOException {
        setGrokStringSetting("oauthBaseUrl", url);
        LOG.info("[CodemossSettingsService] Set grok.oauthBaseUrl=" + redactUrl(url));
    }

    public JsonObject getGrokEnv() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_GROK) || config.get(CommonConstants.PROVIDER_GROK).isJsonNull()) {
            return new JsonObject();
        }
        JsonObject grok = config.getAsJsonObject(CommonConstants.PROVIDER_GROK);
        if (grok.has("env") && grok.get("env").isJsonObject()) {
            return grok.getAsJsonObject("env");
        }
        return new JsonObject();
    }

    public void setGrokEnv(JsonObject env) throws IOException {
        JsonObject config = readConfig();
        JsonObject grok = config.has(CommonConstants.PROVIDER_GROK) && !config.get(CommonConstants.PROVIDER_GROK).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_GROK)
                : new JsonObject();
        if (env == null || env.size() == 0) {
            grok.remove("env");
        } else {
            grok.add("env", env);
        }
        config.add(CommonConstants.PROVIDER_GROK, grok);
        writeConfig(config);
    }

    public String getGrokGatewayOrigin() throws IOException {
        return getGrokStringSetting("gatewayOrigin");
    }

    public void setGrokGatewayOrigin(String origin) throws IOException {
        setGrokStringSetting("gatewayOrigin", origin);
        LOG.info("[CodemossSettingsService] Set grok.gatewayOrigin=" + redactUrl(origin));
    }

    public String resolveGrokBaseUrlForAuth(String authMethod, String explicitBaseUrl) throws IOException {
        if (explicitBaseUrl != null && !explicitBaseUrl.trim().isEmpty()) {
            return explicitBaseUrl.trim();
        }
        String method = normalizeGrokAuthMethod(authMethod);
        if (GROK_AUTH_METHOD_API_KEY.equals(method)) {
            return getGrokApiBaseUrl();
        }
        if (GROK_AUTH_METHOD_OAUTH.equals(method)) {
            return getGrokOauthBaseUrl();
        }
        String oauth = getGrokOauthBaseUrl();
        if (!oauth.isEmpty()) {
            return oauth;
        }
        return getGrokApiBaseUrl();
    }

    private String getGrokStringSetting(String field) throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_GROK) || config.get(CommonConstants.PROVIDER_GROK).isJsonNull()) {
            return "";
        }
        JsonObject grok = config.getAsJsonObject(CommonConstants.PROVIDER_GROK);
        if (!grok.has(field) || grok.get(field).isJsonNull()) {
            return "";
        }
        return grok.get(field).getAsString();
    }

    private void setGrokStringSetting(String field, String value) throws IOException {
        JsonObject config = readConfig();
        JsonObject grok = config.has(CommonConstants.PROVIDER_GROK) && !config.get(CommonConstants.PROVIDER_GROK).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_GROK)
                : new JsonObject();
        String v = value != null ? value.trim() : "";
        if (v.isEmpty()) {
            grok.remove(field);
        } else {
            grok.addProperty(field, v);
        }
        config.add(CommonConstants.PROVIDER_GROK, grok);
        writeConfig(config);
    }

    private String redactUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "(empty)";
        }
        return url.trim();
    }

    // ==================== DSH connection settings (merged from upstream db9874a4b) ====================
    // Thin connection only: bin / host / port / autoStart. Provider keys and model
    // catalog stay in the DSH Web UI ($DSH_HOME); the plugin never writes them.

    private static final String DSH_DEFAULT_HOST = "127.0.0.1";
    private static final int DSH_DEFAULT_PORT = 3080;

    public String getDshBin() throws IOException {
        return getDshStringSetting("bin");
    }

    public void setDshBin(String value) throws IOException {
        setDshStringSetting("bin", value);
    }

    public String getDshHost() throws IOException {
        String value = getDshStringSetting("host");
        return value.isEmpty() ? DSH_DEFAULT_HOST : value;
    }

    public void setDshHost(String value) throws IOException {
        setDshStringSetting("host", value);
    }

    public int getDshPort() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_DSH) || config.get(CommonConstants.PROVIDER_DSH).isJsonNull()) {
            return DSH_DEFAULT_PORT;
        }
        JsonObject dsh = config.getAsJsonObject(CommonConstants.PROVIDER_DSH);
        if (!dsh.has("port") || dsh.get("port").isJsonNull()) {
            return DSH_DEFAULT_PORT;
        }
        try {
            int port = dsh.get("port").getAsInt();
            return port > 0 && port <= 65535 ? port : DSH_DEFAULT_PORT;
        } catch (Exception e) {
            return DSH_DEFAULT_PORT;
        }
    }

    public void setDshPort(int port) throws IOException {
        JsonObject config = readConfig();
        JsonObject dsh = config.has(CommonConstants.PROVIDER_DSH) && !config.get(CommonConstants.PROVIDER_DSH).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_DSH)
                : new JsonObject();
        if (port > 0 && port <= 65535 && port != DSH_DEFAULT_PORT) {
            dsh.addProperty("port", port);
        } else {
            dsh.remove("port");
        }
        config.add(CommonConstants.PROVIDER_DSH, dsh);
        writeConfig(config);
    }

    public boolean getDshAutoStart() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_DSH) || config.get(CommonConstants.PROVIDER_DSH).isJsonNull()) {
            return true;
        }
        JsonObject dsh = config.getAsJsonObject(CommonConstants.PROVIDER_DSH);
        if (!dsh.has("autoStart") || dsh.get("autoStart").isJsonNull()) {
            return true;
        }
        try {
            return dsh.get("autoStart").getAsBoolean();
        } catch (Exception e) {
            return true;
        }
    }

    public void setDshAutoStart(boolean autoStart) throws IOException {
        JsonObject config = readConfig();
        JsonObject dsh = config.has(CommonConstants.PROVIDER_DSH) && !config.get(CommonConstants.PROVIDER_DSH).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_DSH)
                : new JsonObject();
        if (autoStart) {
            dsh.remove("autoStart");
        } else {
            dsh.addProperty("autoStart", false);
        }
        config.add(CommonConstants.PROVIDER_DSH, dsh);
        writeConfig(config);
    }

    private String getDshStringSetting(String field) throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CommonConstants.PROVIDER_DSH) || config.get(CommonConstants.PROVIDER_DSH).isJsonNull()) {
            return "";
        }
        JsonObject dsh = config.getAsJsonObject(CommonConstants.PROVIDER_DSH);
        if (!dsh.has(field) || dsh.get(field).isJsonNull()) {
            return "";
        }
        return dsh.get(field).getAsString();
    }

    private void setDshStringSetting(String field, String value) throws IOException {
        JsonObject config = readConfig();
        JsonObject dsh = config.has(CommonConstants.PROVIDER_DSH) && !config.get(CommonConstants.PROVIDER_DSH).isJsonNull()
                ? config.getAsJsonObject(CommonConstants.PROVIDER_DSH)
                : new JsonObject();
        String v = value != null ? value.trim() : "";
        if (v.isEmpty()) {
            dsh.remove(field);
        } else {
            dsh.addProperty(field, v);
        }
        config.add(CommonConstants.PROVIDER_DSH, dsh);
        writeConfig(config);
    }

    // ==================== Notification focus settings (merged from upstream db9874a4b) ====================

    /**
     * Get whether the AskUserQuestion reminder sound notification is enabled.
     *
     * @return whether the reminder sound is enabled, default is false (opt-in)
     */
    public boolean getAskUserQuestionSoundNotificationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("askUserQuestionSoundNotificationEnabled")
                && !config.get("askUserQuestionSoundNotificationEnabled").isJsonNull()) {
            return config.get("askUserQuestionSoundNotificationEnabled").getAsBoolean();
        }

        return false;
    }

    /**
     * Set whether the AskUserQuestion reminder sound notification is enabled.
     *
     * @param enabled whether to enable
     */
    public void setAskUserQuestionSoundNotificationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("askUserQuestionSoundNotificationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set ask user question sound notification enabled: " + enabled);
    }

    /**
     * Get whether visual system notifications should only be shown when the IDE is not focused.
     *
     * @return whether only-when-unfocused is enabled, default is false
     */
    public boolean getSystemNotificationOnlyWhenUnfocused() throws IOException {
        JsonObject config = readConfig();

        if (config.has("systemNotificationOnlyWhenUnfocused")
                && !config.get("systemNotificationOnlyWhenUnfocused").isJsonNull()) {
            return config.get("systemNotificationOnlyWhenUnfocused").getAsBoolean();
        }

        return false;
    }

    /**
     * Set whether visual system notifications should only be shown when the IDE is not focused.
     *
     * @param enabled whether to enable
     */
    public void setSystemNotificationOnlyWhenUnfocused(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("systemNotificationOnlyWhenUnfocused", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set system notification only when unfocused: " + enabled);
    }
}
