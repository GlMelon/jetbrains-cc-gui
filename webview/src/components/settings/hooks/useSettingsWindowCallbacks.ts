// hooks/useSettingsWindowCallbacks.ts
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { ProviderConfig, CodexProviderConfig } from '../../../types/provider';
import type { AgentConfig } from '../../../types/agent';
import type { PromptConfig } from '../../../types/prompt';
import type { CommitAiConfig } from '../../../types/aiFeatureConfig';
import type { UiFontConfig, CodeFontConfig } from './useSettingsBasicActions';
import type { PromptEnhancerConfig } from '../../../types/promptEnhancer';
import type { AlertType } from '../../AlertDialog';
import type { ToastMessage } from '../../Toast';
import {
  subscribeActiveCodexProvider,
  subscribeActiveProvider,
  subscribeCodexProviderList,
  subscribeProviderList,
} from '../../../utils/runtimeProviderCapabilities';
import { sendBridgeEvent } from '../../../utils/bridge';
import { bridgeHub, registerLegacyAlias } from '../../../bridge';

const sendToJava = (event: string, payload = '') => {
  sendBridgeEvent(event, payload);
};

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
  // AI feature toggle setters
  setCommitGenerationEnabled?: (enabled: boolean) => void;
  setAiTitleGenerationEnabled?: (enabled: boolean) => void;
  setStatusBarWidgetEnabled?: (enabled: boolean) => void;
  setTaskCompletionNotificationEnabled?: (enabled: boolean) => void;
  // Invocation mode setters
  setInvocationMode: (mode: 'sdk' | 'cli') => void;
  setCliPath: (path: string) => void;

  // Hook functions
  updateProviders: (providers: ProviderConfig[]) => void;
  updateActiveProvider: (provider: ProviderConfig) => void;
  loadProviders: () => void;
  loadCodexProviders: () => void;
  loadAgents: () => void;
  updateAgents: (agents: AgentConfig[]) => void;
  handleAgentOperationResult: (result: any) => void;
  handleAgentImportPreviewResult: (previewData: any) => void;
  handleAgentImportResult: (result: any) => void;
  updateCodexProviders: (providers: CodexProviderConfig[]) => void;
  updateActiveCodexProvider: (provider: CodexProviderConfig) => void;
  updateCurrentCodexConfig: (config: any) => void;
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
    registerLegacyAlias('showError', 'toast.error');
    registerLegacyAlias('showSwitchSuccess', 'toast.switch_success');
    registerLegacyAlias('showSuccess', 'toast.success');
    registerLegacyAlias('showSuccessI18n', 'toast.success_i18n');
    registerLegacyAlias('updateNodePath', 'node.path');
    registerLegacyAlias('updateWorkingDirectory', 'config.working_directory');
    registerLegacyAlias('onEditorFontConfigReceived', 'font.editor_config_received');
    registerLegacyAlias('onUiFontConfigReceived', 'font.ui_config_received');
    registerLegacyAlias('onIdeThemeReceived', 'theme.received');
    registerLegacyAlias('updateCodexSandboxMode', 'config.codex_sandbox_mode');
    registerLegacyAlias('updateCommitPrompt', 'config.commit_prompt');
    registerLegacyAlias('updatePromptEnhancerConfig', 'config.prompt_enhancer');
    registerLegacyAlias('updateCommitAiConfig', 'config.commit_ai');
    registerLegacyAlias('updateProjectCommitPrompt', 'config.project_commit_prompt');
    registerLegacyAlias('updateCommitGenerationEnabled', 'config.commit_generation');
    registerLegacyAlias('updateAiTitleGenerationEnabled', 'config.ai_title_generation');
    registerLegacyAlias('updateStatusBarWidgetEnabled', 'config.status_bar_widget');
    registerLegacyAlias('updateTaskCompletionNotificationEnabled', 'config.task_completion_notification');
    registerLegacyAlias('updateInvocationMode', 'config.invocation_mode');
    registerLegacyAlias('updateAgents', 'agent.list');
    registerLegacyAlias('agentOperationResult', 'agent.operation_result');
    registerLegacyAlias('agentImportPreviewResult', 'agent.import_preview');
    registerLegacyAlias('agentImportResult', 'agent.import_result');
    registerLegacyAlias('updatePrompts', 'prompt.list');
    registerLegacyAlias('promptOperationResult', 'prompt.operation_result');
    registerLegacyAlias('promptImportPreviewResult', 'prompt.import_preview');
    registerLegacyAlias('promptImportResult', 'prompt.import_result');
    registerLegacyAlias('updateCurrentCodexConfig', 'provider.codex_config');

    const unsubs: Array<() => void> = [];

    unsubs.push(bridgeHub.subscribe('toast.error', (message) => {
      d().showAlert('error', t('toast.operationFailed'), message as string);
      d().setLoading(false);
      d().setSavingNodePath(false);
      d().setSavingWorkingDirectory(false);
      d().setSavingCommitPrompt(false);
      d().setSavingProjectCommitPrompt(false);
    }));

    unsubs.push(bridgeHub.subscribe('toast.switch_success', (message) => {
      d().showAlert('success', t('toast.switchSuccess'), message as string);
    }));

    unsubs.push(bridgeHub.subscribe('node.path', (jsonStr) => {
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

    unsubs.push(bridgeHub.subscribe('config.working_directory', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setWorkingDirectory(data.customWorkingDir || '');
        d().setSavingWorkingDirectory(false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse working directory:', error);
        d().setSavingWorkingDirectory(false);
      }
    }));

    unsubs.push(bridgeHub.subscribe('toast.success', (message) => {
      d().showAlert('success', t('toast.operationSuccess'), message as string);
      d().setSavingNodePath(false);
      d().setSavingWorkingDirectory(false);
    }));

    unsubs.push(bridgeHub.subscribe('toast.success_i18n', (i18nKey) => {
      const message = t(i18nKey as string);
      d().addToast(message, 'success');
    }));

    unsubs.push(bridgeHub.subscribe('font.editor_config_received', (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().setEditorFontConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse editor font config:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('font.ui_config_received', (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().setUiFontConfig(config);
        window.applyUiFontConfig?.(config);
      } catch {
        // Silently ignore malformed UI font config from backend
      }
    }));

    // IDE theme callback
    unsubs.push(bridgeHub.subscribe('theme.received', (jsonStr) => {
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
      unsubs.push(bridgeHub.subscribe('setting.streaming_enabled', (jsonStr) => {
        try {
          const data = JSON.parse(jsonStr as string);
          d().setLocalStreamingEnabled(data.streamingEnabled ?? true);
        } catch (error) {
          console.error('[SettingsView] Failed to parse streaming config:', error);
        }
      }));
    }

    // Codex sandbox mode callback
    unsubs.push(bridgeHub.subscribe('config.codex_sandbox_mode', (jsonStr) => {
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
      unsubs.push(bridgeHub.subscribe('setting.send_shortcut', (jsonStr) => {
        try {
          const data = JSON.parse(jsonStr as string);
          d().setLocalSendShortcut(data.sendShortcut ?? 'enter');
        } catch (error) {
          console.error('[SettingsView] Failed to parse send shortcut config:', error);
        }
      }));
    }

    // Commit AI prompt callback
    unsubs.push(bridgeHub.subscribe('config.commit_prompt', (jsonStr) => {
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

    unsubs.push(bridgeHub.subscribe('config.prompt_enhancer', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setPromptEnhancerConfig(data);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt enhancer config:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('config.commit_ai', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setCommitAiConfig(data);
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit AI config:', error);
      }
    }));

    // Project-level commit AI prompt callback
    unsubs.push(bridgeHub.subscribe('config.project_commit_prompt', (jsonStr) => {
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
    unsubs.push(bridgeHub.subscribe('config.commit_generation', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setCommitGenerationEnabled?.(data.commitGenerationEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit generation config:', error);
      }
    }));

    // AI session title generation config callback
    unsubs.push(bridgeHub.subscribe('config.ai_title_generation', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setAiTitleGenerationEnabled?.(data.aiTitleGenerationEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse AI title generation config:', error);
      }
    }));

    // Status bar widget config callback
    unsubs.push(bridgeHub.subscribe('config.status_bar_widget', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setStatusBarWidgetEnabled?.(data.statusBarWidgetEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse status bar widget config:', error);
      }
    }));

    // Task completion notification config callback (opt-in feature, default false)
    unsubs.push(bridgeHub.subscribe('config.task_completion_notification', (jsonStr) => {
      try {
        const data = JSON.parse(jsonStr as string);
        d().setTaskCompletionNotificationEnabled?.(data.taskCompletionNotificationEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse task completion notification config:', error);
      }
    }));

    // Invocation mode callback
    unsubs.push(bridgeHub.subscribe('config.invocation_mode', (jsonStr) => {
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
    unsubs.push(bridgeHub.subscribe('agent.list', (jsonStr) => {
      try {
        const agentsList: AgentConfig[] = JSON.parse(jsonStr as string);
        d().updateAgents(agentsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agents:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('agent.operation_result', (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handleAgentOperationResult(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent operation result:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('agent.import_preview', (jsonStr) => {
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

    unsubs.push(bridgeHub.subscribe('agent.import_result', (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handleAgentImportResult(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import result:', error);
      }
    }));

    // Prompt library callbacks (legacy support - now primarily handled by PromptSection)
    unsubs.push(bridgeHub.subscribe('prompt.list', (jsonStr) => {
      try {
        const promptsList: PromptConfig[] = JSON.parse(jsonStr as string);
        d().updatePrompts?.(promptsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompts:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('prompt.operation_result', (jsonStr) => {
      try {
        const result = JSON.parse(jsonStr as string);
        d().handlePromptOperationResult?.(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt operation result:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('prompt.import_preview', (jsonStr) => {
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

    unsubs.push(bridgeHub.subscribe('prompt.import_result', (jsonStr) => {
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

    unsubs.push(bridgeHub.subscribe('provider.codex_config', (jsonStr) => {
      try {
        const config = JSON.parse(jsonStr as string);
        d().updateCurrentCodexConfig(config);
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex config:', error);
        d().setCodexConfigLoading(false);
      }
    }));

    // Initial data loading
    d().loadProviders();
    d().loadCodexProviders();
    d().loadAgents();
    // Note: loadPrompts is now handled by PromptSection component
    d().loadPrompts?.();
    sendToJava('get_node_path');
    sendToJava('get_working_directory');
    sendToJava('get_editor_font_config');
    sendToJava('get_ui_font_config');
    sendToJava('get_streaming_enabled');
    sendToJava('get_codex_sandbox_mode');
    sendToJava('get_commit_prompt');
    sendToJava('get_commit_ai_config');
    sendToJava('get_prompt_enhancer_config');
    sendToJava('get_commit_generation_enabled');
    sendToJava('get_ai_title_generation_enabled');
    sendToJava('get_status_bar_widget_enabled');
    sendToJava('get_task_completion_notification_enabled');
    sendToJava('get_invocation_mode');
    sendToJava('get_permission_dialog_timeout');

    return () => {
      d().cleanupAgentsTimeout();
      d().cleanupPromptsTimeout?.();

      unsubs.forEach((u) => u());
      unsubscribeProviders();
      unsubscribeActiveProvider();
      unsubscribeCodexProviders();
      unsubscribeActiveCodexProvider();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t]);
}
