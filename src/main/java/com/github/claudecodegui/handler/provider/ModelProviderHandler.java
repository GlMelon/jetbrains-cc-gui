package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.handler.UsagePushService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.selection.DefaultModelCapabilityResolver;
import com.github.claudecodegui.model.selection.ModelSelectionRequest;
import com.github.claudecodegui.model.selection.ModelSelectionResult;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.notifications.StatusBarModelResolver;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionRuntimeDefaults;
import com.github.claudecodegui.session.SessionSendService;
import com.github.claudecodegui.skill.SlashCommandRegistry;
import com.github.claudecodegui.util.EditorFileUtils;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

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
            applyModelChange(req.model(), req.identifier(), req.contextWindowOverride(), req.longContextEnabled(), false);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set model: " + e.getMessage(), e);
            notifyModelChangeFailure(e);
        }
    }

    public void handleSetSessionModel(String content) {
        try {
            ModelChangeRequest req = parseModelChange(content);
            applyModelChange(req.model(), req.identifier(), req.contextWindowOverride(), req.longContextEnabled(), true);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set session model: " + e.getMessage(), e);
            notifyModelChangeFailure(e);
        }
    }

    /**
     * 模型切换失败的用户可见提示。此前 catch 仅记日志:resolver 对未知 provider
     * fail-fast(拒绝静默兜底 claude)后,坏数据只能无声失败——用户视角"选了没反应"。
     * 未知 provider 属接入缺陷(VALID_PROVIDERS 白名单漏同步),提示信息须可直接定位。
     */
    private void notifyModelChangeFailure(Exception e) {
        try {
            if (context.getProject() != null) {
                ClaudeNotifier.showWarning(context.getProject(),
                        "模型切换失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        } catch (Exception ignored) {
            // 通知通道不可用时不吞原始日志(已 LOG.error)。
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
        String identifier = null;
        Integer contextWindowOverride = null;
        boolean longContextEnabled = false;

        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("{")) {
            JsonObject json = GsonHolder.GSON.fromJson(trimmed, JsonObject.class);
            model = json.has("model") ? json.get("model").getAsString() : "";
            if (json.has("identifier") && !json.get("identifier").isJsonNull()) {
                identifier = json.get("identifier").getAsString();
            }
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
        return new ModelChangeRequest(model, identifier, contextWindowOverride, longContextEnabled);
    }

    /** set_model / set_session_model 上行 payload 的解析结果。 */
    private record ModelChangeRequest(String model, String identifier, Integer contextWindowOverride,
                                      boolean longContextEnabled) {
    }

    /**
     * Common model change logic for both set_model and set_session_model.
     *
     * <p>[1m] suffix handling:
     * <ul>
     *   <li>Claude models (non-Haiku): append [1m] when contextWindow >= 1M (the Claude CLI recognizes it)</li>
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
    private void applyModelChange(String model, String identifier, Integer contextWindowOverride,
                                  boolean longContextEnabled, boolean isSessionOnly) {
        LOG.info("[ModelProviderHandler] Setting model to: " + model
                + (contextWindowOverride != null ? " (contextWindow=" + contextWindowOverride + ")" : ""));

        // 检测模型是否真变化:仅在实际切换时旋转运行时会话 epoch,避免重复设置同 model 触发不必要的回调丢弃
        String previousModel = context.getCurrentModel();
        boolean modelChanged = hasModelChanged(previousModel, model);
        // 真实模型切换(null/空首次设置视为 no-op):用于作废旧 usage 快照,独立于 modelChanged
        // (epoch 旋转在首次设置时仍需触发,故保留 hasModelChanged 的 null→true 语义)。
        final boolean realModelSwitch = isActualModelSwitch(previousModel, model);

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
                        identifier,
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
            if (realModelSwitch) {
                // 作废旧模型的当前上下文快照,避免被误当作新模型活跃上下文;
                // 历史 per-turn 计量(turnUsage/turnCostUsd)由工具方法保留。
                TokenUsageUtils.clearContextUsageFromSessionMessages(context.getSession().getMessages());
            }
            LOG.info("[ModelProviderHandler] Updated session model to: " + storedModel);
        }
        SessionRuntimeDefaults.rememberModel(context.getProject(), confirmedProvider, storedModel);

        ClaudeNotifier.setModel(context.getProject(), StatusBarModelResolver.displayModel(selection));

        if (realModelSwitch) {
            usagePushService.clearUsageDisplay();
        }

        // Store resolved context window for later use by message handlers (usage tracking).
        // For Claude models, frontend sends longContextEnabled (not contextWindow), so
        // contextWindowOverride is null. Use the resolver's maxTokens (which accounts for
        // longContextEnabled + supports1M) so that getEffectiveMaxTokens() returns the
        // correct value when USAGE_UPDATE is computed.
        int effectiveContextWindow = contextWindowOverride != null && contextWindowOverride > 0
                ? contextWindowOverride
                : selection.maxTokens();
        context.setCurrentModelContextWindow(effectiveContextWindow);
        if (context.getSession() != null) {
            context.getSession().getState().setContextWindowOverride(effectiveContextWindow);
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
            if (confirmedSelection.identifier() != null) {
                confirmedPayload.addProperty("identifier", confirmedSelection.identifier());
            }
            context.dispatchEvent(DownstreamEvent.MODEL_CONFIRMED.value(), gson.toJson(confirmedPayload));
            context.dispatchEvent(
                    DownstreamEvent.MODEL_SELECTION.value(),
                    gson.toJson(buildModelSelectionPayload(confirmedSelection))
            );
            if (realModelSwitch) {
                usagePushService.pushUsageUpdateAfterModelChange(newMaxTokens);
            }
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
     * 判断 provider 切换命令是否代表真实跨 provider 转换。null/空初始化值与同 provider 重申
     * 均视为 no-op(首次设置不清 usage)。与 {@link #hasModelChanged} 区别:后者在首次设置
     * (previousModel==null)时返回 true 以触发 epoch 旋转,本方法返回 false 以跳过 usage 作废。
     */
    static boolean isActualProviderSwitch(String previousProvider, String newProvider) {
        return previousProvider != null
                && newProvider != null
                && !previousProvider.isEmpty()
                && !newProvider.isEmpty()
                && !previousProvider.equals(newProvider);
    }

    /**
     * 判断 model 切换命令是否代表真实模型转换。null/空初始化值与同模型重申均视为 no-op。
     */
    static boolean isActualModelSwitch(String previousModel, String newModel) {
        return previousModel != null
                && newModel != null
                && !previousModel.isEmpty()
                && !newModel.isEmpty()
                && !previousModel.equals(newModel);
    }

    public static JsonObject buildModelSelectionPayload(ModelSelectionResult selection) {
        JsonObject payload = new JsonObject();
        payload.addProperty("provider", selection.provider());
        payload.addProperty("selectedModel", selection.selectedModel());
        if (selection.identifier() != null) {
            payload.addProperty("identifier", selection.identifier());
        }
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
            boolean providerChanged = isActualProviderSwitch(previousProvider, provider);

            LOG.info("[ModelProviderHandler] Setting provider to: " + provider);
            context.setCurrentProvider(provider);
            SessionRuntimeDefaults.rememberProvider(context.getProject(), provider);

            if (context.getSession() != null) {
                context.getSession().setProvider(provider);
                if (providerChanged) {
                    TokenUsageUtils.clearContextUsageFromSessionMessages(context.getSession().getMessages());
                }
                alignSessionModelToProvider(context.getSession(), provider);
            }

            if (providerChanged) {
                usagePushService.clearUsageDisplay();
            }

            refreshSlashCommandsForProvider(provider);
            usagePushService.refreshContextBar();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set provider: " + e.getMessage(), e);
        }
    }

    public void handleSetSessionProvider(String content) {
        try {
            String provider = parseProvider(content);

            LOG.info("[ModelProviderHandler] Setting session provider to: " + provider);
            // 会话级 provider 切换也更新全局默认(粘性),与 set_model 的全局默认更新
            // 成对,保证新建会话沿用用户最近选择的 provider(而非回退到默认)。
            context.setCurrentProvider(provider);
            SessionRuntimeDefaults.rememberProvider(context.getProject(), provider);
            if (context.getSession() != null) {
                context.getSession().setProvider(provider);
                alignSessionModelToProvider(context.getSession(), provider);
            }

            refreshSlashCommandsForProvider(provider);
            usagePushService.refreshContextBar();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set session provider: " + e.getMessage(), e);
        }
    }

    /**
     * 供应商切换后的会话模型对齐。旧实现直接把 session.getModel()(此刻仍属旧 provider)
     * 记到新 provider 名下,污染 SessionRuntimeDefaults 粘性默认:重启后以
     * provider/model 错配对回灌前端(MODEL_SELECTION),输入区显示新 provider 而欢迎页
     * logo 按 modelId 误判显示旧供应商图标。改为解析新 provider 自己的粘性模型
     * (含 registry 归属校验与首启用回退)写入 session;无可解析默认时保持现状,
     * 交由紧随其后的 set_model 决定并正确记忆。
     */
    private void alignSessionModelToProvider(ClaudeSession session, String provider) {
        String model = SessionRuntimeDefaults.resolveProviderModel(
                context.getProject(), provider, context.getSettingsService().getModelRegistry());
        if (model == null || model.isBlank()) {
            return;
        }
        session.setModel(model);
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
                    context.dispatchEvent(DownstreamEvent.SLASH_COMMANDS.value(), json);
                    if (codexJson != null) {
                        context.dispatchEvent(DownstreamEvent.SLASH_DOLLAR_COMMANDS.value(), codexJson);
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

    public static String resolveConfiguredClaudeModel(String baseModel, JsonObject env) {
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

    /**
     * 返回指定 provider 下的模型上下文窗口上限。
     *
     * <p>Codex 等非 Claude provider 支持用户自定义 context window(见
     * {@link com.github.claudecodegui.provider.CustomModelContextWindowProvider}),
     * 优先查自定义配置;未配置或为 Claude provider 时回退到 {@link #getModelContextLimit(String)}。
     * Claude 的运行时上下文行为不受自定义配置影响(provider 内部仅认 codex)。
     */
    public static int getModelContextLimit(String provider, String model) {
        return com.github.claudecodegui.provider.CustomModelContextWindowProvider.getInstance()
                .getContextWindow(provider, model)
                .orElseGet(() -> getModelContextLimit(model));
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
