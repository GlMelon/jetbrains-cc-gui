package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge;
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

    public SessionSendService(
            Project project,
            SessionState state,
            SessionCallbackFacade callbackFacade,
            MessageParser messageParser,
            MessageMerger messageMerger,
            Gson gson,
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            SessionContextService contextService
    ) {
        this(project, state, callbackFacade, messageParser, messageMerger, gson,
                claudeSDKBridge, codexSDKBridge, null, contextService);
    }

    public SessionSendService(
            Project project,
            SessionState state,
            SessionCallbackFacade callbackFacade,
            MessageParser messageParser,
            MessageMerger messageMerger,
            Gson gson,
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            OpenCodeSDKBridge openCodeSDKBridge,
            SessionContextService contextService
    ) {
        this.project = project;
        this.state = state;
        this.callbackFacade = callbackFacade;
        this.messageParser = messageParser;
        this.messageMerger = messageMerger;
        this.gson = gson;
        this.contextService = contextService;
        this.runtimeRouter = new SessionRuntimeRouter(claudeSDKBridge, codexSDKBridge, openCodeSDKBridge);
    }

    public void prepareContextCollector(EditorContextCollector contextCollector) {
        contextCollector.setPsiContextEnabled(state.isPsiContextEnabled());
        contextCollector.setAutoOpenFileEnabled(readAutoOpenFileEnabled());
    }

    public void interruptRuntime(String provider, String channelId, String tabId) {
        RuntimeType runtimeType = RuntimeType.SDK;
        try {
            EffectiveRuntimeResolver.Runtime runtime = EffectiveRuntimeResolver.resolve(
                    provider,
                    state.getClaudeInvocationMode(),
                    null,
                    CodemossSettingsService.getInstance().getRuntimePolicy()
            );
            runtimeType = runtime.runtimeType();
        } catch (Exception e) {
            LOG.warn("[Runtime] Failed to resolve runtime for interrupt, defaulting to SDK: " + e.getMessage());
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

    public static String resolveEffectiveClaudeInvocationMode(String requestedMode) {
        return resolveEffectiveClaudeInvocationMode(requestedMode, null);
    }

    public static String getCodexRuntimeAccessError(String accessMode) {
        if (CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED.equals(accessMode)
                || CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN.equals(accessMode)) {
            return null;
        }
        return ClaudeCodeGuiBundle.message("error.codexLocalAccessNotAuthorized");
    }

    public static String resolveEffectiveClaudeInvocationMode(String requestedMode, String sessionMode) {
        String normalizedSessionMode = SessionState.isValidClaudeInvocationMode(sessionMode) ? sessionMode.trim() : null;

        if (normalizedSessionMode != null) {
            return normalizedSessionMode;
        }

        if (SessionState.isValidClaudeInvocationMode(requestedMode)) {
            return requestedMode.trim();
        }

        try {
            String configured = CodemossSettingsService.getInstance().getClaudeInvocationMode();
            if (SessionState.isValidClaudeInvocationMode(configured)) {
                return configured.trim();
            }
        } catch (Exception e) {
            LOG.warn("[ModeSync][Backend] Failed to read Claude invocation mode, defaulting to sdk: " + e.getMessage());
        }
        return CommonConstants.INVOCATION_MODE_SDK;
    }

    public CompletableFuture<Void> sendMessageToProvider(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String externalAgentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode,
            String requestedInvocationMode
    ) {
        String agentPrompt = externalAgentPrompt;
        if (agentPrompt == null) {
            agentPrompt = getAgentPrompt();
            LOG.info("[Agent] Using agent from global setting (fallback)");
        } else {
            LOG.info("[Agent] Using agent from message (per-tab selection)");
        }

        String currentProvider = state.getProvider();
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

        if (CommonConstants.PROVIDER_CODEX.equals(currentProvider)) {
            // 方向 A:codex 同样透传请求级调用模式(由前端「调用模式」UI 统一驱动 claude+codex)。
            return sendToCodex(
                    channelId,
                    input,
                    attachments,
                    openedFilesJson,
                    agentPrompt,
                    fileTagPaths,
                    effectivePermissionMode,
                    requestedInvocationMode
            );
        }

        if (CommonConstants.PROVIDER_OPENCODE.equals(currentProvider)) {
            // B4:OpenCode 与 Codex 同构(由前端「调用模式」UI 驱动 requestedInvocationMode,无会话级模式),
            // 对称 sendToCodex 路由到 OpenCodeSDKBridge / OpenCode runtime,确保不落入 Claude 默认路径。
            return sendToOpenCode(
                    channelId,
                    input,
                    attachments,
                    openedFilesJson,
                    agentPrompt,
                    fileTagPaths,
                    effectivePermissionMode,
                    requestedInvocationMode
            );
        }

        String effectiveInvocationMode = resolveEffectiveClaudeInvocationMode(requestedInvocationMode, state.getClaudeInvocationMode());
        state.setClaudeInvocationMode(effectiveInvocationMode);

        return sendToClaude(channelId, input, attachments, openedFilesJson, agentPrompt,
                effectivePermissionMode, effectiveInvocationMode);
    }

    private CompletableFuture<Void> sendToCodex(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode,
            String requestedInvocationMode
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

        // codex 无会话级调用模式(sessionMode=null),仅以请求级 requestedInvocationMode 驱动;
        // 为 null(前端调用模式未加载)时由 resolve 回退到「路由策略」面板的 codex default。
        EffectiveRuntimeResolver.Runtime runtime = resolveRuntime(
                CommonConstants.PROVIDER_CODEX,
                null,
                requestedInvocationMode
        );
        warnIfRuntimeDegraded(runtime);
        RuntimeKey key = new RuntimeKey(
                CommonConstants.PROVIDER_CODEX,
                channelId,
                channelId,
                state.getRuntimeSessionEpoch()
        );
        ModelRegistryConfig.ResolvedModelSelection codexModelSelection =
                resolveModelSelection(CommonConstants.PROVIDER_CODEX, state.getModel());
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

    private CompletableFuture<Void> sendToOpenCode(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode,
            String requestedInvocationMode
    ) {
        // B4:复用 CodexMessageHandler 处理统一 MSG_* 协议。OpenCode 事件经 OpenCodeSDKBridge(SDK)
        // / OpenCodeCliSession(CLI)已归一为同一 MSG_* schema(session_id/stream_start/content_delta/
        // thinking_delta/usage/stream_end/error 等),无需新建 OpenCodeMessageHandler(设计 §9 未列新 handler)。
        // 绑定运行时会话 epoch,旧 OpenCode 进程回调因 epoch 不匹配被丢弃(防串台,与 Claude/Codex 一致)。
        CodexMessageHandler handler = new CodexMessageHandler(
                state,
                callbackFacade.getCallbackHandler(),
                state.getRuntimeSessionEpoch()
        );

        // 复用 provider 中性的上下文构造(workspace/module/file);buildCodexContextAppend 实为通用,
        // 并非 Codex 专有——openedFilesJson/fileTagPaths 派生的项目结构上下文对 OpenCode 同样有效。
        String contextAppend = contextService.buildCodexContextAppend(openedFilesJson, fileTagPaths);
        String finalInput = (input != null ? input : "") + contextAppend;

        // OpenCode 无会话级调用模式(sessionMode=null),仅以请求级 requestedInvocationMode 驱动;
        // 为 null(前端调用模式未加载)时由 resolve 回退到「路由策略」面板的 opencode default(默认 SDK)。
        EffectiveRuntimeResolver.Runtime runtime = resolveRuntime(
                CommonConstants.PROVIDER_OPENCODE,
                null,
                requestedInvocationMode
        );
        warnIfRuntimeDegraded(runtime);
        RuntimeKey key = new RuntimeKey(
                CommonConstants.PROVIDER_OPENCODE,
                channelId,
                channelId,
                state.getRuntimeSessionEpoch()
        );
        ModelRegistryConfig.ResolvedModelSelection opencodeModelSelection =
                resolveModelSelection(CommonConstants.PROVIDER_OPENCODE, state.getModel());
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
                opencodeModelSelection.actualModel(),
                state.getReasoningEffort(),
                state.getPermissionSessionId(),
                null,
                Map.of()
        );

        return runtimeRouter.send(
                request,
                MessageNormalizers.forRuntime(
                        CommonConstants.PROVIDER_OPENCODE,
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
            String effectivePermissionMode,
            String effectiveInvocationMode
    ) {
        LOG.debug("[SessionSendService][DIAG] sendToClaude called, attachments="
                + (attachments == null ? "NULL" : attachments.size()));
        LOG.debug(String.format(
                "[ClaudeImageDiag][SessionSendService] sendToClaude route: invocationMode=%s, provider=%s, sessionId=%s, attachments=%s",
                effectiveInvocationMode, state.getProvider(),
                state.getSessionId() != null ? state.getSessionId() : "(new)",
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
                state.getRuntimeSessionEpoch()
        );

        Boolean streaming = readStreamingEnabled();
        final String runtimeSessionEpoch = state.getRuntimeSessionEpoch();
        final String currentModel = state.getModel();
        final ModelRegistryConfig.ResolvedModelSelection modelSelection =
                resolveModelSelection(CommonConstants.PROVIDER_CLAUDE, currentModel);
        LOG.info("[Lifecycle] sendToClaude sessionId=" + (state.getSessionId() != null ? state.getSessionId() : "(new)")
                + ", epoch=" + runtimeSessionEpoch
                + ", cwd=" + state.getCwd()
                + ", model=" + currentModel
                + ", actualModel=" + (modelSelection.actualModel() != null ? modelSelection.actualModel() : "(registry-fallback)"));

        EffectiveRuntimeResolver.Runtime runtime = resolveRuntime(
                CommonConstants.PROVIDER_CLAUDE,
                state.getClaudeInvocationMode(),
                effectiveInvocationMode
        );
        warnIfRuntimeDegraded(runtime);
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
                Map.of()
        );

        return runtimeRouter.send(
                request,
                MessageNormalizers.forRuntime(CommonConstants.PROVIDER_CLAUDE, toInvocationMode(runtime.runtimeType()), handler)
        ).thenApply(result -> null);
    }

    private EffectiveRuntimeResolver.Runtime resolveRuntime(String provider, String sessionMode, String requestedMode) {
        return EffectiveRuntimeResolver.resolve(
                provider,
                sessionMode,
                requestedMode,
                CodemossSettingsService.getInstance().getRuntimePolicy()
        );
    }

    /**
     * 冲突点 B:当用户请求的调用模式(「设置-环境-调用模式」)被「路由策略」的 supported 列表拒绝、
     * 静默降级到 default 时,经 notifyStatusMessage → SessionCallbackAdapter.onStatusMessage →
     * 前端 updateStatus → toast 提示用户,打破此前调用模式与路由策略冲突时的零反馈。
     * 降级判定 + 文案由 {@link EffectiveRuntimeResolver#degradedNotice} 生成(已单测覆盖);
     * 此处仅"有文案则推送"薄胶水(项目无 Mockito,SessionSendService 重依赖未独立单测)。
     */
    private void warnIfRuntimeDegraded(EffectiveRuntimeResolver.Runtime runtime) {
        String notice = EffectiveRuntimeResolver.degradedNotice(runtime);
        if (notice != null) {
            callbackFacade.notifyStatusMessage(notice);
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
        return runtimeType == RuntimeType.CLI
                ? CommonConstants.INVOCATION_MODE_CLI
                : CommonConstants.INVOCATION_MODE_SDK;
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
