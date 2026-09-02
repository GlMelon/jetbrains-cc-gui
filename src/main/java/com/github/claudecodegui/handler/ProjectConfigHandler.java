package com.github.claudecodegui.handler;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.cli.common.CliPersistentProcessRegistry;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.action.SendShortcutSync;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.util.FontConfigService;
import com.github.claudecodegui.util.ThemeConfigService;
import com.github.claudecodegui.util.GsonHolder;
import com.github.claudecodegui.util.CredentialMasker;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.concurrent.CompletableFuture;

/**
 * Handles project-level configuration: working directory, streaming, sandbox mode,
 * auto-open file, send shortcut, commit prompt, IDE theme, editor font config, and usage statistics.
 */
public class ProjectConfigHandler {

    private static final Logger LOG = Logger.getInstance(ProjectConfigHandler.class);
    static final String SEND_SHORTCUT_PROPERTY_KEY = "claude.code.send.shortcut";

    private final HandlerContext context;
    private final CodemossSettingsService settingsService;
    private final Gson gson = GsonHolder.GSON;

    public ProjectConfigHandler(HandlerContext context) {
        this.context = context;
        this.settingsService = context.getSettingsService();
    }

    // ---- Internal helpers --------------------------------------------------

    @FunctionalInterface
    private interface ThrowingJsonSupplier { JsonElement get() throws Exception; }

    @FunctionalInterface
    private interface ThrowingBooleanConsumer { void accept(boolean value) throws Exception; }

    @FunctionalInterface
    private interface ThrowingProjectBooleanConsumer { void accept(String projectPath, boolean value) throws Exception; }

    /**
     * [归一化重构] pushJson 改用 dispatchEvent。jsCallback 参数现为下行总线 type(B5 后统一来自 {@link DownstreamEvent} 枚举)。
     */
    private void pushJson(String type, JsonElement payload) {
        String json = gson.toJson(payload);
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(type, json));
    }

    private void showError(String message) {
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), message));
    }

    private void showSuccess(String message) {
        ApplicationManager.getApplication().invokeLater(() ->
            context.dispatchEvent(DownstreamEvent.TOAST_SUCCESS.value(), message));
    }

    private static JsonObject jsonOf(String key, boolean value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return obj;
    }

    private static JsonObject jsonOf(String key, String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return obj;
    }

    private static JsonObject jsonOf(String key, int value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return obj;
    }

    /**
     * Respond With Json
     *
     * @param jsCallback js callback
     * @param producer producer
     * @param fallback fallback
     * @param errorLogMessage error log message
     */
    private void respondWithJson(String jsCallback, ThrowingJsonSupplier producer, JsonElement fallback,
                                 String errorLogMessage) {
        try {
            pushJson(jsCallback, producer.get());
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] " + errorLogMessage + "; errorClass="
                    + e.getClass().getSimpleName(), e);
            if (fallback != null) {
                pushJson(jsCallback, fallback);
            }
        }
    }

    private boolean readBoolean(JsonObject json, String field, boolean defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) { return defaultValue; }
        return json.get(field).getAsBoolean();
    }

    private String readString(JsonObject json, String field, String defaultValue) {
        if (json == null || !json.has(field) || json.get(field).isJsonNull()) { return defaultValue; }
        return json.get(field).getAsString();
    }

    /** Standard boolean-toggle setter: parse one field, apply mutation, log, echo back via {@code jsCallback}. */
    private void handleBooleanToggle(String content, String field, boolean defaultValue,
                                     String logLabel, ThrowingBooleanConsumer mutation,
                                     String jsCallback, String errorMessage) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = readBoolean(json, field, defaultValue);
            mutation.accept(enabled);
            LOG.info("[ProjectConfigHandler] Set " + logLabel + ": " + enabled);
            pushJson(jsCallback, jsonOf(field, enabled));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set " + logLabel + ": " + e.getMessage(), e);
            showError(errorMessage);
        }
    }

    /** Project-scoped variant of {@link #handleBooleanToggle}; validates project path first. */
    private void handleProjectBooleanToggle(String content, String field, boolean defaultValue,
                                            String logLabel, ThrowingProjectBooleanConsumer mutation,
                                            String jsCallback, String errorMessage) {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                showError("Unable to resolve project path");
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = readBoolean(json, field, defaultValue);
            mutation.accept(projectPath, enabled);
            LOG.info("[ProjectConfigHandler] Set " + logLabel + ": " + enabled);
            pushJson(jsCallback, jsonOf(field, enabled));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set " + logLabel + ": " + e.getMessage(), e);
            showError(errorMessage + ": " + e.getMessage());
        }
    }

    // ---- Working Directory -------------------------------------------------

    public void handleGetWorkingDirectory() {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.CONFIG_WORKING_DIRECTORY.value(), "{}"));
                return;
            }
            String customWorkingDir = settingsService.getCustomWorkingDirectory(projectPath);
            JsonObject response = new JsonObject();
            response.addProperty("projectPath", projectPath);
            response.addProperty("customWorkingDir", customWorkingDir != null ? customWorkingDir : "");
            pushJson(DownstreamEvent.CONFIG_WORKING_DIRECTORY.value(), response);
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get working directory: " + e.getMessage(), e);
            showError("Failed to get working directory config: " + e.getMessage());
        }
    }

    public void handleSetWorkingDirectory(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                showError("Unable to resolve project path");
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String customWorkingDir = readString(json, "customWorkingDir", null);
            if (customWorkingDir != null && !customWorkingDir.trim().isEmpty()) {
                java.io.File workingDirFile = new java.io.File(customWorkingDir);
                if (!workingDirFile.isAbsolute()) {
                    workingDirFile = new java.io.File(projectPath, customWorkingDir);
                }
                if (!workingDirFile.exists() || !workingDirFile.isDirectory()) {
                    showError("Working directory does not exist: " + workingDirFile.getAbsolutePath());
                    return;
                }
            }
            settingsService.setCustomWorkingDirectory(projectPath, customWorkingDir);
            LOG.info("[ProjectConfigHandler] Set custom working directory: " + customWorkingDir);
            showSuccess("Working directory config saved");
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set working directory: " + e.getMessage(), e);
            showError("Failed to save working directory config: " + e.getMessage());
        }
    }

    public void handleGetStreamingEnabled() {
        respondWithJson(DownstreamEvent.SETTING_STREAMING_ENABLED.value(),
            () -> {
                String projectPath = context.getProject().getBasePath();
                boolean enabled = projectPath == null || settingsService.getStreamingEnabled(projectPath);
                return jsonOf("streamingEnabled", enabled);
            },
            jsonOf("streamingEnabled", true),
            "Failed to get streaming enabled");
    }

    public void handleSetStreamingEnabled(String content) {
        handleProjectBooleanToggle(content, "streamingEnabled", true, "streaming enabled",
            settingsService::setStreamingEnabled,
            DownstreamEvent.SETTING_STREAMING_ENABLED.value(),
            "Failed to save streaming config");
    }

    public void handleGetShowThinkingEnabled() {
        respondWithJson(DownstreamEvent.SETTING_SHOW_THINKING_ENABLED.value(),
            () -> {
                String projectPath = context.getProject().getBasePath();
                boolean enabled = projectPath == null || settingsService.getShowThinkingEnabled(projectPath);
                return jsonOf("showThinkingEnabled", enabled);
            },
            jsonOf("showThinkingEnabled", true),
            "Failed to get show thinking enabled");
    }

    public void handleSetShowThinkingEnabled(String content) {
        handleProjectBooleanToggle(content, "showThinkingEnabled", true, "show thinking enabled",
            settingsService::setShowThinkingEnabled,
            DownstreamEvent.SETTING_SHOW_THINKING_ENABLED.value(),
            "Failed to save show thinking config");
    }

    public void handleGetCodexSandboxMode() {
        respondWithJson(DownstreamEvent.CONFIG_CODEX_SANDBOX_MODE.value(),
            () -> jsonOf("sandboxMode", settingsService.getCodexSandboxMode(context.getProject().getBasePath())),
            jsonOf("sandboxMode", "workspace-write"),
            "Failed to get Codex sandbox mode");
    }

    public void handleSetCodexSandboxMode(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String sandboxMode = readString(json, "sandboxMode", "workspace-write");
            settingsService.setCodexSandboxMode(projectPath, sandboxMode);
            LOG.info("[ProjectConfigHandler] Set Codex sandbox mode: " + sandboxMode);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.CONFIG_CODEX_SANDBOX_MODE.value(),
                    gson.toJson(jsonOf("sandboxMode", sandboxMode)));
                context.dispatchEvent(DownstreamEvent.TOAST_SUCCESS_I18N.value(), "toast.saveSuccess");
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set Codex sandbox mode: " + e.getMessage(), e);
            showError("Failed to save Codex sandbox mode: " + e.getMessage());
        }
    }

    public void handleGetSessionRuntimeState() {
        respondWithJson(DownstreamEvent.SESSION_RUNTIME_STATE.value(), this::buildSessionRuntimeStateJson, null, "Failed to get session runtime state");
    }

    public JsonObject buildSessionRuntimeStateJson() throws Exception {
        ClaudeSession session = context.getSession();
        String provider = session != null ? session.getProvider() : context.getCurrentProvider();
        String model = session != null ? session.getModel() : context.getCurrentModel();
        String permissionMode = session != null ? session.getPermissionMode() : readDefaultPermissionMode(provider);

        JsonObject response = new JsonObject();
        response.addProperty("provider", provider);
        response.addProperty("model", model);
        response.addProperty("permissionMode", permissionMode);
        return response;
    }

    private String readDefaultPermissionMode(String provider) {
        String mode = PropertiesComponent.getInstance().getValue(PermissionModeHandler.PERMISSION_MODE_PROPERTY_KEY);
        if (mode == null || mode.trim().isEmpty()) {
            mode = CommonConstants.PERMISSION_MODE_DEFAULT;
        } else {
            mode = mode.trim();
        }
        return mode;
    }

    public void handleGetAutoOpenFileEnabled() {
        respondWithJson(DownstreamEvent.SETTING_AUTO_OPEN_FILE.value(),
            () -> {
                String projectPath = context.getProject().getBasePath();
                boolean enabled = projectPath != null && settingsService.getAutoOpenFileEnabled(projectPath);
                return jsonOf("autoOpenFileEnabled", enabled);
            },
            jsonOf("autoOpenFileEnabled", false),
            "Failed to get auto open file enabled");
    }

    public void handleSetAutoOpenFileEnabled(String content) {
        handleProjectBooleanToggle(content, "autoOpenFileEnabled", false, "auto open file enabled",
            settingsService::setAutoOpenFileEnabled,
            DownstreamEvent.SETTING_AUTO_OPEN_FILE.value(),
            "Failed to save auto open file config");
    }

    public void handleGetPermissionDialogTimeout() {
        respondWithJson(DownstreamEvent.SETTING_PERMISSION_DIALOG_TIMEOUT.value(),
            () -> jsonOf("permissionDialogTimeoutSeconds", settingsService.getPermissionDialogTimeoutSeconds()),
            jsonOf("permissionDialogTimeoutSeconds", CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS),
            "Failed to get permission dialog timeout");
    }

    public void handleSetPermissionDialogTimeout(String content) {
        try {
            JsonObject response = setPermissionDialogTimeoutAndCreateResponse(content);
            LOG.info("[ProjectConfigHandler] Set permission dialog timeout: "
                    + response.get("permissionDialogTimeoutSeconds").getAsInt() + "s");
            pushJson(DownstreamEvent.SETTING_PERMISSION_DIALOG_TIMEOUT.value(), response);
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set permission dialog timeout; errorClass="
                    + e.getClass().getSimpleName(), e);
            showError("Failed to save permission dialog timeout. See IDE log for details.");
        }
    }

    JsonObject setPermissionDialogTimeoutAndCreateResponse(String content) throws Exception {
        JsonObject json = gson.fromJson(content, JsonObject.class);
        // Strict type check: only accept a JSON numeric primitive. Anything else
        // (string, boolean, array, object, null, missing) falls back to default.
        int seconds = CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS;
        if (json != null && json.has("permissionDialogTimeoutSeconds")) {
            JsonElement element = json.get("permissionDialogTimeoutSeconds");
            if (element != null
                    && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isNumber()) {
                seconds = element.getAsInt();
            }
        }
        settingsService.setPermissionDialogTimeoutSeconds(seconds);
        int effectiveSeconds = settingsService.getPermissionDialogTimeoutSeconds();
        return jsonOf("permissionDialogTimeoutSeconds", effectiveSeconds);
    }

    /**
     * Handle Get Send Shortcut
     *
     */
    public void handleGetSendShortcut() {
        try {
            String sendShortcut = PropertiesComponent.getInstance().getValue(SEND_SHORTCUT_PROPERTY_KEY, "enter");
            pushJson(DownstreamEvent.SETTING_SEND_SHORTCUT.value(), jsonOf("sendShortcut", sendShortcut));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get send shortcut: " + e.getMessage(), e);
        }
    }

    public void handleSetSendShortcut(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String sendShortcut = readString(json, "sendShortcut", "enter");
            if (!"enter".equals(sendShortcut) && !"cmdEnter".equals(sendShortcut)) {
                sendShortcut = "enter";
            }
            PropertiesComponent.getInstance().setValue(SEND_SHORTCUT_PROPERTY_KEY, sendShortcut);
            SendShortcutSync.sync(sendShortcut);
            LOG.info("[ProjectConfigHandler] Set send shortcut: " + sendShortcut);
            pushJson(DownstreamEvent.SETTING_SEND_SHORTCUT.value(), jsonOf("sendShortcut", sendShortcut));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set send shortcut: " + e.getMessage(), e);
            showError("Failed to save send shortcut setting: " + e.getMessage());
        }
    }

    public void handleGetCommitPrompt() {
        try {
            String commitPrompt = settingsService.getCommitPrompt();
            String projectPath = context.getProject().getBasePath();
            String projectCommitPrompt = projectPath != null
                    ? settingsService.getProjectCommitPrompt(projectPath)
                    : "";
            JsonObject payload = new JsonObject();
            payload.addProperty("commitPrompt", commitPrompt);
            payload.addProperty("projectCommitPrompt", projectCommitPrompt);
            pushJson(DownstreamEvent.CONFIG_COMMIT_PROMPT.value(), payload);
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get commit prompt: " + e.getMessage(), e);
        }
    }

    public void handleSetCommitPrompt(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json == null || !json.has("prompt")) {
                LOG.warn("[ProjectConfigHandler] Invalid commit prompt request: missing prompt field");
                return;
            }
            String prompt = json.get("prompt").getAsString();
            if (prompt == null) {
                showError("Prompt cannot be empty");
                return;
            }
            prompt = prompt.trim();
            final int MAX_PROMPT_LENGTH = 10000;
            if (prompt.length() > MAX_PROMPT_LENGTH) {
                LOG.warn("[ProjectConfigHandler] Commit prompt too long: " + prompt.length() + " characters");
                showError("Prompt length must not exceed " + MAX_PROMPT_LENGTH + " characters");
                return;
            }
            final String validatedPrompt = prompt;
            settingsService.setCommitPrompt(validatedPrompt);
            LOG.info("[ProjectConfigHandler] Set commit prompt, length: " + validatedPrompt.length());
            JsonObject response = new JsonObject();
            response.addProperty("commitPrompt", validatedPrompt);
            response.addProperty("saved", true);
            pushJson(DownstreamEvent.CONFIG_COMMIT_PROMPT.value(), response);
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set commit prompt: " + e.getMessage(), e);
            showError("Failed to save commit prompt: " + e.getMessage());
        }
    }

    public void handleGetPromptEnhancerConfig() {
        try {
            pushJson(DownstreamEvent.CONFIG_PROMPT_ENHANCER.value(), settingsService.getPromptEnhancerConfig());
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get prompt enhancer config: " + e.getMessage(), e);
            showError(ClaudeCodeGuiBundle.message("projectConfig.promptEnhancer.getFailed", e.getMessage()));
        }
    }

    public void handleSetPromptEnhancerConfig(String content) {
        applyAiProviderConfig(content,
            settingsService::setPromptEnhancerConfig,
            settingsService::getPromptEnhancerConfig,
            DownstreamEvent.CONFIG_PROMPT_ENHANCER.value(),
            "Failed to set prompt enhancer config",
            "projectConfig.promptEnhancer.saveFailed");
    }

    public void handleGetCommitAiConfig() {
        try {
            pushJson(DownstreamEvent.CONFIG_COMMIT_AI.value(), settingsService.getCommitAiConfig());
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get commit AI config: " + e.getMessage(), e);
            showError(ClaudeCodeGuiBundle.message("projectConfig.commitAi.getFailed", e.getMessage()));
        }
    }

    public void handleSetCommitAiConfig(String content) {
        applyAiProviderConfig(content,
            settingsService::setCommitAiConfig,
            settingsService::getCommitAiConfig,
            DownstreamEvent.CONFIG_COMMIT_AI.value(),
            "Failed to set commit AI config",
            "projectConfig.commitAi.saveFailed");
    }

    @FunctionalInterface
    private interface AiProviderSetter {
        void apply(String provider, String claudeModel, String codexModel, String opencodeModel) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingJsonObjectSupplier { JsonObject get() throws Exception; }

    /** Shared logic for AI provider+models configs (prompt enhancer, commit AI, etc.). */
    private void applyAiProviderConfig(String content, AiProviderSetter setter,
                                       ThrowingJsonObjectSupplier getter,
                                       String jsCallback, String errorLogMessage, String errorBundleKey) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String provider = readString(json, "provider", null);
            JsonObject models = json != null && json.has("models") && json.get("models").isJsonObject()
                    ? json.getAsJsonObject("models")
                    : new JsonObject();
            setter.apply(
                    provider,
                    readString(models, ProviderType.CLAUDE.value(), null),
                    readString(models, ProviderType.CODEX.value(), null),
                    readString(models, ProviderType.OPENCODE.value(), null)
            );
            pushJson(jsCallback, getter.get());
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] " + errorLogMessage + ": " + e.getMessage(), e);
            showError(ClaudeCodeGuiBundle.message(errorBundleKey, e.getMessage()));
        }
    }

    public void handleGetProjectCommitPrompt() {
        try {
            String projectPath = context.getProject().getBasePath();
            String projectCommitPrompt = projectPath != null
                    ? settingsService.getProjectCommitPrompt(projectPath)
                    : "";
            pushJson(DownstreamEvent.CONFIG_PROJECT_COMMIT_PROMPT.value(), jsonOf("projectCommitPrompt", projectCommitPrompt));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get project commit prompt: " + e.getMessage(), e);
        }
    }

    public void handleSetProjectCommitPrompt(String content) {
        try {
            String projectPath = context.getProject().getBasePath();
            if (projectPath == null) {
                showError("Cannot resolve project path");
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json == null || !json.has("prompt")) {
                LOG.warn("[ProjectConfigHandler] Invalid project commit prompt request: missing prompt field");
                return;
            }
            String prompt = json.get("prompt").getAsString();
            if (prompt == null) {
                showError("Prompt cannot be empty");
                return;
            }
            prompt = prompt.trim();
            final int MAX_PROMPT_LENGTH = 10000;
            if (prompt.length() > MAX_PROMPT_LENGTH) {
                LOG.warn("[ProjectConfigHandler] Project commit prompt too long: " + prompt.length() + " characters");
                showError("Prompt length must not exceed " + MAX_PROMPT_LENGTH + " characters");
                return;
            }
            final String validatedPrompt = prompt;
            settingsService.setProjectCommitPrompt(projectPath, validatedPrompt);
            LOG.info("[ProjectConfigHandler] Set project commit prompt, length: " + validatedPrompt.length() + ", project: " + projectPath);
            JsonObject response = new JsonObject();
            response.addProperty("projectCommitPrompt", validatedPrompt);
            response.addProperty("saved", true);
            pushJson(DownstreamEvent.CONFIG_PROJECT_COMMIT_PROMPT.value(), response);
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set project commit prompt: " + e.getMessage(), e);
            showError("Failed to save project commit prompt: " + e.getMessage());
        }
    }

    public void handleGetIdeTheme() {
        try {
            String themeConfigJson = ThemeConfigService.getIdeThemeConfig().toString();
            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.THEME_RECEIVED.value(), themeConfigJson));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get IDE theme: " + e.getMessage(), e);
        }
    }

    public void handleGetEditorFontConfig() {
        try {
            String fontConfigJson = FontConfigService.getEditorFontConfig().toString();
            ApplicationManager.getApplication().invokeLater(() ->
                context.dispatchEvent(DownstreamEvent.FONT_EDITOR_CONFIG_RECEIVED.value(), fontConfigJson));
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to get editor font config: " + e.getMessage(), e);
        }
    }

    public void handleGetUiFontConfig() {
        dispatchUiFontConfigUpdate();
    }

    public void handleSetUiFontConfig(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String mode = readString(json, "mode", FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
            String customFontPath = readString(json, "customFontPath", null);

            if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(mode)) {
                FontConfigService.ValidationResult validation = FontConfigService.validateCustomUiFontFile(customFontPath);
                if (!validation.valid()) {
                    showError("Invalid font file: " + validation.errorMessage());
                    return;
                }
            }

            settingsService.setUiFontConfig(mode, customFontPath);
            dispatchUiFontConfigUpdate();
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set UI font config: " + e.getMessage(), e);
            showError("Failed to save font config: " + e.getMessage());
        }
    }

    public void handleGetCodeFontConfig() {
        dispatchCodeFontConfigUpdate();
    }

    public void handleSetCodeFontConfig(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String mode = readString(json, "mode", FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
            String customFontPath = readString(json, "customFontPath", null);

            if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(mode)) {
                FontConfigService.ValidationResult validation = FontConfigService.validateCustomUiFontFile(customFontPath);
                if (!validation.valid()) {
                    showError("Invalid font file: " + validation.errorMessage());
                    return;
                }
            }

            settingsService.setCodeFontConfig(mode, customFontPath);
            dispatchCodeFontConfigUpdate();
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set code font config: " + e.getMessage(), e);
            showError("Failed to save code font config: " + e.getMessage());
        }
    }

    public void handleBrowseUiFontFile() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter(file -> {
                        String ext = file.getExtension();
                        return ext != null && (ext.equalsIgnoreCase("ttf") || ext.equalsIgnoreCase("otf"));
                    })
                    .withTitle("Select Font File")
                    .withDescription("Select a TTF or OTF font file");

                FileChooser.chooseFile(descriptor, context.getProject(), resolveCurrentCustomFontFile(), this::saveSelectedCustomFont);
            } catch (Exception e) {
                LOG.error("[ProjectConfigHandler] Failed to open font file chooser: " + e.getMessage(), e);
            }
        });
    }

    public void handleBrowseCodeFontFile() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, false)
                    .withFileFilter(file -> {
                        String ext = file.getExtension();
                        return ext != null && (ext.equalsIgnoreCase("ttf") || ext.equalsIgnoreCase("otf"));
                    })
                    .withTitle("Select Font File")
                    .withDescription("Select a TTF or OTF font file");

                FileChooser.chooseFile(descriptor, context.getProject(), resolveCurrentCustomCodeFontFile(), this::saveSelectedCodeFont);
            } catch (Exception e) {
                LOG.error("[ProjectConfigHandler] Failed to open code font file chooser: " + e.getMessage(), e);
            }
        });
    }

    private VirtualFile resolveCurrentCustomFontFile() {
        try {
            JsonObject persistedUiFont = settingsService.getUiFontConfig();
            if (persistedUiFont.has("customFontPath") && !persistedUiFont.get("customFontPath").isJsonNull()) {
                return LocalFileSystem.getInstance().findFileByPath(persistedUiFont.get("customFontPath").getAsString());
            }
        } catch (Exception e) {
            LOG.warn("[ProjectConfigHandler] Failed to resolve current custom font path: " + e.getMessage());
        }
        return null;
    }

    private VirtualFile resolveCurrentCustomCodeFontFile() {
        try {
            JsonObject persistedCodeFont = settingsService.getCodeFontConfig();
            if (persistedCodeFont.has("customFontPath") && !persistedCodeFont.get("customFontPath").isJsonNull()) {
                return LocalFileSystem.getInstance().findFileByPath(persistedCodeFont.get("customFontPath").getAsString());
            }
        } catch (Exception e) {
            LOG.warn("[ProjectConfigHandler] Failed to resolve current custom code font path: " + e.getMessage());
        }
        return null;
    }

    private void saveSelectedCustomFont(VirtualFile file) {
        if (file == null) { return; }
        String path = file.getPath();
        FontConfigService.ValidationResult validation = FontConfigService.validateCustomUiFontFile(path);
        if (!validation.valid()) {
            context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "Invalid font file: " + validation.errorMessage());
            return;
        }
        try {
            settingsService.setUiFontConfig(FontConfigService.UI_FONT_MODE_CUSTOM_FILE, path);
            dispatchUiFontConfigUpdate();
            context.dispatchEvent(DownstreamEvent.TOAST_SUCCESS_I18N.value(), "toast.saveSuccess");
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to save selected font file: " + e.getMessage(), e);
            context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "Failed to save font config: " + e.getMessage());
        }
    }

    private void saveSelectedCodeFont(VirtualFile file) {
        if (file == null) { return; }
        String path = file.getPath();
        FontConfigService.ValidationResult validation = FontConfigService.validateCustomUiFontFile(path);
        if (!validation.valid()) {
            context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "Invalid font file: " + validation.errorMessage());
            return;
        }
        try {
            settingsService.setCodeFontConfig(FontConfigService.UI_FONT_MODE_CUSTOM_FILE, path);
            dispatchCodeFontConfigUpdate();
            context.dispatchEvent(DownstreamEvent.TOAST_SUCCESS_I18N.value(), "toast.saveSuccess");
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to save selected code font file: " + e.getMessage(), e);
            context.dispatchEvent(DownstreamEvent.TOAST_ERROR.value(), "Failed to save code font config: " + e.getMessage());
        }
    }

    public void handleGetCommitGenerationEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_COMMIT_GENERATION.value(),
            () -> jsonOf("commitGenerationEnabled", settingsService.getCommitGenerationEnabled()),
            jsonOf("commitGenerationEnabled", true),
            "Failed to get commit generation enabled");
    }

    public void handleSetCommitGenerationEnabled(String content) {
        handleBooleanToggle(content, "commitGenerationEnabled", true, "commit generation enabled",
            settingsService::setCommitGenerationEnabled,
            DownstreamEvent.CONFIG_COMMIT_GENERATION.value(),
            "Failed to save AI commit generation config");
    }

    public void handleGetAiTitleGenerationEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_AI_TITLE_GENERATION.value(),
            () -> jsonOf("aiTitleGenerationEnabled", settingsService.getAiTitleGenerationEnabled()),
            jsonOf("aiTitleGenerationEnabled", true),
            "Failed to get AI title generation enabled");
    }

    public void handleSetAiTitleGenerationEnabled(String content) {
        handleBooleanToggle(content, "aiTitleGenerationEnabled", true, "AI title generation enabled",
            settingsService::setAiTitleGenerationEnabled,
            DownstreamEvent.CONFIG_AI_TITLE_GENERATION.value(),
            "Failed to save AI title generation config");
    }

    public void handleGetStatusBarWidgetEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_STATUS_BAR_WIDGET.value(),
            () -> jsonOf("statusBarWidgetEnabled", settingsService.getStatusBarWidgetEnabled()),
            jsonOf("statusBarWidgetEnabled", true),
            "Failed to get status bar widget enabled");
    }

    public void handleSetStatusBarWidgetEnabled(String content) {
        handleBooleanToggle(content, "statusBarWidgetEnabled", true, "status bar widget enabled",
            settingsService::setStatusBarWidgetEnabled,
            DownstreamEvent.CONFIG_STATUS_BAR_WIDGET.value(),
            "Failed to save status bar config");
    }

    public void handleGetTaskCompletionNotificationEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_TASK_COMPLETION_NOTIFICATION.value(),
            () -> jsonOf("taskCompletionNotificationEnabled", settingsService.getTaskCompletionNotificationEnabled()),
            jsonOf("taskCompletionNotificationEnabled", false),
            "Failed to get task completion notification enabled");
    }

    public void handleSetTaskCompletionNotificationEnabled(String content) {
        // Default to disabled when payload is missing or the field is absent/null (opt-in feature).
        handleBooleanToggle(content, "taskCompletionNotificationEnabled", false, "task completion notification enabled",
            settingsService::setTaskCompletionNotificationEnabled,
            DownstreamEvent.CONFIG_TASK_COMPLETION_NOTIFICATION.value(),
            "Failed to save task completion notification setting");
    }

    public void handleGetAskUserQuestionNotificationEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_ASK_USER_QUESTION_NOTIFICATION.value(),
            () -> jsonOf("askUserQuestionNotificationEnabled", settingsService.getAskUserQuestionNotificationEnabled()),
            jsonOf("askUserQuestionNotificationEnabled", false),
            "Failed to get ask user question notification enabled");
    }

    public void handleSetAskUserQuestionNotificationEnabled(String content) {
        handleBooleanToggle(content, "askUserQuestionNotificationEnabled", false,
            "ask user question notification enabled",
            settingsService::setAskUserQuestionNotificationEnabled,
            DownstreamEvent.CONFIG_ASK_USER_QUESTION_NOTIFICATION.value(),
            "Failed to save ask user question notification setting");
    }

    public void handleGetMcpGatewayEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_MCP_GATEWAY.value(),
            () -> jsonOf("mcpGatewayEnabled", settingsService.getMcpGatewayEnabled()),
            jsonOf("mcpGatewayEnabled", true),
            "Failed to get MCP gateway enabled");
    }

    /**
     * 行为菜单 MCP Gateway 开关写入。存盘 + 下行回灌 + 副作用(后台线程,避免阻塞 UI):
     * 关闭 → {@link McpGatewayService#stopGateway()} 停常驻 Node 进程,下一条消息起走直连 MCP;
     * 开启 → {@link McpGatewayService#refreshConfig} 后台预热(refreshConfig 经 isCliEnabled 守卫 +
     * ensureStarted 启动进程并加载 MCP 目录)。不能用通用 {@code handleBooleanToggle},因有副作用。
     */
    public void handleSetMcpGatewayEnabled(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = readBoolean(json, "mcpGatewayEnabled", true);
            settingsService.setMcpGatewayEnabled(enabled);
            LOG.info("[ProjectConfigHandler] Set MCP gateway enabled: " + enabled);
            pushJson(DownstreamEvent.CONFIG_MCP_GATEWAY.value(), jsonOf("mcpGatewayEnabled", enabled));
            String projectPath = context.getProject().getBasePath();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    McpGatewayService gateway = McpGatewayService.getInstance(context.getProject());
                    if (enabled) {
                        gateway.refreshConfig(projectPath);
                    } else {
                        gateway.stopGateway();
                    }
                } catch (Exception e) {
                    LOG.warn("[ProjectConfigHandler] MCP gateway toggle side-effect failed: " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set MCP gateway enabled: " + e.getMessage(), e);
            showError("Failed to save MCP gateway config");
        }
    }

    public void handleGetCliPersistentEnabled() {
        respondWithJson(DownstreamEvent.CONFIG_CLI_PERSISTENT.value(),
            () -> jsonOf("cliPersistentEnabled", settingsService.getCliPersistentEnabled()),
            jsonOf("cliPersistentEnabled", true),
            "Failed to get CLI persistent enabled");
    }

    /**
     * 行为菜单 CLI 长驻会话开关写入。
     * 存盘 + 下行回灌 + 副作用:关闭时立即回收 IDLE 长驻进程(STREAMING 轮自然收尾后
     * 由周期空闲扫描兜底);开启时无需预热(首条消息同步 spawn)。
     * 用户开关与两层 {@code -D} 系统开关独立生效(三层 AND 门禁)。
     */
    public void handleSetCliPersistentEnabled(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = readBoolean(json, "cliPersistentEnabled", true);
            settingsService.setCliPersistentEnabled(enabled);
            LOG.info("[ProjectConfigHandler] Set CLI persistent enabled: " + enabled);
            pushJson(DownstreamEvent.CONFIG_CLI_PERSISTENT.value(), jsonOf("cliPersistentEnabled", enabled));
            if (!enabled) {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        CliPersistentProcessRegistry.getInstance(context.getProject()).reclaimIdleProcessesNow();
                    } catch (Exception e) {
                        LOG.warn("[ProjectConfigHandler] CLI persistent toggle side-effect failed: "
                                + e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set CLI persistent enabled: " + e.getMessage(), e);
            showError("Failed to save CLI persistent config");
        }
    }

    /**
     * 读取 Smithery Registry API Key 配置状态。
     * <p>安全:下行只返回 {@code hasKey}(是否已配置)+{@code masked}(前2••••后4 掩码),
     * 绝不回传明文 key。前端据此显示"已配置(••••1234)"占位,重新输入才覆盖。
     */
    public void handleGetSmitheryApiKey() {
        respondWithJson(DownstreamEvent.CONFIG_SMITHERY_API_KEY.value(),
            () -> {
                String key = settingsService.getSmitheryApiKey();
                JsonObject obj = new JsonObject();
                obj.addProperty("hasKey", !key.isEmpty());
                obj.addProperty("masked", CredentialMasker.maskApiKey(key));
                return obj;
            },
            jsonOf("hasKey", false),
            "Failed to get Smithery API key");
    }

    /**
     * 写入 Smithery Registry API Key。空串=清除。
     * <p>安全:不日志记录 key 值,仅记 set/cleared;下行回灌掩码状态(非明文)。
     */
    public void handleSetSmitheryApiKey(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String apiKey = json.has("smitheryApiKey") && !json.get("smitheryApiKey").isJsonNull()
                    ? json.get("smitheryApiKey").getAsString() : "";
            settingsService.setSmitheryApiKey(apiKey);
            LOG.info("[ProjectConfigHandler] Smithery API key " + (apiKey.isEmpty() ? "cleared" : "updated"));
            JsonObject payload = new JsonObject();
            payload.addProperty("hasKey", !apiKey.isEmpty());
            payload.addProperty("masked", CredentialMasker.maskApiKey(apiKey));
            pushJson(DownstreamEvent.CONFIG_SMITHERY_API_KEY.value(), payload);
            showSuccess(apiKey.isEmpty() ? "Smithery API key cleared" : "Smithery API key saved");
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to set Smithery API key: " + e.getMessage(), e);
            showError("Failed to save Smithery API key");
        }
    }

    private void dispatchUiFontConfigUpdate() {
        try {
            String uiFontConfigJson = FontConfigService.getResolvedUiFontConfigJson(settingsService);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.FONT_UI_CONFIG_RECEIVED.value(), uiFontConfigJson);
                context.dispatchEvent(DownstreamEvent.FONT_APPLY_UI.value(), uiFontConfigJson);
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to dispatch UI font config: " + e.getMessage(), e);
        }
    }

    private void dispatchCodeFontConfigUpdate() {
        try {
            String codeFontConfigJson = FontConfigService.getResolvedCodeFontConfigJson(settingsService);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.FONT_CODE_CONFIG_RECEIVED.value(), codeFontConfigJson);
                context.dispatchEvent(DownstreamEvent.FONT_APPLY_CODE.value(), codeFontConfigJson);
            });
        } catch (Exception e) {
            LOG.error("[ProjectConfigHandler] Failed to dispatch code font config: " + e.getMessage(), e);
        }
    }

    /** Get usage statistics. Supports both Claude and Codex providers. */
    public void handleGetUsageStatistics(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                String projectPath = "all";
                String provider = CommonConstants.PROVIDER_CLAUDE;
                long cutoffTime = 0;
                if (content != null && !content.isEmpty() && !content.equals("{}")) {
                    try {
                        JsonObject json = gson.fromJson(content, JsonObject.class);
                        if (json.has("scope")) {
                            projectPath = "current".equals(json.get("scope").getAsString())
                                ? context.getProject().getBasePath() : "all";
                        }
                        if (json.has("provider")) {
                            provider = json.get("provider").getAsString();
                        }
                        if (json.has("dateRange")) {
                            String dateRange = json.get("dateRange").getAsString();
                            long now = System.currentTimeMillis();
                            if ("7d".equals(dateRange)) { cutoffTime = now - 7L * 24 * 60 * 60 * 1000; }
                            else if ("30d".equals(dateRange)) { cutoffTime = now - 30L * 24 * 60 * 60 * 1000; }
                        }
                    } catch (Exception e) {
                        projectPath = "current".equals(content) ? context.getProject().getBasePath() : content;
                    }
                }
                String json;
                if (CommonConstants.PROVIDER_CODEX.equals(provider)) {
                    CodexHistoryReader reader = new CodexHistoryReader();
                    CodexHistoryReader.ProjectStatistics stats = reader.getProjectStatistics(projectPath, cutoffTime);
                    LOG.info("[ProjectConfigHandler] Codex statistics - sessions: " + stats.totalSessions +
                             ", cost: " + stats.estimatedCost + ", total tokens: " + stats.totalUsage.totalTokens);
                    json = gson.toJson(stats);
                } else {
                    ClaudeHistoryReader reader = new ClaudeHistoryReader();
                    json = gson.toJson(reader.getProjectStatistics(projectPath, cutoffTime));
                }
                final String statsJson = json;
                ApplicationManager.getApplication().invokeLater(() ->
                    context.dispatchEvent(DownstreamEvent.USAGE_STATISTICS.value(), statsJson));
            } catch (Exception e) {
                LOG.error("[ProjectConfigHandler] Failed to get usage statistics: " + e.getMessage(), e);
                showError("Failed to get statistics: " + e.getMessage());
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
