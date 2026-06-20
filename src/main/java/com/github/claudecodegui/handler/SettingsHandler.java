package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.provider.ModelProviderHandler;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import com.github.claudecodegui.util.LanguageConfigService;
import com.github.claudecodegui.util.ThemeConfigService;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Settings and usage statistics message handler.
 * Delegates to focused sub-handlers for each concern.
 */
public class SettingsHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SettingsHandler.class);
    private final Gson gson = GsonHolder.GSON;

    private final InputHistoryHandler inputHistoryHandler;
    private final UsagePushService usagePushService;
    private final PermissionModeHandler permissionModeHandler;
    private final ModelProviderHandler modelProviderHandler;
    private final NodePathHandler nodePathHandler;
    private final ClaudeCliPathHandler claudeCliPathHandler;
    private final ProjectConfigHandler projectConfigHandler;

    private static final String[] SUPPORTED_TYPES = {
        "get_mode",
        "set_mode", "set_session_mode",
        "set_model", "set_session_model",
        "set_provider", "set_session_provider",
        "set_reasoning_effort",
        "set_codex_fast_mode",
        "get_node_path",
        "set_node_path",
        "get_claude_cli_path",
        "set_claude_cli_path",
        "get_usage_statistics",
        "get_working_directory",
        "set_working_directory",
        "get_editor_font_config",
        "get_ui_font_config",
        "set_ui_font_config",
        "browse_ui_font_file",
        "get_code_font_config",
        "set_code_font_config",
        "browse_code_font_file",
        "get_streaming_enabled",
        "set_streaming_enabled",
        "get_invocation_mode", "get_session_invocation_mode", "get_session_runtime_state",
        "set_invocation_mode",
        "set_cli_path",
        "get_codex_sandbox_mode",
        "set_codex_sandbox_mode",
        "get_send_shortcut",
        "set_send_shortcut",
        "get_auto_open_file_enabled",
        "set_auto_open_file_enabled",
        "get_permission_dialog_timeout",
        "set_permission_dialog_timeout",
        "get_commit_generation_enabled",
        "set_commit_generation_enabled",
        "get_status_bar_widget_enabled",
        "set_status_bar_widget_enabled",
        "get_task_completion_notification_enabled",
        "set_task_completion_notification_enabled",
        "get_ai_title_generation_enabled",
        "set_ai_title_generation_enabled",
        "get_ide_theme",
        "get_commit_prompt",
        "set_commit_prompt",
        "get_commit_ai_config",
        "set_commit_ai_config",
        "get_prompt_enhancer_config",
        "set_prompt_enhancer_config",
        "get_project_commit_prompt",
        "set_project_commit_prompt",
        "get_input_history",
        "record_input_history",
        "delete_input_history_item",
        "clear_input_history",
        // User language preference
        "set_user_language",
        "get_user_language",
        "clear_user_language",
        // Runtime policy
        "get_runtime_policy",
        "set_runtime_policy",
        "reset_runtime_policy",
        "get_runtime_policy_schema"
    };

    public SettingsHandler(HandlerContext context) {
        super(context);
        this.inputHistoryHandler = new InputHistoryHandler(context);
        this.usagePushService = new UsagePushService(context);
        this.permissionModeHandler = new PermissionModeHandler(context);
        this.modelProviderHandler = new ModelProviderHandler(context, usagePushService);
        this.nodePathHandler = new NodePathHandler(context);
        this.claudeCliPathHandler = new ClaudeCliPathHandler(context);
        this.projectConfigHandler = new ProjectConfigHandler(context);
        // Register theme change listener to automatically notify frontend when IDE theme changes
        registerThemeChangeListener();
    }

    /**
     * Register theme change listener.
     */
    private void registerThemeChangeListener() {
        ThemeConfigService.registerThemeChangeListener(themeConfig -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                dispatchEvent("theme.changed", escapeJs(themeConfig.toString()));
            });
        });
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            // Permission mode
            case "get_mode":
                permissionModeHandler.handleGetMode();
                return true;
            case "set_mode":
                permissionModeHandler.handleSetMode(content);
                return true;
            case "set_session_mode":
                permissionModeHandler.handleSetSessionMode(content);
                return true;
            // Model and provider
            case "set_model":
                modelProviderHandler.handleSetModel(content);
                return true;
            case "set_session_model":
                modelProviderHandler.handleSetSessionModel(content);
                return true;
            case "set_provider":
                modelProviderHandler.handleSetProvider(content);
                return true;
            case "set_session_provider":
                modelProviderHandler.handleSetSessionProvider(content);
                return true;
            case "set_reasoning_effort":
                modelProviderHandler.handleSetReasoningEffort(content);
                return true;
            case "set_codex_fast_mode":
                modelProviderHandler.handleSetCodexFastMode(content);
                return true;
            // Node path
            case "get_node_path":
                nodePathHandler.handleGetNodePath();
                return true;
            case "set_node_path":
                nodePathHandler.handleSetNodePath(content);
                return true;
            // Claude CLI path
            case "get_claude_cli_path":
                claudeCliPathHandler.handleGetClaudeCliPath();
                return true;
            case "set_claude_cli_path":
                claudeCliPathHandler.handleSetClaudeCliPath(content);
                return true;
            // Project configuration
            case "get_usage_statistics":
                projectConfigHandler.handleGetUsageStatistics(content);
                return true;
            case "get_working_directory":
                projectConfigHandler.handleGetWorkingDirectory();
                return true;
            case "set_working_directory":
                projectConfigHandler.handleSetWorkingDirectory(content);
                return true;
            case "get_editor_font_config":
                projectConfigHandler.handleGetEditorFontConfig();
                return true;
            case "get_ui_font_config":
                projectConfigHandler.handleGetUiFontConfig();
                return true;
            case "set_ui_font_config":
                projectConfigHandler.handleSetUiFontConfig(content);
                return true;
            case "browse_ui_font_file":
                projectConfigHandler.handleBrowseUiFontFile();
                return true;
            case "get_code_font_config":
                projectConfigHandler.handleGetCodeFontConfig();
                return true;
            case "set_code_font_config":
                projectConfigHandler.handleSetCodeFontConfig(content);
                return true;
            case "browse_code_font_file":
                projectConfigHandler.handleBrowseCodeFontFile();
                return true;
            case "get_streaming_enabled":
                projectConfigHandler.handleGetStreamingEnabled();
                return true;
            case "set_streaming_enabled":
                projectConfigHandler.handleSetStreamingEnabled(content);
                return true;
            case "get_invocation_mode":
                projectConfigHandler.handleGetInvocationMode();
                return true;
            case "get_session_invocation_mode":
                projectConfigHandler.handleGetSessionInvocationMode();
                return true;
            case "get_session_runtime_state":
                projectConfigHandler.handleGetSessionRuntimeState();
                return true;
            case "set_invocation_mode":
                projectConfigHandler.handleSetInvocationMode(content);
                return true;
            case "set_cli_path":
                projectConfigHandler.handleSetCliPath(content);
                return true;
            case "get_codex_sandbox_mode":
                projectConfigHandler.handleGetCodexSandboxMode();
                return true;
            case "set_codex_sandbox_mode":
                projectConfigHandler.handleSetCodexSandboxMode(content);
                return true;
            case "get_send_shortcut":
                projectConfigHandler.handleGetSendShortcut();
                return true;
            case "set_send_shortcut":
                projectConfigHandler.handleSetSendShortcut(content);
                return true;
            case "get_auto_open_file_enabled":
                projectConfigHandler.handleGetAutoOpenFileEnabled();
                return true;
            case "set_auto_open_file_enabled":
                projectConfigHandler.handleSetAutoOpenFileEnabled(content);
                return true;
            case "get_permission_dialog_timeout":
                projectConfigHandler.handleGetPermissionDialogTimeout();
                return true;
            case "set_permission_dialog_timeout":
                projectConfigHandler.handleSetPermissionDialogTimeout(content);
                return true;
            case "get_commit_generation_enabled":
                projectConfigHandler.handleGetCommitGenerationEnabled();
                return true;
            case "set_commit_generation_enabled":
                projectConfigHandler.handleSetCommitGenerationEnabled(content);
                return true;
            case "get_status_bar_widget_enabled":
                projectConfigHandler.handleGetStatusBarWidgetEnabled();
                return true;
            case "set_status_bar_widget_enabled":
                projectConfigHandler.handleSetStatusBarWidgetEnabled(content);
                return true;
            case "get_task_completion_notification_enabled":
                projectConfigHandler.handleGetTaskCompletionNotificationEnabled();
                return true;
            case "set_task_completion_notification_enabled":
                projectConfigHandler.handleSetTaskCompletionNotificationEnabled(content);
                return true;
            case "get_ai_title_generation_enabled":
                projectConfigHandler.handleGetAiTitleGenerationEnabled();
                return true;
            case "set_ai_title_generation_enabled":
                projectConfigHandler.handleSetAiTitleGenerationEnabled(content);
                return true;
            case "get_ide_theme":
                projectConfigHandler.handleGetIdeTheme();
                return true;
            case "get_commit_prompt":
                projectConfigHandler.handleGetCommitPrompt();
                return true;
            case "set_commit_prompt":
                projectConfigHandler.handleSetCommitPrompt(content);
                return true;
            case "get_commit_ai_config":
                projectConfigHandler.handleGetCommitAiConfig();
                return true;
            case "set_commit_ai_config":
                projectConfigHandler.handleSetCommitAiConfig(content);
                return true;
            case "get_prompt_enhancer_config":
                projectConfigHandler.handleGetPromptEnhancerConfig();
                return true;
            case "set_prompt_enhancer_config":
                projectConfigHandler.handleSetPromptEnhancerConfig(content);
                return true;
            case "get_project_commit_prompt":
                projectConfigHandler.handleGetProjectCommitPrompt();
                return true;
            case "set_project_commit_prompt":
                projectConfigHandler.handleSetProjectCommitPrompt(content);
                return true;
            // Input history
            case "get_input_history":
                inputHistoryHandler.handleGetInputHistory();
                return true;
            case "record_input_history":
                inputHistoryHandler.handleRecordInputHistory(content);
                return true;
            case "delete_input_history_item":
                inputHistoryHandler.handleDeleteInputHistoryItem(content);
                return true;
            case "clear_input_history":
                inputHistoryHandler.handleClearInputHistory();
                return true;
            // User language preference
            case "set_user_language":
                handleSetUserLanguage(content);
                return true;
            case "get_user_language":
                handleGetUserLanguage();
                return true;
            case "clear_user_language":
                handleClearUserLanguage();
                return true;
            // Runtime policy
            case "get_runtime_policy":
                handleGetRuntimePolicy();
                return true;
            case "set_runtime_policy":
                handleSetRuntimePolicy(content);
                return true;
            case "reset_runtime_policy":
                handleResetRuntimePolicy();
                return true;
            case "get_runtime_policy_schema":
                handleGetRuntimePolicySchema();
                return true;
            default:
                return false;
        }
    }

    /**
     * Handle set_user_language: save user's manual language preference.
     * On failure, push the authoritative config back so the webview can roll
     * back its optimistic UI update.
     */
    private void handleSetUserLanguage(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String language = json.has("language") && !json.get("language").isJsonNull()
                    ? json.get("language").getAsString() : null;
            if (language == null || language.isEmpty()) {
                LOG.warn("[SettingsHandler] set_user_language rejected: empty language");
                pushLanguageConfig();
                return;
            }
            LanguageConfigService.setUserLanguage(context.getSettingsService(), language);
            LOG.info("[SettingsHandler] Saved user language preference: " + language);
            pushLanguageConfig();
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to save user language: " + e.getMessage(), e);
            pushLanguageConfig();
        }
    }

    /**
     * Handle get_user_language: return user's saved language preference.
     */
    private void handleGetUserLanguage() {
        String userLanguage = LanguageConfigService.getUserLanguage(context.getSettingsService());
        JsonObject response = new JsonObject();
        response.addProperty("language", userLanguage != null ? userLanguage : "");
        response.addProperty("manuallySet", userLanguage != null);
        dispatchEvent("language.user_language", escapeJs(response.toString()));
    }

    /**
     * Handle clear_user_language: clear user's manual language preference.
     * Pushes the authoritative config on both success and failure so the
     * webview always reflects the persisted state.
     */
    private void handleClearUserLanguage() {
        try {
            LanguageConfigService.clearUserLanguage(context.getSettingsService());
            LOG.info("[SettingsHandler] Cleared user language preference");
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to clear user language: " + e.getMessage(), e);
        } finally {
            pushLanguageConfig();
        }
    }

    private void pushLanguageConfig() {
        JsonObject languageConfig = LanguageConfigService.getLanguageConfig(context.getSettingsService());
        dispatchEvent("language.apply", escapeJs(languageConfig.toString()));
    }

    /**
     * Expose getModelContextLimit for callers that previously used the static method on SettingsHandler.
     */
    public static int getModelContextLimit(String model) {
        return ModelProviderHandler.getModelContextLimit(model);
    }

    // ── Runtime Policy Handlers ──────────────────────────────────────────────

    private void handleGetRuntimePolicy() {
        try {
            var policyConfig = context.getSettingsService().getRuntimePolicy();
            JsonObject response = serializeRuntimePolicyToJson(policyConfig);
            dispatchEvent("runtime_policy", escapeJs(response.toString()));
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to get runtime policy: " + e.getMessage(), e);
            dispatchEvent("runtime_policy_error", escapeJs("获取路由策略失败: " + e.getMessage()));
        }
    }

    private void handleSetRuntimePolicy(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            var policyConfig = parseRuntimePolicyFromJson(json);
            var result = context.getSettingsService().setRuntimePolicy(policyConfig);
            if (result.isValid()) {
                // 保存成功，推送最新配置
                var savedConfig = context.getSettingsService().getRuntimePolicy();
                JsonObject response = serializeRuntimePolicyToJson(savedConfig);
                response.addProperty("success", true);
                dispatchEvent("runtime_policy_updated", escapeJs(response.toString()));
            } else {
                // 校验失败，返回错误
                JsonObject response = new JsonObject();
                response.addProperty("success", false);
                var errorsArray = new com.google.gson.JsonArray();
                result.errors().forEach(errorsArray::add);
                response.add("errors", errorsArray);
                dispatchEvent("runtime_policy_updated", escapeJs(response.toString()));
            }
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to set runtime policy: " + e.getMessage(), e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            var errorsArray = new com.google.gson.JsonArray();
            errorsArray.add("保存失败: " + e.getMessage());
            response.add("errors", errorsArray);
            dispatchEvent("runtime_policy_updated", escapeJs(response.toString()));
        }
    }

    private void handleResetRuntimePolicy() {
        try {
            context.getSettingsService().resetRuntimePolicy();
            var defaultConfig = context.getSettingsService().getRuntimePolicy();
            JsonObject response = serializeRuntimePolicyToJson(defaultConfig);
            response.addProperty("success", true);
            response.addProperty("reset", true);
            dispatchEvent("runtime_policy_updated", escapeJs(response.toString()));
        } catch (Exception e) {
            LOG.error("[SettingsHandler] Failed to reset runtime policy: " + e.getMessage(), e);
        }
    }

    private void handleGetRuntimePolicySchema() {
        // 返回 schema 描述，前端据此渲染表单与提示
        JsonObject schema = new JsonObject();
        schema.addProperty("title", "路由策略配置");
        schema.addProperty("description", "配置各 provider 的 runtime 模式（SDK/CLI）。修改后立即生效。");

        var claudeSchema = new JsonObject();
        claudeSchema.addProperty("type", "object");
        claudeSchema.addProperty("description", "Claude provider 路由策略");
        var claudeProps = new JsonObject();
        claudeProps.addProperty("enabled", "是否启用 (boolean)");
        claudeProps.addProperty("supported", "支持的 runtime 列表 (array: SDK, CLI)");
        claudeProps.addProperty("default", "默认 runtime (SDK 或 CLI)");
        claudeSchema.add("properties", claudeProps);
        schema.add("claude", claudeSchema);

        var codexSchema = new JsonObject();
        codexSchema.addProperty("type", "object");
        codexSchema.addProperty("description", "Codex provider 路由策略");
        var codexProps = new JsonObject();
        codexProps.addProperty("enabled", "是否启用 (boolean)");
        codexProps.addProperty("supported", "支持的 runtime 列表 (array: SDK, CLI)");
        codexProps.addProperty("default", "默认 runtime (SDK 或 CLI)");
        codexSchema.add("properties", codexProps);
        schema.add("codex", codexSchema);

        dispatchEvent("runtime_policy_schema", escapeJs(schema.toString()));
    }

    private JsonObject serializeRuntimePolicyToJson(
            com.github.claudecodegui.config.RuntimePolicyConfig policyConfig) {
        JsonObject result = new JsonObject();
        JsonObject providers = new JsonObject();
        for (var entry : policyConfig.providers().entrySet()) {
            String key = entry.getKey().toLowerCase();
            var policy = entry.getValue();
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
            providers.add(key, policyObj);
        }
        result.add("providers", providers);
        return result;
    }

    private com.github.claudecodegui.config.RuntimePolicyConfig parseRuntimePolicyFromJson(JsonObject json) {
        var config = new com.github.claudecodegui.config.RuntimePolicyConfig();
        var providers = new java.util.LinkedHashMap<ProviderType,
                com.github.claudecodegui.config.ProviderRuntimePolicy>();

        if (json.has("providers") && json.get("providers").isJsonObject()) {
            JsonObject providersObj = json.getAsJsonObject("providers");
            for (String key : providersObj.keySet()) {
                var pt = ProviderType.fromString(key);
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
                        LOG.warn("[SettingsHandler] Invalid runtime policy for " + key + ": " + e.getMessage());
                    }
                }
            }
        }
        config.setProviders(providers);
        return config;
    }
}
