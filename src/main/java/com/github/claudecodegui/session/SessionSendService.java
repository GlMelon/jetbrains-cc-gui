package com.github.claudecodegui.session;

import com.github.claudecodegui.cli.CliSessionTitleService;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.session.normalize.MessageNormalizers;
import com.github.claudecodegui.session.runtime.*;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Owns message-send orchestration while ClaudeSession remains the public session facade.
 */
public class SessionSendService {
    public static final String CODEX_FAST_SERVICE_TIER = "fast";

    private static final Logger LOG = Logger.getInstance(SessionSendService.class);

    private final Project project;
    private final SessionState state;
    private final SessionCallbackFacade callbackFacade;
    private final MessageParser messageParser;
    private final MessageMerger messageMerger;
    private final Gson gson;
    private final SessionContextService contextService;
    private final SessionRuntimeRouter runtimeRouter;
    private final CliSessionTitleService cliTitleService;
    private volatile long responseStatusTurnStartedAtMillis = 0L;

    public SessionSendService(
            Project project,
            SessionState state,
            SessionCallbackFacade callbackFacade,
            MessageParser messageParser,
            MessageMerger messageMerger,
            Gson gson,
            SessionContextService contextService
    ) {
        this.project = project;
        this.state = state;
        this.callbackFacade = callbackFacade;
        this.messageParser = messageParser;
        this.messageMerger = messageMerger;
        this.gson = gson;
        this.contextService = contextService;
        this.runtimeRouter = new SessionRuntimeRouter(project);
        this.cliTitleService = new CliSessionTitleService();
    }

    public void prepareContextCollector(EditorContextCollector contextCollector) {
        contextCollector.setPsiContextEnabled(state.isPsiContextEnabled());
        contextCollector.setAutoOpenFileEnabled(readAutoOpenFileEnabled());
    }

    public void interruptRuntime(String provider, String channelId, String tabId) {
        RuntimeType runtimeType = RuntimeType.CLI;
        try {
            EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                    provider,
                    CodemossSettingsService.getInstance().getRuntimePolicy()
            );
            runtimeType = runtime.runtimeType();
        } catch (Exception e) {
            LOG.warn("[Runtime] Failed to resolve runtime for interrupt, defaulting to CLI: " + e.getMessage());
        }
        runtimeRouter.interrupt(ProviderType.fromString(provider), runtimeType, tabId != null ? tabId : channelId);
    }

    public void cleanupRuntimeTab(String tabId) {
        runtimeRouter.disposeTab(tabId);
    }

    public void updateSessionStateForSend(ClaudeSession.Message userMessage, String normalizedInput) {
        state.addMessage(userMessage);
        callbackFacade.notifyMessageUpdate(state.getMessages());

        if (state.getSummary() == null) {
            String baseSummary = (userMessage.content != null && !userMessage.content.isEmpty())
                    ? userMessage.content
                    : normalizedInput;
            String newSummary = baseSummary.length() > 45 ? baseSummary.substring(0, 45) + "..." : baseSummary;
            state.setSummary(newSummary);
            callbackFacade.notifySummaryReceived(newSummary);
        }

        state.updateLastModifiedTime();
        state.setError(null);
        state.setBusy(true);
        state.setLoading(true);
        state.setQueueDisplayState(ClaudeSession.SessionCallback.QueueDisplayState.PROCESSING);
        state.setQueueAheadCount(0);
        ClaudeNotifier.setWaiting(project);
        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
        callbackFacade.notifyQueueDisplayStateChanged(state.getQueueDisplayState(), state.getQueueAheadCount());
        responseStatusTurnStartedAtMillis = System.currentTimeMillis();
        callbackFacade.notifyResponsePhase(AssistantResponseStatusPayload.forProvider(
                AssistantResponsePhase.QUEUED,
                state.getProvider(),
                responseStatusTurnStartedAtMillis
        ));
    }

    public static String resolveEffectivePermissionMode(String provider, String requestedMode, String sessionMode) {
        String resolvedMode = normalizeRequestedPermissionMode(sessionMode);
        if (resolvedMode == null) {
            resolvedMode = requestedMode;
        }
        if (resolvedMode == null) {
            resolvedMode = CommonConstants.PERMISSION_MODE_DEFAULT;
        }

        return resolvedMode;
    }

    public static String normalizeRequestedPermissionMode(String mode) {
        if (mode == null) {
            return null;
        }
        String trimmed = mode.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (SessionState.isValidPermissionMode(trimmed)) {
            return trimmed;
        }
        LOG.warn("[ModeSync][Backend] Invalid requested permissionMode ignored: " + mode);
        return null;
    }

    public static String getCodexRuntimeAccessError(String accessMode) {
        if (CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED.equals(accessMode)
                || CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN.equals(accessMode)) {
            return null;
        }
        return ClaudeCodeGuiBundle.message("error.codexLocalAccessNotAuthorized");
    }

    public CompletableFuture<Void> sendMessageToProvider(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String externalAgentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode
    ) {
        String agentPrompt = externalAgentPrompt;
        if (agentPrompt == null) {
            agentPrompt = getAgentPrompt();
            LOG.info("[Agent] Using agent from global setting (fallback)");
        } else {
            LOG.info("[Agent] Using agent from message (per-tab selection)");
        }

        String currentProvider = state.getProvider();
        if (responseStatusTurnStartedAtMillis <= 0L) {
            responseStatusTurnStartedAtMillis = System.currentTimeMillis();
        }
        callbackFacade.notifyResponsePhase(AssistantResponseStatusPayload.forProvider(
                AssistantResponsePhase.CONNECTING,
                currentProvider,
                responseStatusTurnStartedAtMillis
        ));
        String sessionModeBeforeSend = state.getPermissionMode();
        String normalizedRequestedMode = normalizeRequestedPermissionMode(requestedPermissionMode);
        String effectivePermissionMode = resolveEffectivePermissionMode(
                currentProvider,
                normalizedRequestedMode,
                sessionModeBeforeSend
        );

        LOG.info(
                "[ModeSync][Backend] provider=" + currentProvider
                        + ", requested=" + (normalizedRequestedMode != null ? normalizedRequestedMode : "(none)")
                        + ", session=" + (sessionModeBeforeSend != null ? sessionModeBeforeSend : "(none)")
                        + ", effective=" + effectivePermissionMode
        );

        // CLI 标题生成所需的首轮判据:send 前 sessionId 为空即新会话首轮。
        // 首轮 CLI 的 sessionId 是流解析后才确定的,故 post-success 时从 state 重新读取权威值。
        final String sessionIdBeforeSend = state.getSessionId();
        final boolean isCliRuntime = isCliRuntime(currentProvider);

        CompletableFuture<Void> future;
        if (CommonConstants.PROVIDER_CLAUDE.equals(currentProvider)) {
            // Claude 走专属 ClaudeMessageHandler(streaming-json 协议 + 附件/图片富处理)。
            future = sendToClaude(channelId, input, attachments, openedFilesJson, agentPrompt,
                    effectivePermissionMode);
        } else if (CommonConstants.PROVIDER_CODEX.equals(currentProvider)) {
            future = sendToCodex(
                    channelId,
                    input,
                    attachments,
                    openedFilesJson,
                    agentPrompt,
                    fileTagPaths,
                    effectivePermissionMode
            );
        } else {
            // OpenCode / Grok / Kimi / Pi 均经各自 CliSession 把上游 CLI 输出归一为统一 MSG_* 协议,
            // 共用 CodexMessageHandler + buildCodexContextAppend + passthrough normalizer。
            // 修复:此前 else 兜底走 sendToClaude 且硬编码 PROVIDER_CLAUDE,致 Grok/Kimi/Pi
            // 静默按 Claude 解析(模型/runtime/normalizer 全错位)——发出去的是 grok run,
            // 回流却按 Claude streaming-json 归一,且 modelSelection/runtimeKey 全挂 CLAUDE。
            future = sendToCodexProtocolProvider(
                    currentProvider,
                    channelId,
                    input,
                    attachments,
                    openedFilesJson,
                    agentPrompt,
                    fileTagPaths,
                    effectivePermissionMode
            );
        }

        // provider 无关的统一 post-turn 钩子:仅在 CLI 首轮成功后 fire-and-forget 触发标题生成。
        // 三 provider(Claude/Codex/OpenCode)共用同一 CliSessionTitleService,内部判 CLI+首轮+配置。
        return future.whenComplete((result, ex) -> {
            if (ex != null) {
                return;
            }
            cliTitleService.maybeGenerateTitle(
                    isCliRuntime,
                    sessionIdBeforeSend == null,
                    input,
                    state.getSessionId(),
                    state.getCwd(),
                    callbackFacade);
        });
    }

    private CompletableFuture<Void> sendToCodex(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode
    ) {
        // 绑定当前运行时会话 epoch:运行时切换后(见 ModelProviderHandler 旋转 epoch),
        // 旧 Codex 进程的回调会因 epoch 不匹配被 CodexMessageHandler 丢弃(防串台,与 Claude 侧一致)。
        CodexMessageHandler handler = new CodexMessageHandler(state, callbackFacade.getCallbackHandler(), state.getRuntimeSessionEpoch());
        String accessMode = CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE;
        try {
            accessMode = CodemossSettingsService.getInstance().getCodexRuntimeAccessMode();
        } catch (Exception e) {
            LOG.warn("[Codex] Failed to resolve runtime access mode: " + e.getMessage());
        }

        String accessError = getCodexRuntimeAccessError(accessMode);
        if (accessError != null) {
            handler.onError(accessError);
            return CompletableFuture.completedFuture(null);
        }

        String contextAppend = contextService.buildCodexContextAppend(openedFilesJson, fileTagPaths);
        String finalInput = (input != null ? input : "") + contextAppend;

        EffectiveRuntimeResolver.Runtime runtime = resolveRuntime(CommonConstants.PROVIDER_CODEX);
        RuntimeKey key = new RuntimeKey(
                CommonConstants.PROVIDER_CODEX,
                channelId,
                channelId,
                state.getRuntimeSessionEpoch()
        );
        ModelRegistryConfig.ResolvedModelSelection codexModelSelection =
                resolveModelSelection(CommonConstants.PROVIDER_CODEX, state.getModel());
        Boolean thinkingOutputEnabled = readThinkingOutputEnabled();
        SessionRequest request = new SessionRequest(
                key,
                runtime.provider(),
                runtime.runtimeType(),
                finalInput,
                state.getSessionId(),
                state.getCwd(),
                attachments,
                openedFilesJson,
                fileTagPaths,
                agentPrompt,
                effectivePermissionMode,
                state.getModel(),
                codexModelSelection.actualModel(),
                state.getReasoningEffort(),
                state.getPermissionSessionId(),
                null,
                null,
                thinkingOutputEnabled,
                Map.of()
        );

        return runtimeRouter.send(
                request,
                MessageNormalizers.forRuntime(
                        CommonConstants.PROVIDER_CODEX,
                        toInvocationMode(runtime.runtimeType()),
                        handler
                )
        ).thenApply(result -> null);
    }

    /**
     * Codex 协议族通用发送路径:OpenCode / Grok / Kimi / Pi 均经各自 CliSession
     * (OpenCodeCliSession / GrokCliSession / KimiCliSession / PiCliSession)把上游 CLI 输出
     * 归一为同一 MSG_* schema(session_id/stream_start/content_delta/thinking_delta/usage/
     * stream_end/error 等),故共用 CodexMessageHandler + buildCodexContextAppend + passthrough
     * normalizer(MessageNormalizers 已为四家注册透传归一化器)。无需各建独立 handler。
     * 绑定运行时会话 epoch,旧进程回调因 epoch 不匹配被丢弃(防串台,与 Claude/Codex 一致)。
     */
    private CompletableFuture<Void> sendToCodexProtocolProvider(
            String provider,
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode
    ) {
        CodexMessageHandler handler = new CodexMessageHandler(
                state,
                callbackFacade.getCallbackHandler(),
                state.getRuntimeSessionEpoch()
        );

        // 复用 provider 中性的上下文构造(workspace/module/file);buildCodexContextAppend 实为通用,
        // 并非 Codex 专有——openedFilesJson/fileTagPaths 派生的项目结构上下文对 Codex 协议族同样有效。
        String contextAppend = contextService.buildCodexContextAppend(openedFilesJson, fileTagPaths);
        String finalInput = (input != null ? input : "") + contextAppend;

        EffectiveRuntimeResolver.Runtime runtime = resolveRuntime(provider);
        RuntimeKey key = new RuntimeKey(
                provider,
                channelId,
                channelId,
                state.getRuntimeSessionEpoch()
        );
        ModelRegistryConfig.ResolvedModelSelection modelSelection =
                resolveModelSelection(provider, state.getModel());
        Boolean thinkingOutputEnabled = readThinkingOutputEnabled();
        SessionRequest request = new SessionRequest(
                key,
                runtime.provider(),
                runtime.runtimeType(),
                finalInput,
                state.getSessionId(),
                state.getCwd(),
                attachments,
                openedFilesJson,
                fileTagPaths,
                agentPrompt,
                effectivePermissionMode,
                state.getModel(),
                modelSelection.actualModel(),
                state.getReasoningEffort(),
                state.getPermissionSessionId(),
                null,
                null,
                thinkingOutputEnabled,
                Map.of()
        );

        return runtimeRouter.send(
                request,
                MessageNormalizers.forRuntime(
                        provider,
                        toInvocationMode(runtime.runtimeType()),
                        handler
                )
        ).thenApply(result -> null);
    }

    private CompletableFuture<Void> sendToClaude(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            String effectivePermissionMode
    ) {
        LOG.debug("[SessionSendService][DIAG] sendToClaude called, attachments="
                + (attachments == null ? "NULL" : attachments.size()));
        LOG.debug(String.format(
                "[ClaudeImageDiag][SessionSendService] sendToClaude route: provider=%s, sessionId=%s, attachments=%s",
                state.getProvider(), state.getSessionId() != null ? state.getSessionId() : "(new)",
                attachments == null ? "NULL" : attachments.size()));
        if (attachments != null) {
            for (int i = 0; i < attachments.size(); i++) {
                ClaudeSession.Attachment att = attachments.get(i);
                LOG.debug("[SessionSendService][DIAG] att[" + i + "]: fileName=" + att.fileName
                        + ", localPath=" + att.localPath
                        + ", data=" + (att.data != null ? att.data.length() + "chars" : "null"));
                LOG.debug(String.format(
                        "[ClaudeImageDiag][SessionSendService] att[%d]: fileName=%s, mediaType=%s, localPath=%s, resourceUrl=%s, data=%s",
                        i, att.fileName, att.mediaType, att.localPath, att.resourceUrl,
                        att.data != null ? att.data.length() + "chars" : "null"));
            }
        }
        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                project,
                state,
                callbackFacade.getCallbackHandler(),
                messageParser,
                messageMerger,
                gson,
                state.getRuntimeSessionEpoch(),
                CodemossSettingsService.getInstance()
        );

        Boolean streaming = readStreamingEnabled();
        final String runtimeSessionEpoch = state.getRuntimeSessionEpoch();
        final String currentModel = state.getModel();
        final ModelRegistryConfig.ResolvedModelSelection modelSelection =
                resolveModelSelection(CommonConstants.PROVIDER_CLAUDE, currentModel);
        final Boolean thinkingOutputEnabled = readThinkingOutputEnabled();
        LOG.info("[Lifecycle] sendToClaude sessionId=" + (state.getSessionId() != null ? state.getSessionId() : "(new)")
                + ", epoch=" + runtimeSessionEpoch
                + ", cwd=" + state.getCwd()
                + ", model=" + currentModel
                + ", actualModel=" + (modelSelection.actualModel() != null ? modelSelection.actualModel() : "(registry-fallback)"));

        EffectiveRuntimeResolver.Runtime runtime = resolveRuntime(CommonConstants.PROVIDER_CLAUDE);
        SessionRequest request = new SessionRequest(
                new RuntimeKey(CommonConstants.PROVIDER_CLAUDE, channelId, channelId, runtimeSessionEpoch),
                runtime.provider(),
                runtime.runtimeType(),
                input,
                state.getSessionId(),
                state.getCwd(),
                attachments,
                openedFilesJson,
                List.of(),
                agentPrompt,
                effectivePermissionMode,
                currentModel,
                modelSelection.actualModel(),
                state.getReasoningEffort(),
                state.getPermissionSessionId(),
                streaming,
                null, // disableThinking 废弃:思考预算改由 reasoning effort 控制,三 provider 统一 null(思考区开关下沉为显示控制,见 SessionCallbackAdapter/TurnPushGate)
                thinkingOutputEnabled,
                Map.of()
        );

        return runtimeRouter.send(
                request,
                MessageNormalizers.forRuntime(CommonConstants.PROVIDER_CLAUDE, toInvocationMode(runtime.runtimeType()), handler)
        ).thenApply(result -> null);
    }

    private EffectiveRuntimeResolver.Runtime resolveRuntime(String provider) {
        return EffectiveRuntimeResolver.resolve(
                provider,
                CodemossSettingsService.getInstance().getRuntimePolicy()
        );
    }

    /**
     * 判断给定 provider 当前解析到的运行时是否为 CLI。供 CLI 标题生成钩子使用:
     * 解析失败时保守返回 false(不触发标题,与 SDK 行为一致)。
     */
    private boolean isCliRuntime(String provider) {
        try {
            return resolveRuntime(provider).runtimeType() == RuntimeType.CLI;
        } catch (Exception e) {
            LOG.warn("[CliTitle] Failed to resolve runtime for title trigger, skipping: " + e.getMessage());
            return false;
        }
    }

    private ModelRegistryConfig.ResolvedModelSelection resolveModelSelection(String provider, String selectedModel) {
        try {
            return CodemossSettingsService.getInstance()
                    .getModelRegistry()
                    .resolveModelSelection(provider, selectedModel);
        } catch (Exception e) {
            LOG.warn("[ModelRegistry] Failed to resolve selected model, falling back to request model: "
                    + e.getMessage());
            return ModelRegistryConfig.getDefault().resolveModelSelection(provider, selectedModel);
        }
    }

    private static String toInvocationMode(RuntimeType runtimeType) {
        // SDK 调用模式已移除,恒返回 CLI。
        return CommonConstants.INVOCATION_MODE_CLI;
    }

    private boolean readAutoOpenFileEnabled() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = CodemossSettingsService.getInstance();
                boolean autoOpenFileEnabled = settingsService.getAutoOpenFileEnabled(projectPath);
                LOG.info("[EditorContext] Auto open file enabled: " + autoOpenFileEnabled);
                return autoOpenFileEnabled;
            }
        } catch (Exception e) {
            LOG.warn("[EditorContext] Failed to read autoOpenFileEnabled setting: " + e.getMessage());
        }
        return false;
    }

    private Boolean readStreamingEnabled() {
        Boolean streaming = null;
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = CodemossSettingsService.getInstance();
                streaming = settingsService.getStreamingEnabled(projectPath);
                LOG.info("[Streaming] Read streaming config: " + streaming);
            }
        } catch (Exception e) {
            LOG.warn("[Streaming] Failed to read streaming config: " + e.getMessage());
        }
        return streaming;
    }

    private Boolean readThinkingOutputEnabled() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = CodemossSettingsService.getInstance();
                boolean enabled = settingsService.getShowThinkingEnabled(projectPath);
                LOG.info("[Thinking] Read show thinking config: " + enabled);
                return enabled;
            }
        } catch (Exception e) {
            LOG.warn("[Thinking] Failed to read show thinking config, defaulting to enabled: " + e.getMessage());
        }
        return Boolean.TRUE;
    }

    private String getAgentPrompt() {
        try {
            CodemossSettingsService settingsService = CodemossSettingsService.getInstance();
            String selectedAgentId = settingsService.getSelectedAgentId();
            LOG.info("[Agent] Checking selected agent ID: " + (selectedAgentId != null ? selectedAgentId : "null"));

            if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                JsonObject agent = settingsService.getAgent(selectedAgentId);
                if (agent != null && agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    String agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[Agent] ✓ Found agent: " + agentName);
                    LOG.debug("[Agent] ✓ Prompt length: " + agentPrompt.length() + " chars");
                    LOG.debug("[Agent] ✓ Prompt preview: "
                            + (agentPrompt.length() > 100 ? agentPrompt.substring(0, 100) + "..." : agentPrompt));
                    return agentPrompt;
                }
                LOG.info("[Agent] ✗ Agent found but no prompt configured");
            } else {
                LOG.info("[Agent] ✗ No agent selected");
            }
        } catch (Exception e) {
            LOG.warn("[Agent] ✗ Failed to get agent prompt: " + e.getMessage());
        }
        return null;
    }

    public static String normalizeRequestedCodexServiceTier(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if ("fast".equalsIgnoreCase(trimmed) || "priority".equalsIgnoreCase(trimmed)) {
            return CODEX_FAST_SERVICE_TIER;
        }
        if ("normal".equalsIgnoreCase(trimmed)
                || "standard".equalsIgnoreCase(trimmed)
                || "default".equalsIgnoreCase(trimmed)
                || "none".equalsIgnoreCase(trimmed)) {
            return null;
        }
        LOG.warn("[Codex] Invalid fast mode/service tier ignored: " + value);
        return null;
    }

    public static String resolveEffectiveCodexServiceTier(String requestedValue, String sessionValue) {
        String requested = normalizeRequestedCodexServiceTier(requestedValue);
        if (requested != null) {
            return requested;
        }
        if (isExplicitCodexStandardMode(requestedValue)) {
            return null;
        }

        String session = normalizeRequestedCodexServiceTier(sessionValue);
        return session;
    }

    public static boolean isExplicitCodexStandardMode(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "normal".equalsIgnoreCase(trimmed)
                || "standard".equalsIgnoreCase(trimmed)
                || "default".equalsIgnoreCase(trimmed)
                || "none".equalsIgnoreCase(trimmed);
    }
}
