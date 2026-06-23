package com.github.claudecodegui.ui;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.handler.agent.AgentActionHandlers;
import com.github.claudecodegui.handler.agent.GetAgentsActionHandler;
import com.github.claudecodegui.handler.agent.AddAgentActionHandler;
import com.github.claudecodegui.handler.agent.UpdateAgentActionHandler;
import com.github.claudecodegui.handler.agent.DeleteAgentActionHandler;
import com.github.claudecodegui.handler.agent.GetSelectedAgentActionHandler;
import com.github.claudecodegui.handler.agent.SetSelectedAgentActionHandler;
import com.github.claudecodegui.handler.agent.ExportAgentsActionHandler;
import com.github.claudecodegui.handler.agent.ImportAgentsFileActionHandler;
import com.github.claudecodegui.handler.agent.SaveImportedAgentsActionHandler;
import com.github.claudecodegui.handler.codex.CodexMcpServerActionHandlers;
import com.github.claudecodegui.handler.codex.GetCodexMcpServersActionHandler;
import com.github.claudecodegui.handler.codex.GetCodexMcpServerStatusActionHandler;
import com.github.claudecodegui.handler.codex.GetCodexMcpServerToolsActionHandler;
import com.github.claudecodegui.handler.codex.AddCodexMcpServerActionHandler;
import com.github.claudecodegui.handler.codex.UpdateCodexMcpServerActionHandler;
import com.github.claudecodegui.handler.codex.DeleteCodexMcpServerActionHandler;
import com.github.claudecodegui.handler.codex.ToggleCodexMcpServerActionHandler;
import com.github.claudecodegui.handler.codex.ValidateCodexMcpServerActionHandler;
import com.github.claudecodegui.handler.context.GetContextUsageActionHandler;
import com.github.claudecodegui.handler.dependency.DependencyActionHandlers;
import com.github.claudecodegui.handler.dependency.GetDependencyStatusActionHandler;
import com.github.claudecodegui.handler.dependency.InstallDependencyActionHandler;
import com.github.claudecodegui.handler.dependency.UninstallDependencyActionHandler;
import com.github.claudecodegui.handler.dependency.UpdateDependencyActionHandler;
import com.github.claudecodegui.handler.dependency.CheckDependencyUpdatesActionHandler;
import com.github.claudecodegui.handler.dependency.GetDependencyVersionsActionHandler;
import com.github.claudecodegui.handler.dependency.CheckNodeEnvironmentActionHandler;
import com.github.claudecodegui.handler.enhance.EnhancePromptActionHandler;
import com.github.claudecodegui.handler.file.SaveMarkdownActionHandler;
import com.github.claudecodegui.handler.file.SaveJsonActionHandler;
import com.github.claudecodegui.handler.file.UndoFileChangesActionHandler;
import com.github.claudecodegui.handler.file.UndoAllFileChangesActionHandler;
import com.github.claudecodegui.handler.diff.DiffActionHandlers;
import com.github.claudecodegui.handler.diff.RefreshFileActionHandler;
import com.github.claudecodegui.handler.diff.ShowDiffActionHandler;
import com.github.claudecodegui.handler.diff.ShowMultiEditDiffActionHandler;
import com.github.claudecodegui.handler.diff.ShowEditPreviewDiffActionHandler;
import com.github.claudecodegui.handler.diff.ShowEditFullDiffActionHandler;
import com.github.claudecodegui.handler.diff.ShowEditableDiffActionHandler;
import com.github.claudecodegui.handler.diff.ShowInteractiveDiffActionHandler;
import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.core.LegacyMessageHandlerAdapter;
import com.github.claudecodegui.handler.history.HistoryActionHandlers;
import com.github.claudecodegui.handler.history.LoadHistoryDataActionHandler;
import com.github.claudecodegui.handler.history.LoadSessionActionHandler;
import com.github.claudecodegui.handler.history.DeleteSessionActionHandler;
import com.github.claudecodegui.handler.history.DeleteSessionsActionHandler;
import com.github.claudecodegui.handler.history.ExportSessionActionHandler;
import com.github.claudecodegui.handler.history.ToggleFavoriteActionHandler;
import com.github.claudecodegui.handler.history.UpdateTitleActionHandler;
import com.github.claudecodegui.handler.history.DeleteTitleActionHandler;
import com.github.claudecodegui.handler.history.DeepSearchHistoryActionHandler;
import com.github.claudecodegui.handler.history.LoadSubagentSessionActionHandler;
import com.github.claudecodegui.handler.history.ConvertToCliSessionActionHandler;
import com.github.claudecodegui.handler.PermissionModeHandler;
import com.github.claudecodegui.handler.InputHistoryHandler;
import com.github.claudecodegui.handler.UsagePushService;
import com.github.claudecodegui.handler.provider.ModelProviderHandler;
import com.github.claudecodegui.handler.core.MessageDispatcher;
import com.github.claudecodegui.handler.nodeprocess.NodeProcessActionHandlers;
import com.github.claudecodegui.handler.nodeprocess.GetNodeProcessesActionHandler;
import com.github.claudecodegui.handler.nodeprocess.KillNodeProcessActionHandler;
import com.github.claudecodegui.handler.nodeprocess.KillAllOrphansActionHandler;
import com.github.claudecodegui.handler.nodeprocess.RestartNodeDaemonActionHandler;
import com.github.claudecodegui.handler.permission.PermissionActionHandlers;
import com.github.claudecodegui.handler.permission.PermissionDecisionActionHandler;
import com.github.claudecodegui.handler.permission.AskUserQuestionResponseActionHandler;
import com.github.claudecodegui.handler.permission.PlanApprovalResponseActionHandler;
import com.github.claudecodegui.handler.prompt.PromptActionHandlers;
import com.github.claudecodegui.handler.prompt.GetPromptsActionHandler;
import com.github.claudecodegui.handler.prompt.GetProjectInfoActionHandler;
import com.github.claudecodegui.handler.prompt.AddPromptActionHandler;
import com.github.claudecodegui.handler.prompt.UpdatePromptActionHandler;
import com.github.claudecodegui.handler.prompt.DeletePromptActionHandler;
import com.github.claudecodegui.handler.prompt.ExportPromptsActionHandler;
import com.github.claudecodegui.handler.prompt.ImportPromptsFileActionHandler;
import com.github.claudecodegui.handler.prompt.SaveImportedPromptsActionHandler;
import com.github.claudecodegui.handler.provider.ProviderActionHandlers;
import com.github.claudecodegui.handler.provider.GetProvidersActionHandler;
import com.github.claudecodegui.handler.provider.GetCurrentClaudeConfigActionHandler;
import com.github.claudecodegui.handler.provider.GetThinkingEnabledActionHandler;
import com.github.claudecodegui.handler.provider.SetThinkingEnabledActionHandler;
import com.github.claudecodegui.handler.provider.AddProviderActionHandler;
import com.github.claudecodegui.handler.provider.UpdateProviderActionHandler;
import com.github.claudecodegui.handler.provider.DeleteProviderActionHandler;
import com.github.claudecodegui.handler.provider.SwitchProviderActionHandler;
import com.github.claudecodegui.handler.provider.GetActiveProviderActionHandler;
import com.github.claudecodegui.handler.provider.PreviewCcSwitchImportActionHandler;
import com.github.claudecodegui.handler.provider.OpenFileChooserForCcSwitchActionHandler;
import com.github.claudecodegui.handler.provider.SaveImportedProvidersActionHandler;
import com.github.claudecodegui.handler.provider.SortProvidersActionHandler;
import com.github.claudecodegui.handler.provider.GetCodexProvidersActionHandler;
import com.github.claudecodegui.handler.provider.GetCurrentCodexConfigActionHandler;
import com.github.claudecodegui.handler.provider.AddCodexProviderActionHandler;
import com.github.claudecodegui.handler.provider.UpdateCodexProviderActionHandler;
import com.github.claudecodegui.handler.provider.DeleteCodexProviderActionHandler;
import com.github.claudecodegui.handler.provider.SwitchCodexProviderActionHandler;
import com.github.claudecodegui.handler.provider.RevokeCodexLocalConfigAuthorizationActionHandler;
import com.github.claudecodegui.handler.provider.GetActiveCodexProviderActionHandler;
import com.github.claudecodegui.handler.provider.SortCodexProvidersActionHandler;
import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.handler.settings.GetClaudeCliPathActionHandler;
import com.github.claudecodegui.handler.settings.GetCodexSubscriptionQuotaActionHandler;
import com.github.claudecodegui.handler.settings.GetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.SetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.ResetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.GetModelRegistrySchemaActionHandler;
import com.github.claudecodegui.handler.settings.SetAppearanceConfigActionHandler;
import com.github.claudecodegui.handler.settings.GetModeActionHandler;
import com.github.claudecodegui.handler.settings.SetModeActionHandler;
import com.github.claudecodegui.handler.settings.SetSessionModeActionHandler;
import com.github.claudecodegui.handler.settings.GetInputHistoryActionHandler;
import com.github.claudecodegui.handler.settings.RecordInputHistoryActionHandler;
import com.github.claudecodegui.handler.settings.DeleteInputHistoryItemActionHandler;
import com.github.claudecodegui.handler.settings.ClearInputHistoryActionHandler;
import com.github.claudecodegui.handler.settings.SetModelActionHandler;
import com.github.claudecodegui.handler.settings.SetSessionModelActionHandler;
import com.github.claudecodegui.handler.settings.SetProviderActionHandler;
import com.github.claudecodegui.handler.settings.SetSessionProviderActionHandler;
import com.github.claudecodegui.handler.settings.SetReasoningEffortActionHandler;
import com.github.claudecodegui.handler.settings.SetCodexFastModeActionHandler;
import com.github.claudecodegui.handler.settings.SetClaudeCliPathActionHandler;
import com.github.claudecodegui.handler.settings.GetNodePathActionHandler;
import com.github.claudecodegui.handler.settings.SetNodePathActionHandler;
import com.github.claudecodegui.handler.clipboard.ReadClipboardActionHandler;
import com.github.claudecodegui.handler.clipboard.WriteClipboardActionHandler;
import com.github.claudecodegui.handler.tab.CreateNewTabActionHandler;
import com.github.claudecodegui.handler.rewind.RewindFilesActionHandler;
import com.github.claudecodegui.handler.session.SessionActionHandlers;
import com.github.claudecodegui.handler.session.SendMessageActionHandler;
import com.github.claudecodegui.handler.session.SendMessageWithAttachmentsActionHandler;
import com.github.claudecodegui.handler.session.InterruptSessionActionHandler;
import com.github.claudecodegui.handler.session.RestartSessionActionHandler;
import com.github.claudecodegui.handler.window.WindowActionHandlers;
import com.github.claudecodegui.handler.window.HeartbeatActionHandler;
import com.github.claudecodegui.handler.window.TabLoadingChangedActionHandler;
import com.github.claudecodegui.handler.window.TabStatusChangedActionHandler;
import com.github.claudecodegui.handler.window.CreateNewSessionActionHandler;
import com.github.claudecodegui.handler.window.FrontendReadyActionHandler;
import com.github.claudecodegui.handler.window.RefreshSlashCommandsActionHandler;
import com.github.claudecodegui.handler.mcp.McpServerActionHandlers;
import com.github.claudecodegui.handler.mcp.GetMcpServersActionHandler;
import com.github.claudecodegui.handler.mcp.GetMcpServerStatusActionHandler;
import com.github.claudecodegui.handler.mcp.GetMcpServerToolsActionHandler;
import com.github.claudecodegui.handler.mcp.AddMcpServerActionHandler;
import com.github.claudecodegui.handler.mcp.UpdateMcpServerActionHandler;
import com.github.claudecodegui.handler.mcp.DeleteMcpServerActionHandler;
import com.github.claudecodegui.handler.mcp.ToggleMcpServerActionHandler;
import com.github.claudecodegui.handler.mcp.ValidateMcpServerActionHandler;
import com.github.claudecodegui.handler.skill.SkillActionHandlers;
import com.github.claudecodegui.handler.skill.GetAllSkillsActionHandler;
import com.github.claudecodegui.handler.skill.ImportSkillActionHandler;
import com.github.claudecodegui.handler.skill.DeleteSkillActionHandler;
import com.github.claudecodegui.handler.skill.OpenSkillActionHandler;
import com.github.claudecodegui.handler.skill.ToggleSkillActionHandler;
import com.github.claudecodegui.handler.file.FileActionHandlers;
import com.github.claudecodegui.handler.file.ListFilesActionHandler;
import com.github.claudecodegui.handler.file.OpenFileActionHandler;
import com.github.claudecodegui.handler.file.OpenBrowserActionHandler;
import com.github.claudecodegui.handler.file.OpenClassActionHandler;
import com.github.claudecodegui.handler.file.GetLinkifyCapabilitiesActionHandler;
import com.github.claudecodegui.handler.file.ResolveFilePathActionHandler;
import com.github.claudecodegui.handler.file.OpenClassHandler;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.MessageJsonConverter;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Delegates for initialization setup and runtime operations:
 * handler registration, permission setup, tab status, QuickFix, and frontend ready handling.
 */
public class ChatWindowDelegate {

    private static final Logger LOG = Logger.getInstance(ChatWindowDelegate.class);
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";


    public enum TabAnswerStatus {
        IDLE("idle"),
        QUEUED("queued"),
        PROCESSING("processing"),
        COMPLETED("completed");

        private final String value;

        TabAnswerStatus(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /**
         * 前端传入的 tab 状态字符串映射到枚举值。
         * processing 与 answering 均归为 PROCESSING；未知/null 归为 IDLE。
         */
        public static TabAnswerStatus fromValue(String str) {
            if (str == null) {
                return IDLE;
            }
            switch (str) {
                case "queued":
                    return QUEUED;
                case "processing":
                case "answering":
                    return PROCESSING;
                case "completed":
                    return COMPLETED;
                default:
                    return IDLE;
            }
        }
    }

    public interface DelegateHost {
        Project getProject();
        ClaudeSDKBridge getClaudeSDKBridge();
        CodexSDKBridge getCodexSDKBridge();
        ClaudeSession getSession();
        CodemossSettingsService getSettingsService();
        JPanel getMainPanel();
        JBCefBrowser getBrowser();
        boolean isDisposed();
        void callJavaScript(String fn, String... args);
        Content getParentContent();
        String getOriginalTabName();
        void setOriginalTabName(String name);
        String getSessionId();
        HandlerContext getHandlerContext();
        void setHandlerContext(HandlerContext ctx);
        void setMessageDispatcher(MessageDispatcher d);
        void setFrontendActionDispatcher(FrontendActionDispatcher d);
        void setPermissionHandler(PermissionActionHandlers h);
        SessionLifecycleManager getSessionLifecycleManager();
        StreamMessageCoalescer getStreamCoalescer();
        WebviewWatchdog getWebviewWatchdog();
        PermissionActionHandlers getPermissionHandler();
        void interruptDueToPermissionDenial();
        boolean isFrontendReady();
        void setFrontendReady(boolean ready);
        void setSlashCommandsFetched(boolean fetched);
        void setFetchedSlashCommandsCount(int count);
        void persistTabSessionState();
    }

    private final DelegateHost host;
    private PromptActionHandlers promptHandlers; // B2 迁移: 需要 dispose 停止 FileWatcher
    private TabAnswerStatus currentTabStatus = TabAnswerStatus.IDLE;

    private volatile String pendingQuickFixPrompt = null;
    private volatile MessageCallback pendingQuickFixCallback = null;

    public ChatWindowDelegate(DelegateHost host) {
        this.host = host;
    }

    public void loadNodePathFromSettings() {
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedNodePath = props.getValue(NODE_PATH_PROPERTY_KEY);

            if (savedNodePath != null && !savedNodePath.trim().isEmpty()) {
                String path = savedNodePath.trim();
                claudeSDKBridge.setNodeExecutable(path);
                codexSDKBridge.setNodeExecutable(path);
                claudeSDKBridge.verifyAndCacheNodePath(path);
                LOG.info("Using manually configured Node.js path: " + path);
            } else {
                LOG.info("No saved Node.js path found, attempting auto-detection...");
                com.github.claudecodegui.model.NodeDetectionResult detected =
                    claudeSDKBridge.detectNodeWithDetails();

                if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                    String detectedPath = detected.getNodePath();
                    String detectedVersion = detected.getNodeVersion();

                    props.setValue(NODE_PATH_PROPERTY_KEY, detectedPath);
                    claudeSDKBridge.setNodeExecutable(detectedPath);
                    codexSDKBridge.setNodeExecutable(detectedPath);
                    claudeSDKBridge.verifyAndCacheNodePath(detectedPath);

                    LOG.info("Auto-detected Node.js: " + detectedPath + " (" + detectedVersion + ")");
                } else {
                    LOG.warn("Failed to auto-detect Node.js path. Error: " +
                        (detected != null ? detected.getErrorMessage() : "Unknown error"));
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load Node.js path: " + e.getMessage(), e);
        }
    }

    public void loadPermissionModeFromSettings() {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            if (savedMode != null && !savedMode.trim().isEmpty()) {
                String mode = savedMode.trim();
                ClaudeSession session = host.getSession();
                if (session != null) {
                    session.setPermissionMode(mode);
                    host.persistTabSessionState();
                    LOG.info("Loaded permission mode from settings: " + mode);
                    com.github.claudecodegui.notifications.ClaudeNotifier.setMode(host.getProject(), mode);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load permission mode: " + e.getMessage());
        }
    }

    public void loadInvocationModeFromSettings() {
        try {
            String mode = CodemossSettingsService.getInstance().getClaudeInvocationMode();
            ClaudeSession session = host.getSession();
            if (mode != null && session != null) {
                session.setClaudeInvocationMode(mode);
                LOG.info("Loaded invocation mode from settings: " + mode);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load invocation mode: " + e.getMessage());
        }
    }

    public void savePermissionModeToSettings(String mode) {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
            LOG.info("Saved permission mode to settings: " + mode);
        } catch (Exception e) {
            LOG.warn("Failed to save permission mode: " + e.getMessage());
        }
    }

    public void syncActiveProvider() {
        try {
            CodemossSettingsService settingsService = host.getSettingsService();
            if (settingsService.isLocalProviderActive()) {
                LOG.info("[ClaudeSDKToolWindow] Local provider active, skipping startup sync");
                return;
            }
            settingsService.applyActiveProviderToClaudeSettings();
        } catch (Exception e) {
            LOG.warn("Failed to sync active provider on startup: " + e.getMessage());
        }
    }

    public String setupPermissionService() {
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        Project project = host.getProject();
        String sessionId = claudeSDKBridge.getSessionId();

        if ((sessionId == null || sessionId.isEmpty()) && codexSDKBridge != null) {
            sessionId = codexSDKBridge.getSessionId();
        }

        if (sessionId == null || sessionId.isEmpty()) {
            LOG.warn("Failed to get session ID from bridges, generating fallback UUID");
            sessionId = java.util.UUID.randomUUID().toString();
        }

        claudeSDKBridge.setSessionId(sessionId);
        if (codexSDKBridge != null) {
            codexSDKBridge.setSessionId(sessionId);
        }
        LOG.info("Unified bridge sessionId for PermissionService routing: " + sessionId);

        PermissionService permissionService = PermissionService.getInstance(project, sessionId);
        ClaudeSession session = host.getSession();
        if (session != null) {
            session.setPermissionSessionId(sessionId);
        }
        permissionService.start();
        permissionService.registerDialogShower(project, (toolName, inputs) ->
            host.getPermissionHandler().showFrontendPermissionDialog(toolName, inputs));
        permissionService.registerAskUserQuestionDialogShower(project, (requestId, questionsData) ->
            host.getPermissionHandler().showAskUserQuestionDialog(requestId, questionsData));
        permissionService.registerPlanApprovalDialogShower(project, (requestId, planData) ->
            host.getPermissionHandler().showPlanApprovalDialog(requestId, planData));
        LOG.info("Started permission service with frontend dialog, AskUserQuestion dialog, and PlanApproval dialog for project: " + project.getName());
        return sessionId;
    }

    public void initializeHandlers() {
        Project project = host.getProject();
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        CodemossSettingsService settingsService = host.getSettingsService();

        HandlerContext.FrontendReadyChecker frontendReadyChecker = () -> host.isFrontendReady();
        HandlerContext.JsCallback jsCallback = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                host.callJavaScript(functionName, args);
            }
            @Override
            public String escapeJs(String str) {
                return JsUtils.escapeJs(str);
            }
        };

        HandlerContext handlerContext = new HandlerContext(project, claudeSDKBridge, codexSDKBridge, settingsService, jsCallback);
        handlerContext.setSession(host.getSession());
        handlerContext.setFrontendReadyChecker(frontendReadyChecker);
        host.setHandlerContext(handlerContext);

        MessageDispatcher messageDispatcher = new MessageDispatcher();
        host.setMessageDispatcher(messageDispatcher);

        // Typed frontend action dispatcher: migrated settings actions (model registry + appearance)
        // are served by dedicated typed handlers; the remaining SettingsHandler actions are bridged
        // via LegacyMessageHandlerAdapter. This dispatcher is consulted before the legacy
        // MessageDispatcher in ClaudeChatWindow#handleMessage.
        CodemossSettingsService settings = handlerContext.getSettingsService();
        ModelRegistryService modelRegistryService = new ModelRegistryService(settings);
        AppearanceConfigService appearanceConfigService = new AppearanceConfigService(settings);
        List<FrontendActionHandler<?>> typedHandlers = new ArrayList<>();
        typedHandlers.add(new GetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new SetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new ResetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new GetModelRegistrySchemaActionHandler(modelRegistryService));
        typedHandlers.add(new SetAppearanceConfigActionHandler(appearanceConfigService));
        typedHandlers.add(new GetCodexSubscriptionQuotaActionHandler());
        typedHandlers.add(new GetClaudeCliPathActionHandler());
        typedHandlers.add(new SetClaudeCliPathActionHandler());
        typedHandlers.add(new GetNodePathActionHandler());
        typedHandlers.add(new SetNodePathActionHandler());
        typedHandlers.add(new ReadClipboardActionHandler());
        typedHandlers.add(new WriteClipboardActionHandler());
        typedHandlers.add(new CreateNewTabActionHandler());
        typedHandlers.add(new RewindFilesActionHandler());
        typedHandlers.add(new GetContextUsageActionHandler());
        typedHandlers.add(new EnhancePromptActionHandler());
        typedHandlers.add(new SaveMarkdownActionHandler());
        typedHandlers.add(new SaveJsonActionHandler());
        typedHandlers.add(new UndoFileChangesActionHandler());
        typedHandlers.add(new UndoAllFileChangesActionHandler());
        // Permission mode (B3 slice: permission mode)
        PermissionModeHandler permissionModeHandler = new PermissionModeHandler(handlerContext);
        typedHandlers.add(new GetModeActionHandler(permissionModeHandler));
        typedHandlers.add(new SetModeActionHandler(permissionModeHandler));
        typedHandlers.add(new SetSessionModeActionHandler(permissionModeHandler));
        // Input history (B3 slice: input history)
        InputHistoryHandler inputHistoryHandler = new InputHistoryHandler(handlerContext);
        typedHandlers.add(new GetInputHistoryActionHandler(inputHistoryHandler));
        typedHandlers.add(new RecordInputHistoryActionHandler(inputHistoryHandler));
        typedHandlers.add(new DeleteInputHistoryItemActionHandler(inputHistoryHandler));
        typedHandlers.add(new ClearInputHistoryActionHandler(inputHistoryHandler));
        // Model / provider (B3 slice: model-provider)
        ModelProviderHandler modelProviderHandler = new ModelProviderHandler(handlerContext, new UsagePushService(handlerContext));
        typedHandlers.add(new SetModelActionHandler(modelProviderHandler));
        typedHandlers.add(new SetSessionModelActionHandler(modelProviderHandler));
        typedHandlers.add(new SetProviderActionHandler(modelProviderHandler));
        typedHandlers.add(new SetSessionProviderActionHandler(modelProviderHandler));
        typedHandlers.add(new SetReasoningEffortActionHandler(modelProviderHandler));
        typedHandlers.add(new SetCodexFastModeActionHandler(modelProviderHandler));
        typedHandlers.addAll(LegacyMessageHandlerAdapter.from(new SettingsHandler(handlerContext)));

        // Session action handlers (B2 迁移: send/interrupt/restart)
        SessionActionHandlers sessionActionHandlers = new SessionActionHandlers(handlerContext);
        typedHandlers.add(new SendMessageActionHandler(sessionActionHandlers));
        typedHandlers.add(new SendMessageWithAttachmentsActionHandler(sessionActionHandlers));
        typedHandlers.add(new InterruptSessionActionHandler(sessionActionHandlers));
        typedHandlers.add(new RestartSessionActionHandler(sessionActionHandlers));

        // Window action handlers (B2 迁移: heartbeat + tab status + session lifecycle)
        WindowActionHandlers windowHandlers = new WindowActionHandlers(new WindowActionHandlers.Callback() {
            @Override public void onHeartbeat(String content) { host.getWebviewWatchdog().handleHeartbeat(content); }
            @Override public void onTabLoadingChanged(boolean loading) {
                if (loading) {
                    updateTabStatus(TabAnswerStatus.PROCESSING);
                } else if (currentTabStatus != TabAnswerStatus.COMPLETED) {
                    updateTabStatus(TabAnswerStatus.IDLE);
                }
            }
            @Override public void onTabStatusChanged(String statusStr) {
                updateTabStatus(TabAnswerStatus.fromValue(statusStr));
            }
            @Override public void onCreateNewSession() {
                host.getSessionLifecycleManager().createNewSession();
            }
            @Override public void onFrontendReady() { handleFrontendReady(); }
            @Override public void onRefreshSlashCommands() {
                host.getSessionLifecycleManager().fetchSlashCommandsOnStartup();
            }
        });
        typedHandlers.add(new HeartbeatActionHandler(windowHandlers));
        typedHandlers.add(new TabLoadingChangedActionHandler(windowHandlers));
        typedHandlers.add(new TabStatusChangedActionHandler(windowHandlers));
        typedHandlers.add(new CreateNewSessionActionHandler(windowHandlers));
        typedHandlers.add(new FrontendReadyActionHandler(windowHandlers));
        typedHandlers.add(new RefreshSlashCommandsActionHandler(windowHandlers));

        // MCP server action handlers (B2 迁移: server CRUD + status + tools)
        McpServerActionHandlers mcpServerHandlers = new McpServerActionHandlers(handlerContext);
        typedHandlers.add(new GetMcpServersActionHandler(mcpServerHandlers));
        typedHandlers.add(new GetMcpServerStatusActionHandler(mcpServerHandlers));
        typedHandlers.add(new GetMcpServerToolsActionHandler(mcpServerHandlers));
        typedHandlers.add(new AddMcpServerActionHandler(mcpServerHandlers));
        typedHandlers.add(new UpdateMcpServerActionHandler(mcpServerHandlers));
        typedHandlers.add(new DeleteMcpServerActionHandler(mcpServerHandlers));
        typedHandlers.add(new ToggleMcpServerActionHandler(mcpServerHandlers));
        typedHandlers.add(new ValidateMcpServerActionHandler(mcpServerHandlers));

        // Codex MCP server action handlers (B2 迁移: Codex server CRUD + status + tools)
        CodexMcpServerActionHandlers codexMcpServerHandlers = new CodexMcpServerActionHandlers(handlerContext, settingsService.getCodexMcpServerManager());
        typedHandlers.add(new GetCodexMcpServersActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new GetCodexMcpServerStatusActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new GetCodexMcpServerToolsActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new AddCodexMcpServerActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new UpdateCodexMcpServerActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new DeleteCodexMcpServerActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new ToggleCodexMcpServerActionHandler(codexMcpServerHandlers));
        typedHandlers.add(new ValidateCodexMcpServerActionHandler(codexMcpServerHandlers));

        // Agent action handlers (B2 迁移: agent CRUD + selection + import/export)
        AgentActionHandlers agentHandlers = new AgentActionHandlers(handlerContext);
        typedHandlers.add(new GetAgentsActionHandler(agentHandlers));
        typedHandlers.add(new AddAgentActionHandler(agentHandlers));
        typedHandlers.add(new UpdateAgentActionHandler(agentHandlers));
        typedHandlers.add(new DeleteAgentActionHandler(agentHandlers));
        typedHandlers.add(new GetSelectedAgentActionHandler(agentHandlers));
        typedHandlers.add(new SetSelectedAgentActionHandler(agentHandlers));
        typedHandlers.add(new ExportAgentsActionHandler(agentHandlers));
        typedHandlers.add(new ImportAgentsFileActionHandler(agentHandlers));
        typedHandlers.add(new SaveImportedAgentsActionHandler(agentHandlers));

        // Skill action handlers (B2 迁移: skill CRUD + toggle + open)
        SkillActionHandlers skillHandlers = new SkillActionHandlers(handlerContext);
        typedHandlers.add(new GetAllSkillsActionHandler(skillHandlers));
        typedHandlers.add(new ImportSkillActionHandler(skillHandlers));
        typedHandlers.add(new DeleteSkillActionHandler(skillHandlers));
        typedHandlers.add(new OpenSkillActionHandler(skillHandlers));
        typedHandlers.add(new ToggleSkillActionHandler(skillHandlers));

        // Prompt action handlers (B2 迁移: prompt CRUD + import/export + file watcher)
        this.promptHandlers = new PromptActionHandlers(handlerContext);
        typedHandlers.add(new GetPromptsActionHandler(promptHandlers));
        typedHandlers.add(new GetProjectInfoActionHandler(promptHandlers));
        typedHandlers.add(new AddPromptActionHandler(promptHandlers));
        typedHandlers.add(new UpdatePromptActionHandler(promptHandlers));
        typedHandlers.add(new DeletePromptActionHandler(promptHandlers));
        typedHandlers.add(new ExportPromptsActionHandler(promptHandlers));
        typedHandlers.add(new ImportPromptsFileActionHandler(promptHandlers));
        typedHandlers.add(new SaveImportedPromptsActionHandler(promptHandlers));

        // Dependency action handlers (B2 迁移: SDK install/uninstall/update/versions/node env)
        DependencyActionHandlers dependencyHandlers = new DependencyActionHandlers(handlerContext);
        typedHandlers.add(new GetDependencyStatusActionHandler(dependencyHandlers));
        typedHandlers.add(new InstallDependencyActionHandler(dependencyHandlers));
        typedHandlers.add(new UninstallDependencyActionHandler(dependencyHandlers));
        typedHandlers.add(new UpdateDependencyActionHandler(dependencyHandlers));
        typedHandlers.add(new CheckDependencyUpdatesActionHandler(dependencyHandlers));
        typedHandlers.add(new GetDependencyVersionsActionHandler(dependencyHandlers));
        typedHandlers.add(new CheckNodeEnvironmentActionHandler(dependencyHandlers));

        // Node process action handlers (B2 迁移: get/kill/kill-all-orphan/restart-daemon)
        NodeProcessActionHandlers nodeProcessHandlers = new NodeProcessActionHandlers(handlerContext);
        typedHandlers.add(new GetNodeProcessesActionHandler(nodeProcessHandlers));
        typedHandlers.add(new KillNodeProcessActionHandler(nodeProcessHandlers));
        typedHandlers.add(new KillAllOrphansActionHandler(nodeProcessHandlers));
        typedHandlers.add(new RestartNodeDaemonActionHandler(nodeProcessHandlers));

        // File action handlers (B2 迁移: list/open-file/open-browser/open-class/linkify/resolve-path)
        FileActionHandlers fileHandlers = new FileActionHandlers(handlerContext);
        typedHandlers.add(new ListFilesActionHandler(fileHandlers));
        typedHandlers.add(new OpenFileActionHandler(fileHandlers));
        typedHandlers.add(new OpenBrowserActionHandler(fileHandlers));
        typedHandlers.add(new OpenClassActionHandler(fileHandlers));
        typedHandlers.add(new GetLinkifyCapabilitiesActionHandler(fileHandlers));
        typedHandlers.add(new ResolveFilePathActionHandler(fileHandlers));

        // Diff action handlers (B2 迁移: refresh/show-diff variants via DiffRequestDispatcher 责任链)
        DiffActionHandlers diffHandlers = new DiffActionHandlers(handlerContext);
        typedHandlers.add(new RefreshFileActionHandler(diffHandlers));
        typedHandlers.add(new ShowDiffActionHandler(diffHandlers));
        typedHandlers.add(new ShowMultiEditDiffActionHandler(diffHandlers));
        typedHandlers.add(new ShowEditPreviewDiffActionHandler(diffHandlers));
        typedHandlers.add(new ShowEditFullDiffActionHandler(diffHandlers));
        typedHandlers.add(new ShowEditableDiffActionHandler(diffHandlers));
        typedHandlers.add(new ShowInteractiveDiffActionHandler(diffHandlers));

        // Provider action handlers (B2 迁移: Claude + Codex provider CRUD/switch/import-export/sort)
        ProviderActionHandlers providerHandlers = new ProviderActionHandlers(handlerContext);
        typedHandlers.add(new GetProvidersActionHandler(providerHandlers));
        typedHandlers.add(new GetCurrentClaudeConfigActionHandler(providerHandlers));
        typedHandlers.add(new GetThinkingEnabledActionHandler(providerHandlers));
        typedHandlers.add(new SetThinkingEnabledActionHandler(providerHandlers));
        typedHandlers.add(new AddProviderActionHandler(providerHandlers));
        typedHandlers.add(new UpdateProviderActionHandler(providerHandlers));
        typedHandlers.add(new DeleteProviderActionHandler(providerHandlers));
        typedHandlers.add(new SwitchProviderActionHandler(providerHandlers));
        typedHandlers.add(new GetActiveProviderActionHandler(providerHandlers));
        typedHandlers.add(new PreviewCcSwitchImportActionHandler(providerHandlers));
        typedHandlers.add(new OpenFileChooserForCcSwitchActionHandler(providerHandlers));
        typedHandlers.add(new SaveImportedProvidersActionHandler(providerHandlers));
        typedHandlers.add(new SortProvidersActionHandler(providerHandlers));
        typedHandlers.add(new GetCodexProvidersActionHandler(providerHandlers));
        typedHandlers.add(new GetCurrentCodexConfigActionHandler(providerHandlers));
        typedHandlers.add(new AddCodexProviderActionHandler(providerHandlers));
        typedHandlers.add(new UpdateCodexProviderActionHandler(providerHandlers));
        typedHandlers.add(new DeleteCodexProviderActionHandler(providerHandlers));
        typedHandlers.add(new SwitchCodexProviderActionHandler(providerHandlers));
        typedHandlers.add(new RevokeCodexLocalConfigAuthorizationActionHandler(providerHandlers));
        typedHandlers.add(new GetActiveCodexProviderActionHandler(providerHandlers));
        typedHandlers.add(new SortCodexProvidersActionHandler(providerHandlers));

        host.setFrontendActionDispatcher(
                new FrontendActionDispatcher(typedHandlers, handlerContext));

        // Permission: shared state container + 3 typed handlers
        PermissionActionHandlers permissionHandlers = new PermissionActionHandlers(handlerContext);
        permissionHandlers.setPermissionDeniedCallback(host::interruptDueToPermissionDenial);
        host.setPermissionHandler(permissionHandlers);
        typedHandlers.add(new PermissionDecisionActionHandler(permissionHandlers));
        typedHandlers.add(new AskUserQuestionResponseActionHandler(permissionHandlers));
        typedHandlers.add(new PlanApprovalResponseActionHandler(permissionHandlers));

        // History action handlers (B4 迁移: HistoryHandler 非孤儿,按 B2 范式迁移;SessionLoadCallback 接入容器)
        HistoryActionHandlers historyHandlers = new HistoryActionHandlers(handlerContext);
        historyHandlers.setSessionLoadCallback((sessionId, projectPath, provider) ->
            host.getSessionLifecycleManager().loadHistorySession(sessionId, projectPath, provider));
        typedHandlers.add(new LoadHistoryDataActionHandler(historyHandlers));
        typedHandlers.add(new LoadSessionActionHandler(historyHandlers));
        typedHandlers.add(new DeleteSessionActionHandler(historyHandlers));
        typedHandlers.add(new DeleteSessionsActionHandler(historyHandlers));
        typedHandlers.add(new ExportSessionActionHandler(historyHandlers));
        typedHandlers.add(new ToggleFavoriteActionHandler(historyHandlers));
        typedHandlers.add(new UpdateTitleActionHandler(historyHandlers));
        typedHandlers.add(new DeleteTitleActionHandler(historyHandlers));
        typedHandlers.add(new DeepSearchHistoryActionHandler(historyHandlers));
        typedHandlers.add(new LoadSubagentSessionActionHandler(historyHandlers));
        typedHandlers.add(new ConvertToCliSessionActionHandler(historyHandlers));

        LOG.info("Registered " + messageDispatcher.getHandlerCount() + " message handlers");
    }

    public void initializeStatusBar() {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = host.getProject();
            if (project == null || host.isDisposed()) { return; }

            ClaudeSession session = host.getSession();
            String mode = session != null ? session.getPermissionMode() : CommonConstants.PERMISSION_MODE_DEFAULT;
            com.github.claudecodegui.notifications.ClaudeNotifier.setMode(project, mode);

            String model = session != null ? session.getModel() : HandlerContext.DEFAULT_MODEL;
            com.github.claudecodegui.notifications.ClaudeNotifier.setModel(project, model);

            try {
                CodemossSettingsService settingsService = host.getSettingsService();
                String selectedId = settingsService.getSelectedAgentId();
                if (selectedId != null) {
                    JsonObject agent = settingsService.getAgent(selectedId);
                    if (agent != null) {
                        String agentName = agent.has("name") ? agent.get("name").getAsString() : "Agent";
                        com.github.claudecodegui.notifications.ClaudeNotifier.setAgent(project, agentName);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to set initial agent in status bar: " + e.getMessage());
            }
        });
    }

    public void updateTabStatus(TabAnswerStatus status) {
        Content parentContent = host.getParentContent();
        String originalTabName = host.getOriginalTabName();
        if (parentContent == null || originalTabName == null) {
            LOG.warn("[TabStatus] Cannot update - parentContent or originalTabName is null");
            return;
        }

        if (status == currentTabStatus) {
            LOG.debug("[TabStatus] Skipping redundant update for tab: " + originalTabName);
            return;
        }

        currentTabStatus = status;

        ApplicationManager.getApplication().invokeLater(() -> {
            String tabName = originalTabName;
            String currentDisplayName = parentContent.getDisplayName();
            if (currentDisplayName != null && !currentDisplayName.startsWith(tabName)) {
                tabName = currentDisplayName.endsWith("...")
                    ? currentDisplayName.substring(0, currentDisplayName.length() - 3)
                    : currentDisplayName;
                host.setOriginalTabName(tabName);
                LOG.debug("[TabStatus] Detected external rename, updated originalTabName to: " + tabName);
            }

            String displayName;
            switch (status) {
                case QUEUED:
                    displayName = tabName;
                    parentContent.setIcon(createStatusDotIcon(new Color(0xD98E04)));
                    LOG.debug("[TabStatus] Set queued state for tab: " + displayName);
                    break;
                case PROCESSING:
                    displayName = tabName;
                    parentContent.setIcon(createStatusDotIcon(new Color(0x2F7DFF)));
                    LOG.debug("[TabStatus] Set processing state for tab: " + displayName);
                    break;
                case COMPLETED:
                    displayName = tabName;
                    parentContent.setIcon(createStatusDotIcon(new Color(0x2FA35B)));
                    LOG.debug("[TabStatus] Set completed state for tab: " + displayName);
                    break;
                case IDLE:
                default:
                    displayName = tabName;
                    parentContent.setIcon(null);
                    LOG.debug("[TabStatus] Restored idle state for tab: " + displayName);
                    break;
            }
            parentContent.setDisplayName(displayName);
        });
    }

    private static Icon createStatusDotIcon(Color color) {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(1, 1, 8, 8);
        } finally {
            g2.dispose();
        }
        return new ImageIcon(image);
    }

    public void sendQuickFixMessage(String prompt, boolean isQuickFix, MessageCallback callback) {
        ClaudeSession session = host.getSession();
        if (session == null) {
            LOG.warn("QuickFix: Session is null, cannot send message");
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError("Session not initialized. Please wait for the tool window to fully load.");
            });
            return;
        }

        session.getContextCollector().setQuickFix(isQuickFix);

        if (!host.isFrontendReady()) {
            LOG.info("QuickFix: Frontend not ready, queuing message for later");
            pendingQuickFixPrompt = prompt;
            pendingQuickFixCallback = callback;
            return;
        }

        executeQuickFixInternal(prompt, callback);
    }

    private void executePendingQuickFix(String prompt, MessageCallback callback) {
        ClaudeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError("Session not available");
            });
            return;
        }
        executeQuickFixInternal(prompt, callback);
    }

    private void executeQuickFixInternal(String prompt, MessageCallback callback) {
        String escapedPrompt = JsUtils.escapeJs(prompt);
        host.callJavaScript("addUserMessage", escapedPrompt);
        host.callJavaScript("showLoading", "true");

        host.getSession().send(prompt, null, (String) null).thenRun(() -> {
            List<ClaudeSession.Message> messages = host.getSession().getMessages();
            if (!messages.isEmpty()) {
                ClaudeSession.Message last = messages.get(messages.size() - 1);
                if (last.type == ClaudeSession.Message.Type.ASSISTANT && last.content != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callback.onComplete(SDKResult.success(last.content));
                    });
                }
            }
        }).exceptionally(ex -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError(ex.getMessage());
            });
            return null;
        });
    }

    public void handleFrontendReady() {
        LOG.info("Received frontend_ready signal, frontend is now ready to receive data");
        host.setFrontendReady(true);

        host.callJavaScript(
            "window.updateLinkifyCapabilities",
            JsUtils.escapeJs(OpenClassHandler.buildCapabilitiesJson())
        );
        host.getSessionLifecycleManager().sendCurrentPermissionMode();
        replayCurrentSessionStateToFrontend();
        host.persistTabSessionState();

        if (pendingQuickFixPrompt != null && pendingQuickFixCallback != null) {
            LOG.info("Processing pending QuickFix message after frontend ready");
            String prompt = pendingQuickFixPrompt;
            MessageCallback callback = pendingQuickFixCallback;
            pendingQuickFixPrompt = null;
            pendingQuickFixCallback = null;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                executePendingQuickFix(prompt, callback);
            });
        }

        host.getStreamCoalescer().flush(null);
    }

    private void replayCurrentSessionStateToFrontend() {
        ClaudeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            return;
        }

        try {
            String sessionId = session.getSessionId();
            if (sessionId != null && !sessionId.trim().isEmpty()) {
                host.callJavaScript("setSessionId", JsUtils.escapeJs(sessionId));
            }

            List<ClaudeSession.Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                String messagesJson = MessageJsonConverter.convertMessagesToJson(messages);
                host.callJavaScript("updateMessages", JsUtils.escapeJs(messagesJson));
            }

            host.callJavaScript("showLoading", String.valueOf(session.isLoading()));
            host.callJavaScript("showThinkingStatus", String.valueOf(false));

            String summary = session.getSummary();
            if (summary != null && !summary.trim().isEmpty()) {
                host.callJavaScript("showSummary", JsUtils.escapeJs(summary));
            }

            // FIX: Restore streaming state after webview reload.
            // When the watchdog reloads the webview during active streaming, the frontend's
            // isStreamingRef is reset to false, causing all onContentDelta callbacks to be
            // silently dropped.  Re-sending onStreamStart ensures the frontend accepts
            // subsequent streaming deltas and the stall watchdog is properly initialized.
            boolean streamActive = host.getStreamCoalescer().isStreamActive();
            if (streamActive) {
                LOG.debug("Replaying streaming state to frontend (session was actively streaming during reload)");
                host.callJavaScript("onStreamStart", "replay");
            }

            LOG.info("Replayed current session state to frontend: sessionId="
                    + (sessionId != null ? sessionId : "(none)")
                    + ", messages=" + messages.size()
                    + ", loading=" + session.isLoading()
                    + ", streaming=" + streamActive);
        } catch (Exception e) {
            LOG.warn("Failed to replay current session state to frontend: " + e.getMessage(), e);
        }
    }

    public void dispose() {
        if (promptHandlers != null) {
            promptHandlers.dispose();
        }
    }
}
