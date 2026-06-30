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
import com.github.claudecodegui.model.selection.DefaultModelCapabilityResolver;
import com.github.claudecodegui.model.selection.ModelSelectionRequest;
import com.github.claudecodegui.model.selection.ModelSelectionResult;
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
import com.github.claudecodegui.handler.provider.GetOpenCodeProvidersActionHandler;
import com.github.claudecodegui.handler.provider.GetCurrentOpenCodeConfigActionHandler;
import com.github.claudecodegui.handler.provider.AddOpenCodeProviderActionHandler;
import com.github.claudecodegui.handler.provider.UpdateOpenCodeProviderActionHandler;
import com.github.claudecodegui.handler.provider.DeleteOpenCodeProviderActionHandler;
import com.github.claudecodegui.handler.provider.SwitchOpenCodeProviderActionHandler;
import com.github.claudecodegui.handler.provider.RevokeOpenCodeLocalConfigAuthorizationActionHandler;
import com.github.claudecodegui.handler.provider.GetActiveOpenCodeProviderActionHandler;
import com.github.claudecodegui.handler.provider.SortOpenCodeProvidersActionHandler;
import com.github.claudecodegui.handler.ProjectConfigHandler;
import com.github.claudecodegui.handler.UserLanguageHandler;
import com.github.claudecodegui.handler.RuntimePolicyHandler;
import com.github.claudecodegui.handler.settings.GetClaudeCliPathActionHandler;
import com.github.claudecodegui.handler.settings.GetCodexSubscriptionQuotaActionHandler;
import com.github.claudecodegui.handler.settings.FetchProviderModelsActionHandler;
import com.github.claudecodegui.handler.settings.GetModelRegistryActionHandler;
import com.github.claudecodegui.handler.settings.SetModelRegistryActionHandler;
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
import com.github.claudecodegui.handler.settings.GetUsageStatisticsActionHandler;
import com.github.claudecodegui.handler.settings.GetWorkingDirectoryActionHandler;
import com.github.claudecodegui.handler.settings.SetWorkingDirectoryActionHandler;
import com.github.claudecodegui.handler.settings.GetEditorFontConfigActionHandler;
import com.github.claudecodegui.handler.settings.GetUiFontConfigActionHandler;
import com.github.claudecodegui.handler.settings.SetUiFontConfigActionHandler;
import com.github.claudecodegui.handler.settings.BrowseUiFontFileActionHandler;
import com.github.claudecodegui.handler.settings.GetCodeFontConfigActionHandler;
import com.github.claudecodegui.handler.settings.SetCodeFontConfigActionHandler;
import com.github.claudecodegui.handler.settings.BrowseCodeFontFileActionHandler;
import com.github.claudecodegui.handler.settings.GetStreamingEnabledActionHandler;
import com.github.claudecodegui.handler.settings.SetStreamingEnabledActionHandler;
import com.github.claudecodegui.handler.settings.GetInvocationModeActionHandler;
import com.github.claudecodegui.handler.settings.GetSessionInvocationModeActionHandler;
import com.github.claudecodegui.handler.settings.GetSessionRuntimeStateActionHandler;
import com.github.claudecodegui.handler.settings.SetInvocationModeActionHandler;
import com.github.claudecodegui.handler.settings.SetCliPathActionHandler;
import com.github.claudecodegui.handler.settings.GetCodexSandboxModeActionHandler;
import com.github.claudecodegui.handler.settings.SetCodexSandboxModeActionHandler;
import com.github.claudecodegui.handler.settings.GetSendShortcutActionHandler;
import com.github.claudecodegui.handler.settings.SetSendShortcutActionHandler;
import com.github.claudecodegui.handler.settings.GetAutoOpenFileEnabledActionHandler;
import com.github.claudecodegui.handler.settings.SetAutoOpenFileEnabledActionHandler;
import com.github.claudecodegui.handler.settings.GetPermissionDialogTimeoutActionHandler;
import com.github.claudecodegui.handler.settings.SetPermissionDialogTimeoutActionHandler;
import com.github.claudecodegui.handler.settings.GetCommitGenerationEnabledActionHandler;
import com.github.claudecodegui.handler.settings.SetCommitGenerationEnabledActionHandler;
import com.github.claudecodegui.handler.settings.GetStatusBarWidgetEnabledActionHandler;
import com.github.claudecodegui.handler.settings.SetStatusBarWidgetEnabledActionHandler;
import com.github.claudecodegui.handler.settings.GetTaskCompletionNotificationEnabledActionHandler;
import com.github.claudecodegui.handler.settings.SetTaskCompletionNotificationEnabledActionHandler;
import com.github.claudecodegui.handler.settings.GetAiTitleGenerationEnabledActionHandler;
import com.github.claudecodegui.handler.settings.SetAiTitleGenerationEnabledActionHandler;
import com.github.claudecodegui.handler.settings.GetIdeThemeActionHandler;
import com.github.claudecodegui.handler.settings.GetCommitPromptActionHandler;
import com.github.claudecodegui.handler.settings.SetCommitPromptActionHandler;
import com.github.claudecodegui.handler.settings.GetCommitAiConfigActionHandler;
import com.github.claudecodegui.handler.settings.SetCommitAiConfigActionHandler;
import com.github.claudecodegui.handler.settings.GetPromptEnhancerConfigActionHandler;
import com.github.claudecodegui.handler.settings.SetPromptEnhancerConfigActionHandler;
import com.github.claudecodegui.handler.settings.GetProjectCommitPromptActionHandler;
import com.github.claudecodegui.handler.settings.SetProjectCommitPromptActionHandler;
import com.github.claudecodegui.handler.settings.SetUserLanguageActionHandler;
import com.github.claudecodegui.handler.settings.GetUserLanguageActionHandler;
import com.github.claudecodegui.handler.settings.ClearUserLanguageActionHandler;
import com.github.claudecodegui.handler.settings.GetRuntimePolicyActionHandler;
import com.github.claudecodegui.handler.settings.SetRuntimePolicyActionHandler;
import com.github.claudecodegui.handler.settings.ResetRuntimePolicyActionHandler;
import com.github.claudecodegui.handler.settings.GetRuntimePolicySchemaActionHandler;
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
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.MessageJsonConverter;
import com.github.claudecodegui.util.ThemeConfigService;
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
        com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge getOpenCodeSDKBridge();
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

        HandlerContext handlerContext = new HandlerContext(project, claudeSDKBridge, codexSDKBridge, host.getOpenCodeSDKBridge(), settingsService, jsCallback);
        handlerContext.setSession(host.getSession());
        if (host.getSession() != null) {
            handlerContext.setCurrentProvider(host.getSession().getProvider());
            handlerContext.setCurrentModel(host.getSession().getModel());
            handlerContext.setCurrentModelContextWindow(host.getSession().getState().getContextWindowOverride());
        }
        handlerContext.setFrontendReadyChecker(frontendReadyChecker);
        host.setHandlerContext(handlerContext);

        // Register theme change listener to notify frontend when the IDE theme changes (B3:
        // migrated verbatim from SettingsHandler#registerThemeChangeListener).
        ThemeConfigService.registerThemeChangeListener(themeConfig ->
                ApplicationManager.getApplication().invokeLater(() ->
                        handlerContext.dispatchEvent(DownstreamEvent.THEME_CHANGED.value(),
                                handlerContext.escapeJs(themeConfig.toString()))));

        // Typed frontend action dispatcher: all actions (model registry, appearance, project
        // config, user language, runtime policy, etc.) are served by dedicated typed handlers.
        // This is the sole dispatch path in ClaudeChatWindow#handleMessage (B3: the legacy
        // SettingsHandler string-dispatch has been fully retired).
        CodemossSettingsService settings = handlerContext.getSettingsService();
        ModelRegistryService modelRegistryService = new ModelRegistryService(settings);
        AppearanceConfigService appearanceConfigService = new AppearanceConfigService(settings);
        List<FrontendActionHandler<?>> typedHandlers = new ArrayList<>();
        typedHandlers.add(new GetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new SetModelRegistryActionHandler(modelRegistryService));
        typedHandlers.add(new GetModelRegistrySchemaActionHandler(modelRegistryService));
        typedHandlers.add(new SetAppearanceConfigActionHandler(appearanceConfigService));
        typedHandlers.add(new GetCodexSubscriptionQuotaActionHandler());
        // 模型拉取 RPC(第三方/代理预设:baseUrl+key → 动态拉取真实模型列表)
        typedHandlers.add(new FetchProviderModelsActionHandler());
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
        // Project config (B3 slice: project-config)
        ProjectConfigHandler projectConfigHandler = new ProjectConfigHandler(handlerContext);
        typedHandlers.add(new GetUsageStatisticsActionHandler(projectConfigHandler));
        typedHandlers.add(new GetWorkingDirectoryActionHandler(projectConfigHandler));
        typedHandlers.add(new SetWorkingDirectoryActionHandler(projectConfigHandler));
        typedHandlers.add(new GetEditorFontConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new GetUiFontConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new SetUiFontConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new BrowseUiFontFileActionHandler(projectConfigHandler));
        typedHandlers.add(new GetCodeFontConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new SetCodeFontConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new BrowseCodeFontFileActionHandler(projectConfigHandler));
        typedHandlers.add(new GetStreamingEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new SetStreamingEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new GetInvocationModeActionHandler(projectConfigHandler));
        typedHandlers.add(new GetSessionInvocationModeActionHandler(projectConfigHandler));
        typedHandlers.add(new GetSessionRuntimeStateActionHandler(projectConfigHandler));
        typedHandlers.add(new SetInvocationModeActionHandler(projectConfigHandler));
        typedHandlers.add(new SetCliPathActionHandler(projectConfigHandler));
        typedHandlers.add(new GetCodexSandboxModeActionHandler(projectConfigHandler));
        typedHandlers.add(new SetCodexSandboxModeActionHandler(projectConfigHandler));
        typedHandlers.add(new GetSendShortcutActionHandler(projectConfigHandler));
        typedHandlers.add(new SetSendShortcutActionHandler(projectConfigHandler));
        typedHandlers.add(new GetAutoOpenFileEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new SetAutoOpenFileEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new GetPermissionDialogTimeoutActionHandler(projectConfigHandler));
        typedHandlers.add(new SetPermissionDialogTimeoutActionHandler(projectConfigHandler));
        typedHandlers.add(new GetCommitGenerationEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new SetCommitGenerationEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new GetStatusBarWidgetEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new SetStatusBarWidgetEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new GetTaskCompletionNotificationEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new SetTaskCompletionNotificationEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new GetAiTitleGenerationEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new SetAiTitleGenerationEnabledActionHandler(projectConfigHandler));
        typedHandlers.add(new GetIdeThemeActionHandler(projectConfigHandler));
        typedHandlers.add(new GetCommitPromptActionHandler(projectConfigHandler));
        typedHandlers.add(new SetCommitPromptActionHandler(projectConfigHandler));
        typedHandlers.add(new GetCommitAiConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new SetCommitAiConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new GetPromptEnhancerConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new SetPromptEnhancerConfigActionHandler(projectConfigHandler));
        typedHandlers.add(new GetProjectCommitPromptActionHandler(projectConfigHandler));
        typedHandlers.add(new SetProjectCommitPromptActionHandler(projectConfigHandler));
        // User language (B3 slice: user-language)
        UserLanguageHandler userLanguageHandler = new UserLanguageHandler(handlerContext);
        typedHandlers.add(new SetUserLanguageActionHandler(userLanguageHandler));
        typedHandlers.add(new GetUserLanguageActionHandler(userLanguageHandler));
        typedHandlers.add(new ClearUserLanguageActionHandler(userLanguageHandler));
        // Runtime policy (B3 slice: runtime-policy)
        RuntimePolicyHandler runtimePolicyHandler = new RuntimePolicyHandler(handlerContext);
        typedHandlers.add(new GetRuntimePolicyActionHandler(runtimePolicyHandler));
        typedHandlers.add(new SetRuntimePolicyActionHandler(runtimePolicyHandler));
        typedHandlers.add(new ResetRuntimePolicyActionHandler(runtimePolicyHandler));
        typedHandlers.add(new GetRuntimePolicySchemaActionHandler(runtimePolicyHandler));

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
        // OpenCode provider handlers (对称 codex, Principle 6)
        typedHandlers.add(new GetOpenCodeProvidersActionHandler(providerHandlers));
        typedHandlers.add(new GetCurrentOpenCodeConfigActionHandler(providerHandlers));
        typedHandlers.add(new AddOpenCodeProviderActionHandler(providerHandlers));
        typedHandlers.add(new UpdateOpenCodeProviderActionHandler(providerHandlers));
        typedHandlers.add(new DeleteOpenCodeProviderActionHandler(providerHandlers));
        typedHandlers.add(new SwitchOpenCodeProviderActionHandler(providerHandlers));
        typedHandlers.add(new RevokeOpenCodeLocalConfigAuthorizationActionHandler(providerHandlers));
        typedHandlers.add(new GetActiveOpenCodeProviderActionHandler(providerHandlers));
        typedHandlers.add(new SortOpenCodeProvidersActionHandler(providerHandlers));

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

        // FrontendActionDispatcher 构造时一次性把传入 list 拷进路由表(快照语义),之后对 list
        // 的 add 不会反映到已构造的 dispatcher。因此必须在所有 typedHandlers.add 完成后构造 ——
        // 否则后注册的 permission/history handler 不会进入路由表,前端发出的 action 会落到
        // ClaudeChatWindow#handleJavaScriptMessage 的 unknown-type 分支被静默丢弃(B2/B4 迁移
        // 曾因构造时机错误回归:permission_decision / load_history_data 等 14 个 action 失效)。
        // verifyAllRegistered 自检兜底:若未来再次把构造提前,装配时立即 fail-fast。
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(typedHandlers, handlerContext);
        FrontendActionDispatcher.verifyAllRegistered(dispatcher, typedHandlers);
        host.setFrontendActionDispatcher(dispatcher);

        LOG.info("Registered " + typedHandlers.size() + " typed action handlers");
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
        sendCurrentModelRegistryToFrontend();
        sendCurrentModelSelectionToFrontend();
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

    /**
     * FIX(regression):前端就绪后主动下发模型注册表快照。
     *
     * 根因:新建标签 / 首次加载 / watchdog reload 会创建全新 JCEF webview,其前端
     * currentRegistry 模块单例初始为空。此前后端不主动下发 MODEL_REGISTRY,前端
     * 仅靠 ButtonArea 的 useEffect 竞态触发 requestModelRegistry() 填充——
     * ffa728db 删除 CLAUDE_MODELS 本地表 fallback 前,竞态空态被本地表掩盖;
     * 删除后该空态直接暴露为"no model configured"(见前端 chat.noModelConfigured)。
     *
     * 修复:frontend_ready 是后端确认前端可接收数据的信号,在此主动下发 registry,
     * 与 sendCurrentPermissionMode 对称(permission mode 在 ready 时下发,registry
     * 同理),使模型下拉不再依赖脆弱的前端竞态。载荷复用 ProviderOperations 已验证的
     * getModelRegistryJson()(= ModelRegistryService.serialize)。
     */
    private void sendCurrentModelRegistryToFrontend() {
        try {
            final String registryJson = host.getSettingsService().getModelRegistryJson();
            if (registryJson == null || registryJson.trim().isEmpty()) {
                return;
            }
            final HandlerContext ctx = host.getHandlerContext();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!host.isDisposed() && host.getBrowser() != null) {
                    ctx.dispatchEvent(DownstreamEvent.MODEL_REGISTRY.value(), ctx.escapeJs(registryJson));
                }
            });
        } catch (Exception e) {
            LOG.warn("Failed to send model registry on frontend ready: " + e.getMessage(), e);
        }
    }

    /**
     * FIX:前端就绪后主动下发当前会话的 provider/model 选择。
     *
     * <p>根因:新建标签 / 首次加载 / watchdog reload 会创建全新 JCEF webview,其前端
     * currentProvider 默认 'claude',而新 webview 的 localStorage 不可靠(独立 JS
     * 上下文,与 registry 回灌同一类隔离问题)。此前后端 ready 时已对称下发
     * permission mode / model registry / session messages,但遗漏了 provider/model
     * selection —— 新标签页的供应商总是回退到 claude。
     *
     * <p>修复:与 {@link SessionLifecycleManager#sendCurrentPermissionMode} /
     * {@link #sendCurrentModelRegistryToFrontend} 对称,从 session 真相源
     * (provider/model)重新 resolve 出 ModelSelectionResult,复用
     * {@link ModelProviderHandler#buildModelSelectionPayload} 构造载荷并 dispatch
     * MODEL_SELECTION。前端 useModelProviderState 已订阅该事件,收到后自动
     * setCurrentProvider + setSelected{Provider}Model,完成跨标签供应商状态回灌(前端零改)。
     */
    private void sendCurrentModelSelectionToFrontend() {
        try {
            final ClaudeSession session = host.getSession();
            if (session == null) {
                return;
            }
            final String provider = session.getProvider();
            final String model = session.getModel();
            if (provider == null || provider.trim().isEmpty()
                    || model == null || model.trim().isEmpty()) {
                return;
            }
            final HandlerContext ctx = host.getHandlerContext();
            ModelSelectionResult selection = new DefaultModelCapabilityResolver(
                    ctx.getSettingsService().getModelRegistry()
            ).resolve(new ModelSelectionRequest(provider, model, null, false));
            final String payload = ModelProviderHandler.buildModelSelectionPayload(selection).toString();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!host.isDisposed() && host.getBrowser() != null) {
                    ctx.dispatchEvent(DownstreamEvent.MODEL_SELECTION.value(), ctx.escapeJs(payload));
                }
            });
        } catch (Exception e) {
            LOG.warn("Failed to send current model selection on frontend ready: " + e.getMessage(), e);
        }
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
