// hooks/useSettingsWindowCallbacks.ts
import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { ProviderConfig, CodexProviderConfig, OpenCodeProviderConfig } from '../../../types/provider';
import type { AgentConfig } from '../../../types/agent';
import type { PromptConfig } from '../../../types/prompt';
import type { CommitAiConfig } from '../../../types/aiFeatureConfig';
import type { UiFontConfig, CodeFontConfig } from './useSettingsBasicActions';
import type { PromptEnhancerConfig } from '../../../types/promptEnhancer';
import type { AlertType } from '../../AlertDialog';
import type { ToastMessage } from '../../Toast';
import { subscribeActiveCodexProvider, subscribeActiveOpenCodeProvider, subscribeActiveProvider, subscribeCodexProviderList, subscribeOpenCodeProviderList, subscribeProviderList } from '../../../utils/runtimeProviderCapabilities';
import { registerLegacyAlias } from '../../../bridge';

export interface SettingsWindowCallbacksDeps {
  // State setters
  setNodePath: (path: string) => void;
  setNodeVersion: (version: string | null) => void;
  setMinNodeVersion: (version: number) => void;
  setSavingNodePath: (saving: boolean) => void;
  setWorkingDirectory: (dir: string) => void;
  setSavingWorkingDirectory: (saving: boolean) => void;
  setCommitPrompt: (prompt: string) => void;
  setSavingCommitPrompt: (saving: boolean) => void;
  setCommitAiConfig: (config: CommitAiConfig) => void;
  setPromptEnhancerConfig: (config: PromptEnhancerConfig) => void;
  setProjectCommitPrompt: (prompt: string) => void;
  setSavingProjectCommitPrompt: (saving: boolean) => void;
  setEditorFontConfig: (config: { fontFamily: string; fontSize: number; lineSpacing: number } | undefined) => void;
  setUiFontConfig: (config: UiFontConfig | undefined) => void;
  setCodeFontConfig: (config: CodeFontConfig | undefined) => void;
  setIdeTheme: (theme: 'light' | 'dark' | null) => void;
  setLocalStreamingEnabled: (enabled: boolean) => void;
  setCodexSandboxMode?: (mode: 'workspace-write' | 'danger-full-access') => void;
  setLocalSendShortcut: (shortcut: 'enter' | 'cmdEnter') => void;
  setLoading: (loading: boolean) => void;
  setCodexLoading: (loading: boolean) => void;
  setCodexConfigLoading: (loading: boolean) => void;
  setOpenCodeLoading: (loading: boolean) => void;
  setOpenCodeConfigLoading: (loading: boolean) => void;
  // AI feature toggle setters
  setCommitGenerationEnabled?: (enabled: boolean) => void;
  setAiTitleGenerationEnabled?: (enabled: boolean) => void;
  setStatusBarWidgetEnabled?: (enabled: boolean) => void;
  setTaskCompletionNotificationEnabled?: (enabled: boolean) => void;
  // Invocation mode setters
  setInvocationMode: (mode: 'sdk' | 'cli') => void;
  setCliPath: (path: string) => void;
  // Claude CLI path setters
  setClaudeCliPath?: (path: string) => void;
  setSavingClaudeCliPath?: (saving: boolean) => void;

  // Hook functions
  updateProviders: (providers: ProviderConfig[]) => void;
  updateActiveProvider: (provider: ProviderConfig) => void;
  loadProviders: () => void;
  loadCodexProviders: () => void;
  loadOpenCodeProviders: () => void;
  loadAgents: () => void;
  updateAgents: (agents: AgentConfig[]) => void;
  handleAgentOperationResult: (result: any) => void;
  handleAgentImportPreviewResult: (previewData: any) => void;
  handleAgentImportResult: (result: any) => void;
  updateCodexProviders: (providers: CodexProviderConfig[]) => void;
  updateActiveCodexProvider: (provider: CodexProviderConfig) => void;
  updateCurrentCodexConfig: (config: any) => void;
  updateOpenCodeProviders: (providers: OpenCodeProviderConfig[]) => void;
  updateActiveOpenCodeProvider: (provider: OpenCodeProviderConfig) => void;
  updateCurrentOpenCodeConfig: (config: any) => void;
  cleanupAgentsTimeout: () => void;

  // Prompt-related handlers (optional - now handled by PromptSection component)
  loadPrompts?: () => void;
  updatePrompts?: (prompts: PromptConfig[]) => void;
  handlePromptOperationResult?: (result: any) => void;
  handlePromptImportPreviewResult?: (previewData: any) => void;
  handlePromptImportResult?: (result: any) => void;
  cleanupPromptsTimeout?: () => void;

  // Callbacks
  showAlert: (type: AlertType, title: string, message: string) => void;
  addToast: (message: string, type?: ToastMessage['type']) => void;

  // Props
  onStreamingEnabledChangeProp?: (enabled: boolean) => void;
  onSendShortcutChangeProp?: (shortcut: 'enter' | 'cmdEnter') => void;
}

/**
 * Registers window callbacks for Java bridge communication in settings view.
 * Handles provider, agent, prompt, config, and theme callbacks.
 */
export function useSettingsWindowCallbacks(deps: SettingsWindowCallbacksDeps) {
  const { t } = useTranslation();

  // Use ref to avoid stale closures - callbacks always read latest deps
  const depsRef = useRef(deps);
  depsRef.current = deps;

  useEffect(() => {
    const d = () => depsRef.current;

    // Provider callbacks - subscribe to the registry instead of overriding
    // window callbacks directly. This keeps behavior deterministic when
    // multiple consumers (e.g. RuntimeProviderSelect) are mounted.
    const unsubscribeProviders = subscribeProviderList((jsonStr: string) => {
      try {
        const providersList: ProviderConfig[] = JSON.parse(jsonStr);
        d().updateProviders(providersList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse providers:', error);
        d().setLoading(false);
      }
    });

    const unsubscribeActiveProvider = subscribeActiveProvider((jsonStr: string) => {
      try {
        const activeProvider: ProviderConfig = JSON.parse(jsonStr);
        if (activeProvider) {
          d().updateActiveProvider(activeProvider);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse active provider:', error);
      }
    });

    // [归一化] 所有回调经 bridgeHub 订阅,替代旧 window.xxx 覆盖 + 链式转发。bridgeHub 广播到所有订阅者。
    registerLegacyAlias('showError', DOWNSTREAM.TOAST_ERROR);
    registerLegacyAlias('showSwitchSuccess', DOWNSTREAM.TOAST_SWITCH_SUCCESS);
    registerLegacyAlias('showSuccess', DOWNSTREAM.TOAST_SUCCESS);
    registerLegacyAlias('showSuccessI18n', DOWNSTREAM.TOAST_SUCCESS_I18N);
    registerLegacyAlias('updateNodePath', DOWNSTREAM.NODE_PATH);
    registerLegacyAlias('updateWorkingDirectory', DOWNSTREAM.CONFIG_WORKING_DIRECTORY);
    registerLegacyAlias('onEditorFontConfigReceived', DOWNSTREAM.FONT_EDITOR_CONFIG_RECEIVED);
    registerLegacyAlias('onUiFontConfigReceived', DOWNSTREAM.FONT_UI_CONFIG_RECEIVED);
    registerLegacyAlias('onCodeFontConfigReceived', DOWNSTREAM.FONT_CODE_CONFIG_RECEIVED);
    registerLegacyAlias('onIdeThemeReceived', DOWNSTREAM.THEME_RECEIVED);
    registerLegacyAlias('updateCodexSandboxMode', DOWNSTREAM.CONFIG_CODEX_SANDBOX_MODE);
    registerLegacyAlias('updateCommitPrompt', DOWNSTREAM.CONFIG_COMMIT_PROMPT);
    registerLegacyAlias('updatePromptEnhancerConfig', DOWNSTREAM.CONFIG_PROMPT_ENHANCER);
    registerLegacyAlias('updateCommitAiConfig', DOWNSTREAM.CONFIG_COMMIT_AI);
    registerLegacyAlias('updateProjectCommitPrompt', DOWNSTREAM.CONFIG_PROJECT_COMMIT_PROMPT);
    registerLegacyAlias('updateCommitGenerationEnabled', DOWNSTREAM.CONFIG_COMMIT_GENERATION);
    registerLegacyAlias('updateAiTitleGenerationEnabled', DOWNSTREAM.CONFIG_AI_TITLE_GENERATION);
    registerLegacyAlias('updateStatusBarWidgetEnabled', DOWNSTREAM.CONFIG_STATUS_BAR_WIDGET);
    registerLegacyAlias('updateTaskCompletionNotificationEnabled', DOWNSTREAM.CONFIG_TASK_COMPLETION_NOTIFICATION);
    registerLegacyAlias('updateInvocationMode', DOWNSTREAM.CONFIG_INVOCATION_MODE);
    registerLegacyAlias('updateClaudeCliPath', DOWNSTREAM.CONFIG_CLAUDE_CLI_PATH);
    registerLegacyAlias('updateAgents', DOWNSTREAM.AGENT_LIST);
    registerLegacyAlias('agentOperationResult', DOWNSTREAM.AGENT_OPERATION_RESULT);
    registerLegacyAlias('agentImportPreviewResult', DOWNSTREAM.AGENT_IMPORT_PREVIEW);
    registerLegacyAlias('agentImportResult', DOWNSTREAM.AGENT_IMPORT_RESULT);
    registerLegacyAlias('updatePrompts', DOWNSTREAM.PROMPT_LIST);
    registerLegacyAlias('promptOperationResult', DOWNSTREAM.PROMPT_OPERATION_RESULT);
    registerLegacyAlias('promptImportPreviewResult', DOWNSTREAM.PROMPT_IMPORT_PREVIEW);
    registerLegacyAlias('promptImportResult', DOWNSTREAM.PROMPT_IMPORT_RESULT);
    registerLegacyAlias('updateCurrentCodexConfig', DOWNSTREAM.PROVIDER_CODEX_CONFIG);
    registerLegacyAlias('updateOpenCodeProviders', DOWNSTREAM.PROVIDER_OPENCODE_LIST);
    registerLegacyAlias('updateActiveOpenCodeProvider', DOWNSTREAM.PROVIDER_ACTIVE_OPENCODE);
    registerLegacyAlias('updateCurrentOpenCodeConfig', DOWNSTREAM.PROVIDER_OPENCODE_CONFIG);

    const unsubs: Array<() => void> = [];

    unsubs.push(subscribeEvent(DOWNSTREAM.TOAST_ERROR, (message) => {
      d().showAlert('error', t('toast.operationFailed'), message as string);
      d().setLoading(false);
      d().setSavingNodePath(false);
      d().setSavingWorkingDirectory(false);
      d().setSavingCommitPrompt(false);
      d().setSavingProjectCommitPrompt(false);
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.TOAST_SWITCH_SUCCESS, (message) => {
      d().showAlert('success', t('toast.switchSuccess'), message as string);
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.NODE_PATH, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setNodePath(data.path || '');
        d().setNodeVersion(data.version || null);
        if (data.minVersion) {
          d().setMinNodeVersion(data.minVersion);
        }
      } catch (e) {
        console.warn('[SettingsView] Failed to parse updateNodePath JSON, fallback to legacy format:', e);
        d().setNodePath((jsonStr as string) || '');
      }
      d().setSavingNodePath(false);
      window.dispatchEvent(new CustomEvent('nodePathReady'));
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_WORKING_DIRECTORY, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setWorkingDirectory(data.customWorkingDir || '');
        d().setSavingWorkingDirectory(false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse working directory:', error);
        d().setSavingWorkingDirectory(false);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_CLAUDE_CLI_PATH, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setClaudeCliPath?.(data.path || '');
      } catch (e) {
        d().setClaudeCliPath?.((jsonStr as string) || '');
      }
      d().setSavingClaudeCliPath?.(false);
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.TOAST_SUCCESS, (message) => {
      d().showAlert('success', t('toast.operationSuccess'), message as string);
      d().setSavingNodePath(false);
      d().setSavingWorkingDirectory(false);
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.TOAST_SUCCESS_I18N, (i18nKey) => {
      const message = t(i18nKey as string);
      d().addToast(message, 'success');
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.FONT_EDITOR_CONFIG_RECEIVED, (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().setEditorFontConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse editor font config:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.FONT_UI_CONFIG_RECEIVED, (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().setUiFontConfig(config);
        window.applyUiFontConfig?.(config);
      } catch {
        // Silently ignore malformed UI font config from backend
      }
    }));

    // [归一化] 代码字体配置回显(与 editor/ui 字体平行,迁移期遗漏已补)
    unsubs.push(subscribeEvent(DOWNSTREAM.FONT_CODE_CONFIG_RECEIVED, (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().setCodeFontConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse code font config:', error);
      }
    }));

    // IDE theme callback
    unsubs.push(subscribeEvent(DOWNSTREAM.THEME_RECEIVED, (jsonStr) => {
      try {
        const themeData = JSON.parse(jsonStr as string);
        const theme = themeData.isDark ? 'dark' : 'light';
        d().setIdeTheme(theme);
      } catch (error) {
        console.error('[SettingsView] Failed to parse IDE theme:', error);
      }
    }));

    // Streaming configuration callback
    if (!d().onStreamingEnabledChangeProp) {
      unsubs.push(subscribeEvent(DOWNSTREAM.SETTING_STREAMING_ENABLED, (jsonStr) => {
        try {
          const data = JSON.parse(jsonStr as string);
          d().setLocalStreamingEnabled(data.streamingEnabled ?? true);
        } catch (error) {
          console.error('[SettingsView] Failed to parse streaming config:', error);
        }
      }));
    }

    // Codex sandbox mode callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_CODEX_SANDBOX_MODE, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        const mode = data?.sandboxMode;
        if (mode === 'workspace-write' || mode === 'danger-full-access') {
          d().setCodexSandboxMode?.(mode);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex sandbox mode config:', error);
      }
    }));

    // Send shortcut configuration callback
    if (!d().onSendShortcutChangeProp) {
      unsubs.push(subscribeEvent(DOWNSTREAM.SETTING_SEND_SHORTCUT, (jsonStr) => {
        try {
          const data = JSON.parse(jsonStr as string);
          d().setLocalSendShortcut(data.sendShortcut ?? 'enter');
        } catch (error) {
          console.error('[SettingsView] Failed to parse send shortcut config:', error);
        }
      }));
    }

    // Commit AI prompt callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_COMMIT_PROMPT, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setCommitPrompt(data.commitPrompt || '');
        d().setSavingCommitPrompt(false);
        if (data.projectCommitPrompt !== undefined) {
          d().setProjectCommitPrompt(data.projectCommitPrompt || '');
        }
        if (data.saved) {
          d().addToast(t('toast.saveSuccess'), 'success');
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit prompt:', error);
        d().setSavingCommitPrompt(false);
        d().addToast(t('toast.saveFailed'), 'error');
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_PROMPT_ENHANCER, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setPromptEnhancerConfig(data);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt enhancer config:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_COMMIT_AI, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setCommitAiConfig(data);
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit AI config:', error);
      }
    }));

    // Project-level commit AI prompt callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_PROJECT_COMMIT_PROMPT, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setProjectCommitPrompt(data.projectCommitPrompt || '');
        d().setSavingProjectCommitPrompt(false);
        if (data.saved) {
          d().addToast(t('toast.saveSuccess'), 'success');
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse project commit prompt:', error);
        d().setSavingProjectCommitPrompt(false);
        d().addToast(t('toast.saveFailed'), 'error');
      }
    }));

    // AI commit generation config callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_COMMIT_GENERATION, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setCommitGenerationEnabled?.(data.commitGenerationEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit generation config:', error);
      }
    }));

    // AI session title generation config callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_AI_TITLE_GENERATION, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setAiTitleGenerationEnabled?.(data.aiTitleGenerationEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse AI title generation config:', error);
      }
    }));

    // Status bar widget config callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_STATUS_BAR_WIDGET, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setStatusBarWidgetEnabled?.(data.statusBarWidgetEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse status bar widget config:', error);
      }
    }));

    // Task completion notification config callback (opt-in feature, default false)
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_TASK_COMPLETION_NOTIFICATION, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setTaskCompletionNotificationEnabled?.(data.taskCompletionNotificationEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse task completion notification config:', error);
      }
    }));

    // Invocation mode callback
    unsubs.push(subscribeEvent(DOWNSTREAM.CONFIG_INVOCATION_MODE, (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        const mode = data.invocationMode;
        if (mode === 'sdk' || mode === 'cli') {
          d().setInvocationMode(mode);
        }
        if (data.cliPath !== undefined) {
          d().setCliPath(data.cliPath);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse invocation mode:', error);
      }
    }));

    // Agent callbacks
    unsubs.push(subscribeEvent(DOWNSTREAM.AGENT_LIST, (jsonStr) => {
      try {
        const agentsList: AgentConfig[] = JSON.parse(jsonStr as string);
        d().updateAgents(agentsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agents:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.AGENT_OPERATION_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handleAgentOperationResult(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent operation result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.AGENT_IMPORT_PREVIEW, (jsonStr) => {
      try {
        const previewData = JSON.parse(jsonStr as string);
        if (!Array.isArray(previewData?.items) || typeof previewData?.summary !== 'object') {
          console.error('[SettingsView] Invalid agent import preview data structure');
          return;
        }
        d().handleAgentImportPreviewResult(previewData);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import preview result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.AGENT_IMPORT_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handleAgentImportResult(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import result:', error);
      }
    }));

    // Prompt library callbacks (legacy support - now primarily handled by PromptSection)
    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_LIST, (jsonStr) => {
      try {
        const promptsList: PromptConfig[] = JSON.parse(jsonStr as string);
        d().updatePrompts?.(promptsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompts:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_OPERATION_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handlePromptOperationResult?.(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt operation result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_IMPORT_PREVIEW, (jsonStr) => {
      try {
        const previewData = JSON.parse(jsonStr as string);
        if (!Array.isArray(previewData?.items) || typeof previewData?.summary !== 'object') {
          console.error('[SettingsView] Invalid prompt import preview data structure');
          return;
        }
        d().handlePromptImportPreviewResult?.(previewData);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt import preview result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.PROMPT_IMPORT_RESULT, (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handlePromptImportResult?.(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt import result:', error);
      }
    }));

    // Codex provider callbacks - subscribe via the registry.
    const unsubscribeCodexProviders = subscribeCodexProviderList((jsonStr: string) => {
      try {
        const providersList: CodexProviderConfig[] = JSON.parse(jsonStr);
        d().updateCodexProviders(providersList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex providers:', error);
        d().setCodexLoading(false);
      }
    });

    const unsubscribeActiveCodexProvider = subscribeActiveCodexProvider((jsonStr: string) => {
      try {
        const activeProvider: CodexProviderConfig = JSON.parse(jsonStr);
        if (activeProvider) {
          d().updateActiveCodexProvider(activeProvider);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse active Codex provider:', error);
      }
    });

    unsubs.push(subscribeEvent(DOWNSTREAM.PROVIDER_CODEX_CONFIG, (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().updateCurrentCodexConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex config:', error);
        d().setCodexConfigLoading(false);
      }
    }));

    // OpenCode provider callbacks - subscribe via the registry.
    const unsubscribeOpenCodeProviders = subscribeOpenCodeProviderList((jsonStr: string) => {
      try {
        const providersList: OpenCodeProviderConfig[] = JSON.parse(jsonStr);
        d().updateOpenCodeProviders(providersList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse OpenCode providers:', error);
        d().setOpenCodeLoading(false);
      }
    });

    const unsubscribeActiveOpenCodeProvider = subscribeActiveOpenCodeProvider((jsonStr: string) => {
      try {
        const activeProvider: OpenCodeProviderConfig = JSON.parse(jsonStr);
        if (activeProvider) {
          d().updateActiveOpenCodeProvider(activeProvider);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse active OpenCode provider:', error);
      }
    });

    unsubs.push(subscribeEvent(DOWNSTREAM.PROVIDER_OPENCODE_CONFIG, (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().updateCurrentOpenCodeConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse OpenCode config:', error);
        d().setOpenCodeConfigLoading(false);
      }
    }));

    // Initial data loading
    d().loadProviders();
    d().loadCodexProviders();
    d().loadOpenCodeProviders();
    d().loadAgents();
    // Note: loadPrompts is now handled by PromptSection component
    d().loadPrompts?.();
    sendAction(UPSTREAM.GET_NODE_PATH);
    sendAction(UPSTREAM.GET_WORKING_DIRECTORY);
    sendAction(UPSTREAM.GET_EDITOR_FONT_CONFIG);
    sendAction(UPSTREAM.GET_UI_FONT_CONFIG);
    sendAction(UPSTREAM.GET_STREAMING_ENABLED);
    sendAction(UPSTREAM.GET_CODEX_SANDBOX_MODE);
    sendAction(UPSTREAM.GET_COMMIT_PROMPT);
    sendAction(UPSTREAM.GET_COMMIT_AI_CONFIG);
    sendAction(UPSTREAM.GET_PROMPT_ENHANCER_CONFIG);
    sendAction(UPSTREAM.GET_COMMIT_GENERATION_ENABLED);
    sendAction(UPSTREAM.GET_AI_TITLE_GENERATION_ENABLED);
    sendAction(UPSTREAM.GET_STATUS_BAR_WIDGET_ENABLED);
    sendAction(UPSTREAM.GET_TASK_COMPLETION_NOTIFICATION_ENABLED);
    sendAction(UPSTREAM.GET_INVOCATION_MODE);
    sendAction(UPSTREAM.GET_PERMISSION_DIALOG_TIMEOUT);
    sendAction(UPSTREAM.GET_CLAUDE_CLI_PATH);

    return () => {
      d().cleanupAgentsTimeout();
      d().cleanupPromptsTimeout?.();

      unsubs.forEach((u) => u());
      unsubscribeProviders();
      unsubscribeActiveProvider();
      unsubscribeCodexProviders();
      unsubscribeActiveCodexProvider();
      unsubscribeOpenCodeProviders();
      unsubscribeActiveOpenCodeProvider();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t]);
}
