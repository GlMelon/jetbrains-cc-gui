package com.github.claudecodegui.settings;

import com.github.claudecodegui.dependency.DependencyManager;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.ConflictStrategy;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.model.PromptScope;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelConfigValidator;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.config.ReadOnlyDefaultModels;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import com.github.claudecodegui.util.FontConfigService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonNull;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Codemoss configuration service (Facade pattern).
 * Delegates specific functionality to specialized managers.
 */
public class CodemossSettingsService {

    public static CodemossSettingsService getInstance() {
        return ApplicationManager.getApplication().getService(CodemossSettingsService.class);
    }

    private static final Logger LOG = Logger.getInstance(CodemossSettingsService.class);
    private static final int CONFIG_VERSION = 2;
    private static final String CODEX_SANDBOX_MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS = "danger-full-access";
    private static final String UI_FONT_CONFIG_KEY = "uiFont";
    private static final String CODE_FONT_CONFIG_KEY = "codeFont";
    // Shared by both UI font and code font: the persisted JSON keys ("mode" /
    // "customFontPath") and the set of valid modes are identical for the two font kinds,
    // so they reuse these UI_FONT_*-named constants. They are NOT UI-only despite the name.
    private static final String UI_FONT_MODE_KEY = "mode";
    private static final String UI_FONT_CUSTOM_PATH_KEY = "customFontPath";
    private static final String CLAUDE_INVOCATION_MODE_KEY = "claudeInvocationMode";
    private static final String CLAUDE_CLI_PATH_KEY = "claudeCliPath";
    private static final Set<String> VALID_UI_FONT_MODES = Set.of(
            FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR,
            FontConfigService.UI_FONT_MODE_CUSTOM_FILE
    );
    public static final String CODEX_RUNTIME_ACCESS_INACTIVE = "inactive";
    public static final String CODEX_RUNTIME_ACCESS_MANAGED = "managed";
    public static final String CODEX_RUNTIME_ACCESS_CLI_LOGIN = "cli_login";
    private static final String COMMIT_AI_KEY = "commitAi";
    private static final String PROMPT_ENHANCER_KEY = "promptEnhancer";
    private static final String MODEL_REGISTRY_KEY = "models";
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
    // Appearance config (theme preference / font size / diff theme / per-theme colors).
    // Persisted so the webview can restore appearance after IDE cache invalidation
    // (localStorage otherwise lives inside the wiped JCEF cache directory).
    private static final String APPEARANCE_CONFIG_KEY = "appearance";
    private static final String APPEARANCE_THEME_PREFERENCE_KEY = "themePreference";
    private static final String APPEARANCE_FONT_SIZE_KEY = "fontSizeLevel";
    private static final String APPEARANCE_DIFF_THEME_KEY = "diffTheme";
    private static final String APPEARANCE_CHAT_BG_KEY = "chatBgColor";
    private static final String APPEARANCE_USER_MSG_KEY = "userMsgColor";
    private static final Set<String> VALID_THEME_PREFERENCES = Set.of("system", "light", "dark");
    private static final Set<String> VALID_DIFF_THEMES = Set.of("follow", "editor", "light", "soft-dark");
    private static final java.util.regex.Pattern HEX_COLOR_PATTERN =
            java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final Gson gson;

    // Managers
    private final ConfigPathManager pathManager;
    private final ClaudeSettingsManager claudeSettingsManager;
    private final CodexSettingsManager codexSettingsManager;
    private final CodexMcpServerManager codexMcpServerManager;
    private final WorkingDirectoryManager workingDirectoryManager;
    private final AgentManager agentManager;
    private final McpServerManager mcpServerManager;
    private final ProviderManager providerManager;
    private final CodexProviderManager codexProviderManager;

    public CodemossSettingsService() {
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

        // Initialize ConfigPathManager
        this.pathManager = new ConfigPathManager();

        // Initialize ClaudeSettingsManager
        this.claudeSettingsManager = new ClaudeSettingsManager(gson, pathManager);

        // Initialize WorkingDirectoryManager
        this.workingDirectoryManager = new WorkingDirectoryManager(
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        // Initialize AgentManager
        this.agentManager = new AgentManager(gson, pathManager);

        // Initialize McpServerManager
        this.mcpServerManager = new McpServerManager(
                gson,
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                claudeSettingsManager
        );

        // Initialize ProviderManager
        this.providerManager = new ProviderManager(
                gson,
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                pathManager,
                claudeSettingsManager
        );

        // Initialize CodexSettingsManager
        this.codexSettingsManager = new CodexSettingsManager(gson);

        // Initialize CodexMcpServerManager
        this.codexMcpServerManager = new CodexMcpServerManager(codexSettingsManager);

        // Initialize CodexProviderManager
        this.codexProviderManager = new CodexProviderManager(
                gson,
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                pathManager,
                codexSettingsManager
        );
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
     */
    public JsonObject readConfig() throws IOException {
        String configPath = getConfigPath();
        File configFile = new File(configPath);

        if (!configFile.exists()) {
            LOG.info("[CodemossSettings] Config file not found, creating default: " + configPath);
            return createDefaultConfig();
        }

        try (FileReader reader = new FileReader(configFile, StandardCharsets.UTF_8)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            LOG.info("[CodemossSettings] Successfully read config from: " + configPath);
            return config;
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read config: " + e.getMessage());
            return createDefaultConfig();
        }
    }

    /**
     * Write the config file.
     */
    public void writeConfig(JsonObject config) throws IOException {
        pathManager.ensureConfigDirectory();

        // Back up existing config
        backupConfig();

        String configPath = getConfigPath();
        try (FileWriter writer = new FileWriter(configPath, StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
            LOG.info("[CodemossSettings] Successfully wrote config to: " + configPath);
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to write config: " + e.getMessage());
            throw e;
        }
        // Security (J): config.json holds provider API keys/tokens; restrict to 0600.
        hardenFilePermissions(Paths.get(configPath));
    }

    private void backupConfig() {
        try {
            Path configPath = pathManager.getConfigFilePath();
            if (Files.exists(configPath)) {
                Path backupPath = Paths.get(pathManager.getBackupPath());
                Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                // Security (J): the .bak copy also contains secrets; restrict to 0600.
                hardenFilePermissions(backupPath);
            }
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to backup config: " + e.getMessage());
        }
    }

    /**
     * Best-effort restrict a file to owner read/write (0600). No-op on non-POSIX
     * filesystems (e.g. Windows), where the per-user home directory ACL applies. (Security J)
     */
    private static void hardenFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            LOG.debug("[CodemossSettings] Could not set 0600 on " + path + ": " + e.getMessage());
        }
    }

    /**
     * Create default config.
     */
    private JsonObject createDefaultConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("version", CONFIG_VERSION);

        // Claude config - empty provider list
        JsonObject claude = new JsonObject();
        JsonObject providers = new JsonObject();

        claude.addProperty("current", "");
        claude.add("providers", providers);
        config.add(CommonConstants.PROVIDER_CLAUDE, claude);

        JsonObject codex = new JsonObject();
        codex.addProperty("current", "");
        codex.add("providers", new JsonObject());
        codex.addProperty("localConfigAuthorized", false);
        config.add(ProviderType.CODEX.value(), codex);

        return config;
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
        JsonObject config = readConfig();
        if (!config.has(APPEARANCE_CONFIG_KEY) || !config.get(APPEARANCE_CONFIG_KEY).isJsonObject()) {
            return createDefaultAppearanceConfig();
        }
        return normalizeAppearanceConfig(config.getAsJsonObject(APPEARANCE_CONFIG_KEY));
    }

    /**
     * Persist appearance config (called from the webview via {@code set_appearance_config}).
     *
     * @param rawConfig raw appearance config payload from the webview
     */
    public void setAppearanceConfig(JsonObject rawConfig) throws IOException {
        JsonObject config = readConfig();
        config.add(APPEARANCE_CONFIG_KEY, normalizeAppearanceConfig(rawConfig));
        writeConfig(config);
        LOG.debug("[CodemossSettings] Updated appearance config");
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

    private JsonObject createDefaultAppearanceConfig() {
        JsonObject appearance = new JsonObject();
        appearance.addProperty(APPEARANCE_THEME_PREFERENCE_KEY, "system");
        appearance.addProperty(APPEARANCE_FONT_SIZE_KEY, 2);
        appearance.addProperty(APPEARANCE_DIFF_THEME_KEY, "follow");
        // Colors are omitted by default (unset → webview falls back to theme defaults).
        return appearance;
    }

    /**
     * Normalize and validate an appearance config payload. Unknown/invalid fields
     * fall back to defaults; per-theme colors only persist valid hex values.
     */
    private JsonObject normalizeAppearanceConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultAppearanceConfig();
        }
        JsonObject appearance = new JsonObject();

        // Theme preference (system | light | dark)
        String themePref = rawConfig.has(APPEARANCE_THEME_PREFERENCE_KEY)
                && !rawConfig.get(APPEARANCE_THEME_PREFERENCE_KEY).isJsonNull()
                ? rawConfig.get(APPEARANCE_THEME_PREFERENCE_KEY).getAsString()
                : "system";
        appearance.addProperty(APPEARANCE_THEME_PREFERENCE_KEY,
                VALID_THEME_PREFERENCES.contains(themePref) ? themePref : "system");

        // Font size level (1-6, default 2)
        int fontSizeLevel = 2;
        if (rawConfig.has(APPEARANCE_FONT_SIZE_KEY) && rawConfig.get(APPEARANCE_FONT_SIZE_KEY).isJsonPrimitive()) {
            try {
                int v = rawConfig.get(APPEARANCE_FONT_SIZE_KEY).getAsInt();
                if (v >= 1 && v <= 6) {
                    fontSizeLevel = v;
                }
            } catch (Exception ignored) {
                // Non-numeric value → fall back to default
            }
        }
        appearance.addProperty(APPEARANCE_FONT_SIZE_KEY, fontSizeLevel);

        // Diff theme
        String diffTheme = rawConfig.has(APPEARANCE_DIFF_THEME_KEY)
                && !rawConfig.get(APPEARANCE_DIFF_THEME_KEY).isJsonNull()
                ? rawConfig.get(APPEARANCE_DIFF_THEME_KEY).getAsString()
                : "follow";
        appearance.addProperty(APPEARANCE_DIFF_THEME_KEY,
                VALID_DIFF_THEMES.contains(diffTheme) ? diffTheme : "follow");

        // Per-theme colors (only valid hex values are persisted)
        JsonObject chatBg = normalizeScopedColors(rawConfig, APPEARANCE_CHAT_BG_KEY);
        if (chatBg != null) {
            appearance.add(APPEARANCE_CHAT_BG_KEY, chatBg);
        }
        JsonObject userMsg = normalizeScopedColors(rawConfig, APPEARANCE_USER_MSG_KEY);
        if (userMsg != null) {
            appearance.add(APPEARANCE_USER_MSG_KEY, userMsg);
        }

        return appearance;
    }

    /** Normalize a per-theme color map ({light, dark}); null if no valid entries. */
    private JsonObject normalizeScopedColors(JsonObject rawConfig, String key) {
        if (!rawConfig.has(key) || !rawConfig.get(key).isJsonObject()) {
            return null;
        }
        JsonObject src = rawConfig.getAsJsonObject(key);
        JsonObject out = new JsonObject();
        addHexIfValid(out, src, "light");
        addHexIfValid(out, src, "dark");
        return out.size() > 0 ? out : null;
    }

    private void addHexIfValid(JsonObject out, JsonObject src, String theme) {
        if (!src.has(theme) || !src.get(theme).isJsonPrimitive()) {
            return;
        }
        String v = src.get(theme).getAsString();
        if (v != null && HEX_COLOR_PATTERN.matcher(v).matches()) {
            out.addProperty(theme, v);
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
                if (config.has("claude")) {
                    JsonObject claude = config.getAsJsonObject("claude");
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
        return providerManager.setAlwaysThinkingEnabledInActiveProvider(enabled);
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
        providerManager.applyActiveProviderToClaudeSettings();
    }

    // ==================== Working Directory Management ====================

    public String getCustomWorkingDirectory(String projectPath) throws IOException {
        return workingDirectoryManager.getCustomWorkingDirectory(projectPath);
    }

    public void setCustomWorkingDirectory(String projectPath, String customWorkingDir) throws IOException {
        workingDirectoryManager.setCustomWorkingDirectory(projectPath, customWorkingDir);
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
        JsonObject config = readConfig();
        if (!config.has(UI_FONT_CONFIG_KEY) || !config.get(UI_FONT_CONFIG_KEY).isJsonObject()) {
            return createDefaultUiFontConfig();
        }
        return normalizeUiFontConfig(config.getAsJsonObject(UI_FONT_CONFIG_KEY));
    }

    /**
     * Persist UI font configuration.
     *
     * @param mode requested mode
     * @param customFontPath custom font path for custom file mode
     */
    public void setUiFontConfig(String mode, String customFontPath) throws IOException {
        JsonObject config = readConfig();
        config.add(UI_FONT_CONFIG_KEY, createUiFontConfig(mode, customFontPath));
        writeConfig(config);
        LOG.debug("[CodemossSettings] Set UI font config: mode=" + mode
                + ", customFontPath=" + customFontPath);
    }

    /**
     * Get persisted code font configuration.
     *
     * @return normalized code font configuration
     */
    public JsonObject getCodeFontConfig() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(CODE_FONT_CONFIG_KEY) || !config.get(CODE_FONT_CONFIG_KEY).isJsonObject()) {
            return createDefaultCodeFontConfig();
        }
        return normalizeCodeFontConfig(config.getAsJsonObject(CODE_FONT_CONFIG_KEY));
    }

    /**
     * Persist code font configuration.
     *
     * @param mode requested mode
     * @param customFontPath custom font path for custom file mode
     */
    public void setCodeFontConfig(String mode, String customFontPath) throws IOException {
        JsonObject config = readConfig();
        config.add(CODE_FONT_CONFIG_KEY, createCodeFontConfig(mode, customFontPath));
        writeConfig(config);
        LOG.debug("[CodemossSettings] Set code font config: mode=" + mode
                + ", customFontPath=" + customFontPath);
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

    private JsonObject createDefaultUiFontConfig() {
        JsonObject uiFont = new JsonObject();
        uiFont.addProperty(UI_FONT_MODE_KEY, FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
        return uiFont;
    }

    private JsonObject createDefaultCodeFontConfig() {
        JsonObject codeFont = new JsonObject();
        codeFont.addProperty(UI_FONT_MODE_KEY, FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
        return codeFont;
    }

    private JsonObject normalizeUiFontConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultUiFontConfig();
        }
        String requestedMode = rawConfig.has(UI_FONT_MODE_KEY) && !rawConfig.get(UI_FONT_MODE_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_MODE_KEY).getAsString()
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        String customFontPath = rawConfig.has(UI_FONT_CUSTOM_PATH_KEY) && !rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).getAsString()
                : null;
        return createUiFontConfig(requestedMode, customFontPath);
    }

    private JsonObject createUiFontConfig(String mode, String customFontPath) {
        String normalizedMode = VALID_UI_FONT_MODES.contains(mode)
                ? mode
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        JsonObject uiFont = new JsonObject();
        uiFont.addProperty(UI_FONT_MODE_KEY, normalizedMode);

        if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(normalizedMode)
                && customFontPath != null
                && !customFontPath.trim().isEmpty()) {
            uiFont.addProperty(UI_FONT_CUSTOM_PATH_KEY, customFontPath.trim());
        }

        return uiFont;
    }

    private JsonObject normalizeCodeFontConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultCodeFontConfig();
        }
        String requestedMode = rawConfig.has(UI_FONT_MODE_KEY) && !rawConfig.get(UI_FONT_MODE_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_MODE_KEY).getAsString()
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        String customFontPath = rawConfig.has(UI_FONT_CUSTOM_PATH_KEY) && !rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).getAsString()
                : null;
        return createCodeFontConfig(requestedMode, customFontPath);
    }

    private JsonObject createCodeFontConfig(String mode, String customFontPath) {
        // UI font and code font share the same valid-mode set (see VALID_UI_FONT_MODES).
        String normalizedMode = VALID_UI_FONT_MODES.contains(mode)
                ? mode
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        JsonObject codeFont = new JsonObject();
        codeFont.addProperty(UI_FONT_MODE_KEY, normalizedMode);

        if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(normalizedMode)
                && customFontPath != null
                && !customFontPath.trim().isEmpty()) {
            codeFont.addProperty(UI_FONT_CUSTOM_PATH_KEY, customFontPath.trim());
        }

        return codeFont;
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

    // ==================== Codex Sandbox Mode Config Management ====================

    /**
     * Get Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @return sandbox mode (workspace-write or danger-full-access)
     */
    public String getCodexSandboxMode(String projectPath) throws IOException {
        JsonObject config = readConfig();
        String defaultMode = getDefaultCodexSandboxMode();

        if (!config.has("codexSandboxMode")) {
            return defaultMode;
        }

        JsonObject sandboxConfig = config.getAsJsonObject("codexSandboxMode");

        if (projectPath != null && sandboxConfig.has(projectPath)) {
            String mode = sandboxConfig.get(projectPath).getAsString();
            return isValidCodexSandboxMode(mode) ? mode : defaultMode;
        }

        if (sandboxConfig.has("default")) {
            String mode = sandboxConfig.get("default").getAsString();
            return isValidCodexSandboxMode(mode) ? mode : defaultMode;
        }

        return defaultMode;
    }

    /**
     * Set Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @param sandboxMode sandbox mode (workspace-write or danger-full-access)
     */
    public void setCodexSandboxMode(String projectPath, String sandboxMode) throws IOException {
        if (!isValidCodexSandboxMode(sandboxMode)) {
            throw new IllegalArgumentException("Invalid Codex sandbox mode: " + sandboxMode);
        }

        JsonObject config = readConfig();

        JsonObject sandboxConfig;
        if (config.has("codexSandboxMode")) {
            sandboxConfig = config.getAsJsonObject("codexSandboxMode");
        } else {
            sandboxConfig = new JsonObject();
            config.add("codexSandboxMode", sandboxConfig);
        }

        if (projectPath != null) {
            sandboxConfig.addProperty(projectPath, sandboxMode);
        }
        sandboxConfig.addProperty("default", sandboxMode);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set Codex sandbox mode to " + sandboxMode + " for project: " + projectPath);
    }

    private boolean isValidCodexSandboxMode(String mode) {
        return CODEX_SANDBOX_MODE_WORKSPACE_WRITE.equals(mode)
                || CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS.equals(mode);
    }

    private String getDefaultCodexSandboxMode() {
        // Security (F): default to workspace-write (sandboxed to the project) instead of
        // danger-full-access (no sandbox), so a prompt-injected Codex command is contained
        // to the project by default; full access must be an explicit opt-in. Windows keeps
        // danger-full-access as a platform fallback because the Codex sandbox is experimental
        // there (mirrors CodexSDKBridge.resolveCodexSandboxMode).
        return com.github.claudecodegui.util.PlatformUtils.isWindows()
                ? CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS
                : CODEX_SANDBOX_MODE_WORKSPACE_WRITE;
    }

    // ==================== Provider Management ====================

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

    public int saveProviders(List<JsonObject> providers) throws IOException {
        return providerManager.saveProviders(providers);
    }

    public void saveProviderOrder(List<String> orderedIds) throws IOException {
        providerManager.saveProviderOrder(orderedIds);
    }

    public boolean isLocalProviderActive() {
        return providerManager.isLocalProviderActive();
    }

    // ==================== MCP Server Management ====================

    public List<JsonObject> getMcpServers() throws IOException {
        return mcpServerManager.getMcpServers();
    }

    public List<JsonObject> getMcpServersWithProjectPath(String projectPath) throws IOException {
        return mcpServerManager.getMcpServersWithProjectPath(projectPath);
    }

    public void upsertMcpServer(JsonObject server) throws IOException {
        mcpServerManager.upsertMcpServer(server);
    }

    public void upsertMcpServer(JsonObject server, String projectPath) throws IOException {
        mcpServerManager.upsertMcpServer(server, projectPath);
    }

    public boolean deleteMcpServer(String serverId) throws IOException {
        return mcpServerManager.deleteMcpServer(serverId);
    }

    public Map<String, Object> validateMcpServer(JsonObject server) {
        return mcpServerManager.validateMcpServer(server);
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

    // ==================== AI Feature Toggle Management ====================

    /**
     * Get whether AI commit message generation is enabled.
     *
     * @return whether commit generation is enabled, default is true
     */
    public boolean getCommitGenerationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("commitGenerationEnabled") && !config.get("commitGenerationEnabled").isJsonNull()) {
            return config.get("commitGenerationEnabled").getAsBoolean();
        }

        return true;
    }

    /**
     * Set whether AI commit message generation is enabled.
     *
     * @param enabled whether to enable
     */
    public void setCommitGenerationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("commitGenerationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set commit generation enabled: " + enabled);
    }

    /**
     * Get whether status bar widget is enabled.
     *
     * @return whether status bar widget is enabled, default is true
     */
    public boolean getStatusBarWidgetEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("statusBarWidgetEnabled") && !config.get("statusBarWidgetEnabled").isJsonNull()) {
            return config.get("statusBarWidgetEnabled").getAsBoolean();
        }

        return true;
    }

    /**
     * Set whether status bar widget is enabled.
     *
     * @param enabled whether to enable
     */
    public void setStatusBarWidgetEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("statusBarWidgetEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set status bar widget enabled: " + enabled);
    }

    /**
     * Get whether AI session title generation is enabled.
     *
     * @return whether AI title generation is enabled, default is true
     */
    public boolean getAiTitleGenerationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("aiTitleGenerationEnabled") && !config.get("aiTitleGenerationEnabled").isJsonNull()) {
            return config.get("aiTitleGenerationEnabled").getAsBoolean();
        }

        return true;
    }

    /**
     * Set whether AI session title generation is enabled.
     *
     * @param enabled whether to enable
     */
    public void setAiTitleGenerationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("aiTitleGenerationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set AI title generation enabled: " + enabled);
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
            DependencyManager dependencyManager = new DependencyManager();
            if (CommonConstants.PROVIDER_CODEX.equals(provider)) {
                return getActiveCodexProvider() != null && dependencyManager.isInstalled("codex-sdk");
            }
            if (CommonConstants.PROVIDER_OPENCODE.equals(provider)) {
                return dependencyManager.isInstalled("opencode-sdk");
            }
            return getActiveClaudeProvider() != null && dependencyManager.isInstalled("claude-sdk");
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
            LOG.warn("[CodemossSettings] Failed to check Codex local authorization: " + e.getMessage());
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
            LOG.warn("[CodemossSettings] Failed to read Codex local authorization state: " + e.getMessage());
            return null;
        }
    }

    public boolean isCodexLocalConfigAuthorized() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(ProviderType.CODEX.value()) || !config.get(ProviderType.CODEX.value()).isJsonObject()) {
            return false;
        }
        JsonObject codex = config.getAsJsonObject(ProviderType.CODEX.value());
        return codex.has("localConfigAuthorized")
                && !codex.get("localConfigAuthorized").isJsonNull()
                && codex.get("localConfigAuthorized").getAsBoolean();
    }

    public void setCodexLocalConfigAuthorized(boolean authorized) throws IOException {
        JsonObject config = readConfig();
        JsonObject codex;
        if (config.has(ProviderType.CODEX.value()) && config.get(ProviderType.CODEX.value()).isJsonObject()) {
            codex = config.getAsJsonObject(ProviderType.CODEX.value());
        } else {
            codex = new JsonObject();
            codex.add("providers", new JsonObject());
            codex.addProperty("current", "");
            config.add(ProviderType.CODEX.value(), codex);
        }

        codex.addProperty("localConfigAuthorized", authorized);
        writeConfig(config);
    }

    public String getCodexRuntimeAccessMode() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(ProviderType.CODEX.value()) || !config.get(ProviderType.CODEX.value()).isJsonObject()) {
            return CODEX_RUNTIME_ACCESS_INACTIVE;
        }

        JsonObject codex = config.getAsJsonObject(ProviderType.CODEX.value());
        String currentId = codex.has("current") && !codex.get("current").isJsonNull()
                ? codex.get("current").getAsString().trim()
                : "";

        if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(currentId)) {
            return isCodexLocalConfigAuthorized()
                    ? CODEX_RUNTIME_ACCESS_CLI_LOGIN
                    : CODEX_RUNTIME_ACCESS_INACTIVE;
        }

        if (!currentId.isEmpty()
                && codex.has("providers")
                && codex.get("providers").isJsonObject()
                && codex.getAsJsonObject("providers").has(currentId)) {
            return CODEX_RUNTIME_ACCESS_MANAGED;
        }

        return CODEX_RUNTIME_ACCESS_INACTIVE;
    }

    public String getClaudeInvocationMode() throws IOException {
        JsonObject config = readConfig();
        if (config.has(CLAUDE_INVOCATION_MODE_KEY) && !config.get(CLAUDE_INVOCATION_MODE_KEY).isJsonNull()) {
            String mode = config.get(CLAUDE_INVOCATION_MODE_KEY).getAsString();
            if (CommonConstants.INVOCATION_MODE_CLI.equals(mode)) {
                return CommonConstants.INVOCATION_MODE_CLI;
            }
        }
        return CommonConstants.INVOCATION_MODE_SDK;
    }

    public void setClaudeInvocationMode(String mode) throws IOException {
        JsonObject config = readConfig();
        config.addProperty(CLAUDE_INVOCATION_MODE_KEY, CommonConstants.INVOCATION_MODE_CLI.equals(mode) ? CommonConstants.INVOCATION_MODE_CLI : CommonConstants.INVOCATION_MODE_SDK);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set Claude invocation mode: " + getClaudeInvocationMode());
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
        codexProviderManager.saveProviderOrder(orderedIds);
    }

    // ==================== Model Registry Config Management ====================

    /**
     * Read the effective model registry = merge(persisted user layer, read-only defaults).
     * Read-only defaults (Claude 4 roles from settings.json + Codex from config.toml) are
     * computed at runtime and never persisted.
     */
    public ModelRegistryConfig getModelRegistry() {
        try {
            return ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(readPersistedUserLayer());
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read model registry, using read-only defaults: " + e.getMessage());
            return ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(new ModelRegistryConfig(java.util.List.of()));
        }
    }

    /**
     * Save the user-layer model registry. Read-only items are stripped (never persisted).
     * New entries conflicting with read-only default keys are rejected; validation runs on
     * the effective registry (user layer + read-only defaults) so the read-only roles
     * guarantee "at least one enabled" — an empty user layer is therefore valid.
     */
    public ModelConfigValidator.ValidationResult setModelRegistry(ModelRegistryConfig registry) {
        ModelRegistryConfig userOnly = stripReadOnly(registry);
        ModelConfigValidator.ValidationResult conflict = checkNoNewConflictsWithReadOnly(userOnly);
        if (!conflict.isValid()) {
            LOG.warn("[CodemossSettings] Model registry conflicts with read-only defaults, not saving: "
                    + conflict.errors());
            return conflict;
        }
        ModelConfigValidator.ValidationResult validation =
                ModelConfigValidator.validate(ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(userOnly));
        if (!validation.isValid()) {
            LOG.warn("[CodemossSettings] Model registry validation failed, not saving: " + validation.errors());
            return validation;
        }
        try {
            JsonObject config = readConfig();
            config.add(MODEL_REGISTRY_KEY, serializeModelRegistry(userOnly));
            writeConfig(config);
            LOG.info("[CodemossSettings] Saved model registry");
            return validation;
        } catch (Exception e) {
            LOG.error("[CodemossSettings] Failed to save model registry: " + e.getMessage());
            var errors = new java.util.ArrayList<String>();
            errors.add("保存失败: " + e.getMessage());
            return new ModelConfigValidator.ValidationResult(errors, java.util.List.of());
        }
    }

    /**
     * Remove persisted model registry so defaults are used again.
     */
    public void resetModelRegistry() {
        try {
            JsonObject config = readConfig();
            config.remove(MODEL_REGISTRY_KEY);
            writeConfig(config);
            LOG.info("[CodemossSettings] Reset model registry to defaults");
        } catch (Exception e) {
            LOG.error("[CodemossSettings] Failed to reset model registry: " + e.getMessage());
        }
    }

    /**
     * Read the raw persisted user layer without read-only defaults and without the
     * getDefault() fallback. Missing/invalid config returns an empty user layer.
     */
    private ModelRegistryConfig readPersistedUserLayer() {
        try {
            JsonObject config = readConfig();
            if (!config.has(MODEL_REGISTRY_KEY) || !config.get(MODEL_REGISTRY_KEY).isJsonObject()) {
                return new ModelRegistryConfig(java.util.List.of());
            }
            ModelRegistryConfig parsed = parseModelRegistry(config.getAsJsonObject(MODEL_REGISTRY_KEY));
            return stripReadOnly(parsed); // 防御:磁盘上不应残留只读项
        } catch (Exception e) {
            return new ModelRegistryConfig(java.util.List.of());
        }
    }

    /** 剥离 readOnly=true 项(后端权威:只读默认永不进持久化)。 */
    private static ModelRegistryConfig stripReadOnly(ModelRegistryConfig registry) {
        java.util.List<ModelConfig> userOnly = new java.util.ArrayList<>();
        for (ModelConfig model : registry.models()) {
            if (!model.readOnly()) {
                userOnly.add(model);
            }
        }
        return new ModelRegistryConfig(userOnly);
    }

    /**
     * 仅拦截"新增"冲突:用户层中、与只读默认键相同、且当前磁盘用户层不存在的项。
     * legacy 同键项放行(合并时 role 被跳过 / codex 被用户覆盖),避免阻塞无关保存。
     */
    private ModelConfigValidator.ValidationResult checkNoNewConflictsWithReadOnly(ModelRegistryConfig incoming) {
        java.util.Set<String> currentKeys = new java.util.HashSet<>();
        for (ModelConfig model : readPersistedUserLayer().models()) {
            currentKeys.add(ReadOnlyDefaultModels.dedupKey(model.provider(), model.id()));
        }
        java.util.Set<String> readOnlyKeys = new java.util.HashSet<>();
        for (ModelConfig model : ReadOnlyDefaultModels.compute()) {
            readOnlyKeys.add(ReadOnlyDefaultModels.dedupKey(model.provider(), model.id()));
        }
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (ModelConfig model : incoming.models()) {
            String key = ReadOnlyDefaultModels.dedupKey(model.provider(), model.id());
            if (readOnlyKeys.contains(key) && !currentKeys.contains(key)) {
                errors.add("模型 " + model.id() + " 与配置文件默认模型冲突,无法新增");
            }
        }
        return errors.isEmpty()
                ? new ModelConfigValidator.ValidationResult(java.util.List.of(), java.util.List.of())
                : new ModelConfigValidator.ValidationResult(errors, java.util.List.of());
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
        return ModelRegistryService.serialize(getModelRegistry()).toString();
    }

    private ModelRegistryConfig parseModelRegistry(JsonObject modelRegistryObj) {
        List<ModelConfig> models = new java.util.ArrayList<>();
        if (modelRegistryObj.has("items") && modelRegistryObj.get("items").isJsonArray()) {
            JsonArray items = modelRegistryObj.getAsJsonArray("items");
            for (JsonElement item : items) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject obj = item.getAsJsonObject();
                String id = readString(obj, "id");
                String provider = readString(obj, "provider");
                String role = readString(obj, "role");
                String label = readString(obj, "label");
                String actualModel = readString(obj, "actualModel");
                String description = readString(obj, "description");
                int contextWindow = obj.has("contextWindow") && obj.get("contextWindow").isJsonPrimitive()
                        ? obj.get("contextWindow").getAsInt()
                        : CommonConstants.DEFAULT_CONTEXT_WINDOW;
                boolean supports1MContext = obj.has("supports1MContext")
                        && obj.get("supports1MContext").getAsBoolean();
                boolean enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
                models.add(new ModelConfig(id, provider, role, label, actualModel,
                        description, contextWindow, supports1MContext, enabled));
            }
        }
        return new ModelRegistryConfig(models);
    }

    private JsonObject serializeModelRegistry(ModelRegistryConfig registry) {
        JsonObject root = new JsonObject();
        JsonArray items = new JsonArray();
        for (ModelConfig model : registry.models()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", model.id());
            obj.addProperty("provider", model.provider());
            obj.addProperty("role", model.role());
            obj.addProperty("label", model.label());
            if (model.actualModel() == null || model.actualModel().isEmpty()) {
                obj.add("actualModel", JsonNull.INSTANCE);
            } else {
                obj.addProperty("actualModel", model.actualModel());
            }
            if (model.description() == null || model.description().isEmpty()) {
                obj.add("description", JsonNull.INSTANCE);
            } else {
                obj.addProperty("description", model.description());
            }
            obj.addProperty("contextWindow", model.contextWindow());
            obj.addProperty("supports1MContext", model.supports1MContext());
            obj.addProperty("enabled", model.enabled());
            obj.addProperty("readOnly", model.readOnly());
            items.add(obj);
        }
        root.add("items", items);
        return root;
    }

    private static String readString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        return obj.get(key).getAsString();
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
                return com.github.claudecodegui.config.RuntimePolicyConfig.getDefault();
            }
            JsonObject runtimeObj = config.getAsJsonObject(RUNTIME_POLICY_KEY);
            return parseRuntimePolicy(runtimeObj);
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read runtime policy, using default: " + e.getMessage());
            return com.github.claudecodegui.config.RuntimePolicyConfig.getDefault();
        }
    }

    /**
     * 保存路由策略配置。先校验，errors 非空则拒绝落盘。
     *
     * @param policyConfig 待保存的配置
     * @return 校验结果（errors 非空表示被拒绝）
     */
    public com.github.claudecodegui.config.RuntimePolicyValidator.ValidationResult setRuntimePolicy(
            com.github.claudecodegui.config.RuntimePolicyConfig policyConfig) {
        var validationResult = com.github.claudecodegui.config.RuntimePolicyValidator.validate(policyConfig);
        if (!validationResult.isValid()) {
            LOG.warn("[CodemossSettings] Runtime policy validation failed, not saving: " + validationResult.errors());
            return validationResult;
        }
        try {
            JsonObject config = readConfig();
            JsonObject runtimeObj = serializeRuntimePolicy(policyConfig);
            config.add(RUNTIME_POLICY_KEY, runtimeObj);
            writeConfig(config);
            LOG.info("[CodemossSettings] Saved runtime policy config");
        } catch (Exception e) {
            LOG.error("[CodemossSettings] Failed to save runtime policy: " + e.getMessage());
            var errors = new java.util.ArrayList<String>();
            errors.add("保存失败: " + e.getMessage());
            return new com.github.claudecodegui.config.RuntimePolicyValidator.ValidationResult(errors, java.util.List.of());
        }
        return validationResult;
    }

    /**
     * 重置路由策略为默认配置。
     */
    public void resetRuntimePolicy() {
        try {
            JsonObject config = readConfig();
            config.remove(RUNTIME_POLICY_KEY);
            writeConfig(config);
            LOG.info("[CodemossSettings] Reset runtime policy to default");
        } catch (Exception e) {
            LOG.error("[CodemossSettings] Failed to reset runtime policy: " + e.getMessage());
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
                ProviderType pt;
                if (CommonConstants.PROVIDER_CLAUDE.equalsIgnoreCase(key)) {
                    pt = ProviderType.CLAUDE;
                } else if (CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(key)) {
                    pt = ProviderType.CODEX;
                } else {
                    LOG.warn("[CodemossSettings] Unrecognized runtime policy provider key '"
                            + key + "', skipping (valid keys: claude, codex)");
                    continue;
                }
                if (providersObj.get(key).isJsonObject()) {
                    JsonObject policyObj = providersObj.getAsJsonObject(key);
                    boolean enabled = policyObj.has("enabled") && policyObj.get("enabled").getAsBoolean();
                    var supported = new java.util.HashSet<RuntimeType>();
                    if (policyObj.has("supported") && policyObj.get("supported").isJsonArray()) {
                        for (var el : policyObj.getAsJsonArray("supported")) {
                            String rtStr = el.getAsString();
                            if ("SDK".equalsIgnoreCase(rtStr)) {
                                supported.add(RuntimeType.SDK);
                            } else if ("CLI".equalsIgnoreCase(rtStr)) {
                                supported.add(RuntimeType.CLI);
                            }
                        }
                    }
                    RuntimeType defaultRt = null;
                    if (policyObj.has("default") && !policyObj.get("default").isJsonNull()) {
                        String defStr = policyObj.get("default").getAsString();
                        if ("SDK".equalsIgnoreCase(defStr)) {
                            defaultRt = RuntimeType.SDK;
                        } else if ("CLI".equalsIgnoreCase(defStr)) {
                            defaultRt = RuntimeType.CLI;
                        }
                    }
                    try {
                        providers.put(pt, new com.github.claudecodegui.config.ProviderRuntimePolicy(
                                enabled, supported, defaultRt));
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

    private JsonObject serializeRuntimePolicy(com.github.claudecodegui.config.RuntimePolicyConfig policyConfig) {
        JsonObject runtimeObj = new JsonObject();
        JsonObject providersObj = new JsonObject();

        for (var entry : policyConfig.providers().entrySet()) {
            String key = entry.getKey().toLowerCase();
            com.github.claudecodegui.config.ProviderRuntimePolicy policy = entry.getValue();
            JsonObject policyObj = new JsonObject();
            policyObj.addProperty("enabled", policy.enabled());

            var supportedArray = new com.google.gson.JsonArray();
            if (policy.supported() != null) {
                for (var rt : policy.supported()) {
                    supportedArray.add(rt.name());
                }
            }
            policyObj.add("supported", supportedArray);

            if (policy.defaultRuntime() != null) {
                policyObj.addProperty("default", policy.defaultRuntime().name());
            }

            providersObj.add(key, policyObj);
        }

        runtimeObj.add("providers", providersObj);
        return runtimeObj;
    }
}
