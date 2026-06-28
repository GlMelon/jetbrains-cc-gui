package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.handler.UsagePushService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.selection.DefaultModelCapabilityResolver;
import com.github.claudecodegui.model.selection.ModelSelectionRequest;
import com.github.claudecodegui.model.selection.ModelSelectionResult;
import com.github.claudecodegui.protocol.DownstreamEvent;

import com.github.claudecodegui.session.SessionSendService;
import com.github.claudecodegui.skill.SlashCommandRegistry;
import com.github.claudecodegui.util.EditorFileUtils;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles model and provider selection, reasoning effort, and slash command refresh.
 */
public class ModelProviderHandler {

    private static final Logger LOG = Logger.getInstance(ModelProviderHandler.class);

    private final HandlerContext context;
    private final UsagePushService usagePushService;
    private final Gson gson = GsonHolder.GSON;

    public ModelProviderHandler(HandlerContext context, UsagePushService usagePushService) {
        this.context = context;
        this.usagePushService = usagePushService;
    }

    public void handleSetModel(String content) {
        try {
            ModelChangeRequest req = parseModelChange(content);
            applyModelChange(req.model(), req.contextWindowOverride(), req.longContextEnabled(), false);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set model: " + e.getMessage(), e);
        }
    }

    public void handleSetSessionModel(String content) {
        try {
            ModelChangeRequest req = parseModelChange(content);
            applyModelChange(req.model(), req.contextWindowOverride(), req.longContextEnabled(), true);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set session model: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 set_model / set_session_model 上行 payload。
     *
     * <p>归一化后的协议(新前端):{@code {model, longContextEnabled}} —— 前端只发意图布尔,
     * 由后端权威计算 effectiveContextWindow(见 {@link DefaultModelCapabilityResolver})。
     *
     * <p>向后兼容(旧前端):payload 仅含 {@code {model, contextWindow}} 而无 {@code longContextEnabled}
     * 时,从 {@code contextWindow>=1M} 推导 longContextEnabled,保持行为等价。
     */
    private static ModelChangeRequest parseModelChange(String content) {
        String model;
        Integer contextWindowOverride = null;
        boolean longContextEnabled = false;

        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("{")) {
            JsonObject json = GsonHolder.GSON.fromJson(trimmed, JsonObject.class);
            model = json.has("model") ? json.get("model").getAsString() : "";
            if (json.has("contextWindow") && !json.get("contextWindow").isJsonNull()) {
                contextWindowOverride = json.get("contextWindow").getAsInt();
            }
            if (json.has("longContextEnabled") && !json.get("longContextEnabled").isJsonNull()) {
                longContextEnabled = json.get("longContextEnabled").getAsBoolean();
            } else if (contextWindowOverride != null && contextWindowOverride >= CommonConstants.ONE_MILLION_CONTEXT_WINDOW) {
                // 向后兼容:旧前端仅发 contextWindow,从中推导 longContextEnabled
                longContextEnabled = true;
            }
        } else {
            model = content;
        }
        return new ModelChangeRequest(model, contextWindowOverride, longContextEnabled);
    }

    /** set_model / set_session_model 上行 payload 的解析结果。 */
    private record ModelChangeRequest(String model, Integer contextWindowOverride, boolean longContextEnabled) {
    }

    /**
     * Common model change logic for both set_model and set_session_model.
     *
     * <p>[1m] suffix handling:
     * <ul>
     *   <li>Claude models (non-Haiku): append [1m] when contextWindow >= 1M (CLI and SDK recognize it)</li>
     *   <li>Non-Claude models: do NOT append [1m] (CLI may not recognize it → model name mismatch error)</li>
     *   <li>Haiku: never append [1m] (does not support 1M context)</li>
     * </ul>
     *
     * @param model               clean model ID (without [1m] suffix)
     * @param contextWindowOverride desired context window from frontend (null = use backend default)
     * @param longContextEnabled  前端显式上送的长上下文意图(新协议权威来源);
     *                            旧前端仅发 contextWindow 时由 {@link #parseModelChange} 兼容推导
     * @param isSessionOnly       true for set_session_model(仅影响 provider 来源,见
     *                            {@link #confirmedProviderForModelChange};不再守卫全局默认更新)
     */
    private void applyModelChange(String model, Integer contextWindowOverride,
                                  boolean longContextEnabled, boolean isSessionOnly) {
        LOG.info("[ModelProviderHandler] Setting model to: " + model
                + (contextWindowOverride != null ? " (contextWindow=" + contextWindowOverride + ")" : ""));

        // 检测模型是否真变化:仅在实际切换时旋转运行时会话 epoch,避免重复设置同 model 触发不必要的回调丢弃
        String previousModel = context.getCurrentModel();
        boolean modelChanged = hasModelChanged(previousModel, model);

        // 模型选择同时更新全局默认(粘性):无论 set_model 还是 set_session_model,
        // 都让用户最近选择的模型成为新建会话的默认。model 与 provider 成对更新
        // (见 handleSetSessionProvider),以保证新建会话的 provider/model 一致。
        context.setCurrentModel(model);

        final String confirmedProvider = isSessionOnly
                ? (context.getSession() != null ? context.getSession().getProvider() : context.getCurrentProvider())
                : context.getCurrentProvider();
        ModelSelectionResult selection = new DefaultModelCapabilityResolver(context.getSettingsService().getModelRegistry())
                .resolve(new ModelSelectionRequest(
                        confirmedProvider,
                        model,
                        contextWindowOverride,
                        longContextEnabled
                ));
        String storedModel = selection.storedModel();

        if (context.getSession() != null) {
            context.getSession().setModel(storedModel);
            if (modelChanged) {
                // 模型切换 = 运行时意图变化:旋转 epoch 让旧模型的 in-flight 回调
                // 被 ClaudeMessageHandler/CodexMessageHandler 守卫丢弃,避免串台到新模型会话。
                context.getSession().getState().rotateRuntimeSessionEpoch();
            }
            LOG.info("[ModelProviderHandler] Updated session model to: " + storedModel);
        }

        com.github.claudecodegui.notifications.ClaudeNotifier.setModel(context.getProject(), model);

        // Store contextWindow override for later use by message handlers
        context.setCurrentModelContextWindow(contextWindowOverride);
        if (context.getSession() != null) {
            context.getSession().getState().setContextWindowOverride(contextWindowOverride);
        }

        int newMaxTokens = selection.maxTokens();
        LOG.info("[ModelProviderHandler] Model context limit: " + newMaxTokens
                + " tokens for selected model: " + model
                + ", resolved model: " + selection.resolvedActualModel());

        final String confirmedModel = model;
        final ModelSelectionResult confirmedSelection = selection;
        ApplicationManager.getApplication().invokeLater(() -> {
            // [归一化] onModelConfirmed(modelId, provider) 原为两参数,归一化为单 JSON {modelId, provider}
            JsonObject confirmedPayload = new JsonObject();
            confirmedPayload.addProperty("modelId", confirmedModel);
            confirmedPayload.addProperty("provider", confirmedProvider);
            context.dispatchEvent(DownstreamEvent.MODEL_CONFIRMED.value(), context.escapeJs(gson.toJson(confirmedPayload)));
            context.dispatchEvent(
                    DownstreamEvent.MODEL_SELECTION.value(),
                    context.escapeJs(gson.toJson(buildModelSelectionPayload(confirmedSelection)))
            );
            usagePushService.pushUsageUpdateAfterModelChange(newMaxTokens);
        });
    }

    /**
     * 判断模型是否真变化(供 applyModelChange 决定是否旋转运行时会话 epoch)。
     * previousModel 为 null 表示首次设置,视为变化。
     */
    static boolean hasModelChanged(String previousModel, String newModel) {
        if (previousModel == null) {
            return true;
        }
        return !previousModel.equals(newModel);
    }

    /**
     * Check if a model is a Claude-family model (starts with claude- or claude_).
     * Used to decide whether to append the [1m] suffix.
     */
    private static boolean isClaudeModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase();
        return lower.startsWith("claude-") || lower.startsWith("claude_");
    }

    public static JsonObject buildModelSelectionPayload(ModelSelectionResult selection) {
        JsonObject payload = new JsonObject();
        payload.addProperty("provider", selection.provider());
        payload.addProperty("selectedModel", selection.selectedModel());
        payload.addProperty("storedModel", selection.storedModel());
        payload.addProperty("resolvedActualModel", selection.resolvedActualModel());
        payload.addProperty("effectiveContextWindow", selection.effectiveContextWindow());
        payload.addProperty("maxTokens", selection.maxTokens());
        payload.addProperty("supportsLongContext", selection.supportsLongContext());
        return payload;
    }

    public void handleSetProvider(String content) {
        try {
            String provider = parseProvider(content);
            String previousProvider = context.getCurrentProvider();

            LOG.info("[ModelProviderHandler] Setting provider to: " + provider);
            context.setCurrentProvider(provider);

            if (context.getSession() != null) {
                context.getSession().setProvider(provider);
            }

            // Bug fix (Node process leak L2): when the tab moves AWAY from Claude
            // to another SDK family (currently only Codex), the lingering Claude
            // daemon would otherwise stay alive for the rest of the tab's lifetime.
            // The daemon caches process.env, so even if the user comes back to
            // Claude with refreshed credentials, the cached env would persist —
            // shutting it down here forces the next Claude message to spawn a
            // fresh daemon. The daemon restart on return is lazy (deferred to
            // the next claude.send call), so users pay ~5–10s only when they
            // actually send the next Claude message.
            shutdownStaleClaudeDaemonIfLeavingClaude(previousProvider, provider);

            refreshSlashCommandsForProvider(provider);
            usagePushService.refreshContextBar();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set provider: " + e.getMessage(), e);
        }
    }

    public void handleSetSessionProvider(String content) {
        try {
            String provider = parseProvider(content);
            String previousProvider = context.getCurrentProvider();

            LOG.info("[ModelProviderHandler] Setting session provider to: " + provider);
            // 会话级 provider 切换也更新全局默认(粘性),与 set_model 的全局默认更新
            // 成对,保证新建会话沿用用户最近选择的 provider(而非回退到默认)。
            context.setCurrentProvider(provider);
            if (context.getSession() != null) {
                context.getSession().setProvider(provider);
            }

            // 与 handleSetProvider 保持一致:离开 Claude 家族时清理残留 daemon,
            // 避免切走后 claude daemon 在会话剩余生命周期内泄漏。
            shutdownStaleClaudeDaemonIfLeavingClaude(previousProvider, provider);

            refreshSlashCommandsForProvider(provider);
            usagePushService.refreshContextBar();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set session provider: " + e.getMessage(), e);
        }
    }

    /**
     * Pure decision predicate: should we shut down the Claude daemon when the
     * tab provider transitions from {@code previousProvider} to {@code newProvider}?
     *
     * <p>Returns true only on Claude → non-Claude transitions. Same-direction
     * reaffirmations (e.g. {@code set_provider("codex")} fired again on every
     * message send) must not restart the daemon, and Claude → Claude
     * reaffirmations must keep the warm daemon alive.
     *
     * <p>Package-private so unit tests can verify the full transition matrix
     * without spinning up a HandlerContext or ClaudeSDKBridge.
     */
    static boolean shouldShutdownClaudeDaemonOnProviderSwitch(String previousProvider, String newProvider) {
        if (!CommonConstants.PROVIDER_CLAUDE.equals(previousProvider)) {
            return false;
        }
        // Empty/null newProvider means "not set yet" (initialization, race), NOT
        // "user moved away from Claude". Treating it as a leave-claude transition
        // would cause spurious daemon restarts (~5–10s) when set_provider arrives
        // before the tab has fully booted.
        if (newProvider == null || newProvider.isEmpty() || CommonConstants.PROVIDER_CLAUDE.equals(newProvider)) {
            return false;
        }
        return true;
    }

    /**
     * Shut down the Claude daemon when leaving the Claude family.
     * Delegates the decision to {@link #shouldShutdownClaudeDaemonOnProviderSwitch}
     * and only performs the side effect (calling
     * {@link com.github.claudecodegui.provider.claude.ClaudeSDKBridge#shutdownDaemon()})
     * when the decision says yes and the bridge is present.
     *
     * @return true when shutdown was actually invoked
     */
    boolean shutdownStaleClaudeDaemonIfLeavingClaude(String previousProvider, String newProvider) {
        if (!shouldShutdownClaudeDaemonOnProviderSwitch(previousProvider, newProvider)) {
            return false;
        }
        if (context.getClaudeSDKBridge() == null) {
            return false;
        }
        try {
            context.getClaudeSDKBridge().shutdownDaemon();
            LOG.info("[ModelProviderHandler] Shut down Claude daemon after switching to: " + newProvider);
            return true;
        } catch (Exception e) {
            LOG.warn("[ModelProviderHandler] Failed to shut down Claude daemon on provider switch: "
                    + e.getMessage(), e);
            return false;
        }
    }

    public void handleSetReasoningEffort(String content) {
        try {
            String effort = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("reasoningEffort")) {
                        effort = json.get("reasoningEffort").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the effort
                }
            }

            LOG.info("[ModelProviderHandler] Setting reasoning effort to: " + effort);

            if (context.getSession() != null) {
                context.getSession().setReasoningEffort(effort);
            }
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set reasoning effort: " + e.getMessage(), e);
        }
    }

    public void handleSetCodexFastMode(String content) {
        try {
            String mode = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("codexFastMode")) {
                        mode = json.get("codexFastMode").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the mode
                }
            }

            String serviceTier = SessionSendService.resolveEffectiveCodexServiceTier(mode, null);
            LOG.info("[ModelProviderHandler] Setting Codex fast mode to: " + mode
                    + ", serviceTier=" + (serviceTier != null ? serviceTier : "standard"));

            if (context.getSession() != null) {
                context.getSession().setCodexServiceTier(serviceTier);
            }
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set Codex fast mode: " + e.getMessage(), e);
        }
    }

    private void refreshSlashCommandsForProvider(String provider) {
        String cwd = null;
        if (context.getSession() != null) {
            cwd = context.getSession().getCwd();
        }
        if (cwd == null) {
            cwd = context.getProject().getBasePath();
        }

        final String finalCwd = cwd;
        CompletableFuture.runAsync(() -> {
            String currentFilePath = EditorFileUtils.getCurrentEditorFilePath(context.getProject());
            var commands = SlashCommandRegistry.getCommands(provider, finalCwd, currentFilePath);
            String json = SlashCommandRegistry.toJson(commands);

            final String codexJson;
            if (CommonConstants.PROVIDER_CODEX.equalsIgnoreCase(provider)) {
                var codexSkills = SlashCommandRegistry.getCodexSkills(finalCwd);
                codexJson = SlashCommandRegistry.toJson(codexSkills);
                LOG.info("[ModelProviderHandler] Codex skills refreshed: " + codexSkills.size() + " skills");
            } else {
                codexJson = null;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    context.callJavaScript("updateSlashCommands", context.escapeJs(json));
                    if (codexJson != null) {
                        context.dispatchEvent(DownstreamEvent.SLASH_DOLLAR_COMMANDS.value(), context.escapeJs(codexJson));
                    }
                } catch (Exception e) {
                    LOG.warn("[ModelProviderHandler] Failed to refresh slash commands: " + e.getMessage());
                }
            });
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[ModelProviderHandler] Failed to refresh slash commands asynchronously: " + ex.getMessage(), ex);
            return null;
        });
    }

    private String parseModel(String content) {
        String model = content;
        if (content != null && !content.isEmpty()) {
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                if (json.has("model")) {
                    model = json.get("model").getAsString();
                }
            } catch (Exception e) {
                // content itself is the model
            }
        }
        return model;
    }

    private String confirmedProviderForModelChange(boolean isSessionOnly) {
        if (isSessionOnly && context.getSession() != null) {
            return context.getSession().getProvider();
        }
        return context.getCurrentProvider();
    }

    private String parseProvider(String content) {
        String provider = content;
        if (content != null && !content.isEmpty()) {
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                if (json.has("provider")) {
                    provider = json.get("provider").getAsString();
                }
            } catch (Exception e) {
                // content itself is the provider
            }
        }
        return provider;
    }

    private String resolveConfiguredClaudeModelFromSettings(String baseModel) {
        try {
            JsonObject claudeSettings = context.getSettingsService().readClaudeSettings();
            if (claudeSettings == null || !claudeSettings.has("env") || !claudeSettings.get("env").isJsonObject()) {
                return baseModel;
            }
            return resolveConfiguredClaudeModel(baseModel, claudeSettings.getAsJsonObject("env"));
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to resolve actual model name: " + e.getMessage());
        }

        return baseModel;
    }

    static String resolveConfiguredClaudeModel(String baseModel, JsonObject env) {
        if (baseModel == null || baseModel.isEmpty() || env == null) {
            return baseModel;
        }

        String mainModel = readConfiguredEnvValue(env, CommonConstants.ENV_ANTHROPIC_MODEL);
        if (mainModel != null) {
            return mainModel;
        }

        ClaudeRole role = ClaudeRole.fromModelId(baseModel);
        if (role == null) {
            return baseModel;
        }

        // envKeys 已含 fallback 顺序(Fable→Opus、Haiku→SMALL_FAST→DEFAULT_HAIKU)
        for (String envKey : role.envKeys()) {
            if (CommonConstants.ENV_ANTHROPIC_SMALL_FAST_MODEL.equals(envKey)) {
                continue;
            }
            String mapped = readConfiguredEnvValue(env, envKey);
            if (mapped != null) {
                return mapped;
            }
        }
        return baseModel;
    }

    private static String readConfiguredEnvValue(JsonObject env, String key) {
        if (env == null || key == null || !env.has(key) || env.get(key).isJsonNull()) {
            return null;
        }

        String value = env.get(key).getAsString();
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static int getModelContextLimit(String model) {
        return getModelContextLimit(ModelRegistryConfig.getDefault(), model);
    }

    public static int getModelContextLimit(ModelRegistryConfig registry, String model) {
        if (model == null || model.isEmpty()) {
            return CommonConstants.DEFAULT_CONTEXT_WINDOW;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\s*\\[([0-9.]+)([kKmM])\\]\\s*$");
        java.util.regex.Matcher matcher = pattern.matcher(model);

        if (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();

                if ("m".equals(unit)) {
                    return (int)(value * 1_000_000);
                } else if ("k".equals(unit)) {
                    return (int)(value * 1_000);
                }
            } catch (NumberFormatException e) {
                LOG.error("Failed to parse capacity from model name: " + model);
            }
        }

        if (registry != null) {
            var configured = registry.find(model);
            if (configured.isPresent()) {
                return configured.get().contextWindow();
            }
        }

        String baseModel = ModelRegistryConfig.stripCapacitySuffix(model);
        ClaudeRole role = ClaudeRole.fromModelId(baseModel);
        return role != null ? role.contextWindow() : CommonConstants.DEFAULT_CONTEXT_WINDOW;
    }

    public static boolean isKnownModel(ModelRegistryConfig registry, String model) {
        if (model == null || model.isEmpty()) {
            return false;
        }
        if (registry != null && registry.find(model).isPresent()) {
            return true;
        }
        String baseModel = ModelRegistryConfig.stripCapacitySuffix(model);
        return ClaudeRole.fromModelId(baseModel) != null;
    }
}
