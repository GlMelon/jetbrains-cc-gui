// hooks/useSettingsBasicActions.ts
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
import { useCallback, useEffect, useState } from 'react';
import type { UiFontConfig, CodeFontConfig } from '../../../types/uiFontConfig';
export type { UiFontConfig, CodeFontConfig } from '../../../types/uiFontConfig';
import type { CommitAiConfig, CommitAiProvider } from '../../../types/aiFeatureConfig';
import { DEFAULT_COMMIT_AI_CONFIG } from '../../../types/aiFeatureConfig';
import type { PromptEnhancerConfig, PromptEnhancerProvider } from '../../../types/promptEnhancer';
import { DEFAULT_PROMPT_ENHANCER_CONFIG } from '../../../types/promptEnhancer';
import {
  clampPermissionDialogTimeoutSeconds,
  DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
} from '../../../utils/permissionDialogTimeout';
import {
  getSkipNewSessionConfirm,
  SKIP_NEW_SESSION_CONFIRM_EVENT,
  type SkipNewSessionConfirmChangedDetail,
} from '../../../utils/skipNewSessionConfirm';

export function useSettingsBasicActions({
  streamingEnabledProp,
  onStreamingEnabledChangeProp,
  showThinkingEnabledProp,
  onShowThinkingEnabledChangeProp,
  sendShortcutProp,
  onSendShortcutChangeProp,
  autoOpenFileEnabledProp,
  onAutoOpenFileEnabledChangeProp,
  detailedOutputEnabledProp,
  onDetailedOutputEnabledChangeProp,
  permissionDialogTimeoutSecondsProp,
  onPermissionDialogTimeoutChangeProp,
}: {
  streamingEnabledProp?: boolean;
  onStreamingEnabledChangeProp?: (enabled: boolean) => void;
  showThinkingEnabledProp?: boolean;
  onShowThinkingEnabledChangeProp?: (enabled: boolean) => void;
  sendShortcutProp?: 'enter' | 'cmdEnter';
  onSendShortcutChangeProp?: (shortcut: 'enter' | 'cmdEnter') => void;
  autoOpenFileEnabledProp?: boolean;
  onAutoOpenFileEnabledChangeProp?: (enabled: boolean) => void;
  detailedOutputEnabledProp?: boolean;
  onDetailedOutputEnabledChangeProp?: (enabled: boolean) => void;
  permissionDialogTimeoutSecondsProp?: number;
  onPermissionDialogTimeoutChangeProp?: (seconds: number) => void;
}) {
  // Node.js path
  const [nodePath, setNodePath] = useState('');
  const [nodeVersion, setNodeVersion] = useState<string | null>(null);
  const [minNodeVersion, setMinNodeVersion] = useState(18);
  const [savingNodePath, setSavingNodePath] = useState(false);

  // Working directory configuration
  const [workingDirectory, setWorkingDirectory] = useState('');
  const [savingWorkingDirectory, setSavingWorkingDirectory] = useState(false);

  // IDEA editor font configuration (read-only display)
  const [editorFontConfig, setEditorFontConfig] = useState<
    | {
        fontFamily: string;
        fontSize: number;
        lineSpacing: number;
      }
    | undefined
  >();
  const [uiFontConfig, setUiFontConfig] = useState<UiFontConfig | undefined>();
  const [codeFontConfig, setCodeFontConfig] = useState<CodeFontConfig | undefined>();

  // Streaming configuration - prefer props, fallback to local state
  const [localStreamingEnabled, setLocalStreamingEnabled] = useState<boolean>(false);
  const streamingEnabled = streamingEnabledProp ?? localStreamingEnabled;

  // Show thinking configuration - prefer props, fallback to local state.
  // 显示思考区开关(跨所有 provider/调用模式):off 时后端 TurnPushGate 丢弃 thinking delta。
  const [localShowThinkingEnabled, setLocalShowThinkingEnabled] = useState<boolean>(false);
  const showThinkingEnabled = showThinkingEnabledProp ?? localShowThinkingEnabled;

  const [codexSandboxMode, setCodexSandboxMode] = useState<
    'workspace-write' | 'danger-full-access'
  >('danger-full-access');

  // Send shortcut configuration - prefer props, fallback to local state
  const [localSendShortcut, setLocalSendShortcut] = useState<'enter' | 'cmdEnter'>('enter');
  const sendShortcut = sendShortcutProp ?? localSendShortcut;

  // Auto open file configuration - prefer props, fallback to local state
  const [localAutoOpenFileEnabled, setLocalAutoOpenFileEnabled] = useState<boolean>(false);
  const autoOpenFileEnabled = autoOpenFileEnabledProp ?? localAutoOpenFileEnabled;
  const detailedOutputEnabled = detailedOutputEnabledProp ?? false;

  // Commit AI prompt configuration
  const [commitPrompt, setCommitPrompt] = useState('');
  const [savingCommitPrompt, setSavingCommitPrompt] = useState(false);

  // Project-level commit AI prompt configuration
  const [projectCommitPrompt, setProjectCommitPrompt] = useState('');
  const [savingProjectCommitPrompt, setSavingProjectCommitPrompt] = useState(false);

  // Diff expanded by default configuration (localStorage-only)
  const [diffExpandedByDefault, setDiffExpandedByDefault] = useState<boolean>(() => {
    try {
      return localStorage.getItem('diffExpandedByDefault') === 'true';
    } catch {
      return false;
    }
  });

  // History completion toggle configuration
  const [historyCompletionEnabled, setHistoryCompletionEnabled] = useState<boolean>(() => {
    const saved = localStorage.getItem('historyCompletionEnabled');
    return saved !== 'false'; // Enabled by default
  });

  // "Skip new-session confirm dialog" preference (localStorage-only, default: false).
  // Synced bidirectionally with the dialog checkbox via CustomEvent so toggling
  // either surface (dialog or settings page) updates the other immediately.
  const [skipNewSessionConfirm, setSkipNewSessionConfirm] = useState<boolean>(() =>
    getSkipNewSessionConfirm(),
  );
  useEffect(() => {
    const handler = (event: Event) => {
      const custom = event as CustomEvent<SkipNewSessionConfirmChangedDetail>;
      if (custom.detail && typeof custom.detail.enabled === 'boolean') {
        setSkipNewSessionConfirm(custom.detail.enabled);
      }
    };
    window.addEventListener(SKIP_NEW_SESSION_CONFIRM_EVENT, handler);
    return () => window.removeEventListener(SKIP_NEW_SESSION_CONFIRM_EVENT, handler);
  }, []);

  // AI commit generation toggle (default: true)
  const [commitGenerationEnabled, setCommitGenerationEnabled] = useState<boolean>(true);

  // MCP Gateway acceleration toggle (default: true; off falls back to direct MCP)
  const [mcpGatewayEnabled, setMcpGatewayEnabled] = useState<boolean>(true);

  // CLI persistent sessions toggle (default: true; off falls back to one-shot per message)
  const [cliPersistentEnabled, setCliPersistentEnabled] = useState<boolean>(true);

  // AI session title generation toggle (default: true)
  const [aiTitleGenerationEnabled, setAiTitleGenerationEnabled] = useState<boolean>(true);

  // Status bar widget toggle (default: true)
  const [statusBarWidgetEnabled, setStatusBarWidgetEnabled] = useState<boolean>(true);

  // Task completion notification toggle (default: false, opt-in feature)
  const [taskCompletionNotificationEnabled, setTaskCompletionNotificationEnabled] =
    useState<boolean>(false);

  // AskUserQuestion reminder notification toggle (default: false, opt-in feature)
  const [askUserQuestionNotificationEnabled, setAskUserQuestionNotificationEnabled] =
    useState<boolean>(false);

  // Permission dialog timeout — owned by App.tsx; we treat the prop as authoritative.
  // We intentionally do NOT keep a local copy: it would be dead state because the
  // prop is always provided in production, and a divergent local copy could be read
  // by accident in future refactors.
  const permissionDialogTimeoutSeconds =
    permissionDialogTimeoutSecondsProp ?? DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS;

  const [commitAiConfig, setCommitAiConfig] = useState<CommitAiConfig>(DEFAULT_COMMIT_AI_CONFIG);
  const [promptEnhancerConfig, setPromptEnhancerConfig] = useState<PromptEnhancerConfig>(
    DEFAULT_PROMPT_ENHANCER_CONFIG,
  );

  // Diff expanded by default handler
  useEffect(() => {
    try {
      if (diffExpandedByDefault) {
        localStorage.setItem('diffExpandedByDefault', 'true');
      } else {
        localStorage.removeItem('diffExpandedByDefault');
      }
    } catch {
      /* ignore storage errors */
    }
  }, [diffExpandedByDefault]);

  const handleSaveNodePath = useCallback(() => {
    setSavingNodePath(true);
    const payload = { path: (nodePath || '').trim() };
    sendAction(UPSTREAM.SET_NODE_PATH, JSON.stringify(payload));
  }, [nodePath]);

  const handleSaveWorkingDirectory = useCallback(() => {
    setSavingWorkingDirectory(true);
    const payload = { customWorkingDir: (workingDirectory || '').trim() };
    sendAction(UPSTREAM.SET_WORKING_DIRECTORY, JSON.stringify(payload));
  }, [workingDirectory]);

  const handleUiFontSelectionChange = useCallback(
    (selection: string) => {
      if (selection === 'followEditor') {
        sendAction(UPSTREAM.SET_UI_FONT_CONFIG, JSON.stringify({ mode: 'followEditor' }));
        return;
      }

      if (selection === 'customFile' && uiFontConfig?.customFontPath) {
        sendAction(
          UPSTREAM.SET_UI_FONT_CONFIG,
          JSON.stringify({
            mode: 'customFile',
            customFontPath: uiFontConfig.customFontPath,
          }),
        );
      }
    },
    [uiFontConfig?.customFontPath],
  );

  const handleSaveUiFontCustomPath = useCallback((path: string) => {
    sendAction(
      UPSTREAM.SET_UI_FONT_CONFIG,
      JSON.stringify({
        mode: 'customFile',
        customFontPath: path,
      }),
    );
  }, []);

  const handleBrowseUiFontFile = useCallback(() => {
    sendAction(UPSTREAM.BROWSE_UI_FONT_FILE);
  }, []);

  const handleCodeFontSelectionChange = useCallback(
    (selection: string) => {
      if (selection === 'followEditor') {
        sendAction(UPSTREAM.SET_CODE_FONT_CONFIG, JSON.stringify({ mode: 'followEditor' }));
        return;
      }

      if (selection === 'customFile' && codeFontConfig?.customFontPath) {
        sendAction(
          UPSTREAM.SET_CODE_FONT_CONFIG,
          JSON.stringify({
            mode: 'customFile',
            customFontPath: codeFontConfig.customFontPath,
          }),
        );
      }
    },
    [codeFontConfig?.customFontPath],
  );

  const handleSaveCodeFontCustomPath = useCallback((path: string) => {
    sendAction(
      UPSTREAM.SET_CODE_FONT_CONFIG,
      JSON.stringify({
        mode: 'customFile',
        customFontPath: path,
      }),
    );
  }, []);

  const handleBrowseCodeFontFile = useCallback(() => {
    sendAction(UPSTREAM.BROWSE_CODE_FONT_FILE);
  }, []);

  // Streaming toggle change handler
  const handleStreamingEnabledChange = useCallback(
    (enabled: boolean) => {
      // If prop callback is provided (from App.tsx), use it for centralized state management
      if (onStreamingEnabledChangeProp) {
        onStreamingEnabledChangeProp(enabled);
      } else {
        // Fallback to local state if no prop callback provided
        setLocalStreamingEnabled(enabled);
        const payload = { streamingEnabled: enabled };
        sendAction(UPSTREAM.SET_STREAMING_ENABLED, JSON.stringify(payload));
      }
    },
    [onStreamingEnabledChangeProp],
  );

  // Show thinking toggle change handler - 显示思考区开关(跨所有 provider/调用模式)
  const handleShowThinkingEnabledChange = useCallback(
    (enabled: boolean) => {
      if (onShowThinkingEnabledChangeProp) {
        onShowThinkingEnabledChangeProp(enabled);
      } else {
        setLocalShowThinkingEnabled(enabled);
        const payload = { showThinkingEnabled: enabled };
        sendAction(UPSTREAM.SET_SHOW_THINKING_ENABLED, JSON.stringify(payload));
      }
    },
    [onShowThinkingEnabledChangeProp],
  );

  const handleCodexSandboxModeChange = useCallback(
    (mode: 'workspace-write' | 'danger-full-access') => {
      setCodexSandboxMode(mode);
      const payload = { sandboxMode: mode };
      sendAction(UPSTREAM.SET_CODEX_SANDBOX_MODE, JSON.stringify(payload));
    },
    [],
  );

  // Send shortcut change handler
  const handleSendShortcutChange = useCallback(
    (shortcut: 'enter' | 'cmdEnter') => {
      // If prop callback is provided (from App.tsx), use it for centralized state management
      if (onSendShortcutChangeProp) {
        onSendShortcutChangeProp(shortcut);
      } else {
        // Fallback to local state if no prop callback provided
        setLocalSendShortcut(shortcut);
        const payload = { sendShortcut: shortcut };
        sendAction(UPSTREAM.SET_SEND_SHORTCUT, JSON.stringify(payload));
      }
    },
    [onSendShortcutChangeProp],
  );

  // Auto open file toggle change handler
  const handleAutoOpenFileEnabledChange = useCallback(
    (enabled: boolean) => {
      // If prop callback is provided (from App.tsx), use it for centralized state management
      if (onAutoOpenFileEnabledChangeProp) {
        onAutoOpenFileEnabledChangeProp(enabled);
      } else {
        // Fallback to local state if no prop callback provided
        setLocalAutoOpenFileEnabled(enabled);
        const payload = { autoOpenFileEnabled: enabled };
        sendAction(UPSTREAM.SET_AUTO_OPEN_FILE_ENABLED, JSON.stringify(payload));
      }
    },
    [onAutoOpenFileEnabledChangeProp],
  );

  // AI commit generation toggle change handler
  const handleCommitGenerationEnabledChange = useCallback((enabled: boolean) => {
    setCommitGenerationEnabled(enabled);
    const payload = { commitGenerationEnabled: enabled };
    sendAction(UPSTREAM.SET_COMMIT_GENERATION_ENABLED, JSON.stringify(payload));
  }, []);

  // MCP Gateway toggle change handler — 关闭停常驻进程回直连,开启后台预热(后端即时处理,无需 restart)
  const handleMcpGatewayEnabledChange = useCallback((enabled: boolean) => {
    setMcpGatewayEnabled(enabled);
    const payload = { mcpGatewayEnabled: enabled };
    sendAction(UPSTREAM.SET_MCP_GATEWAY_ENABLED, JSON.stringify(payload));
  }, []);

  // CLI persistent sessions toggle change handler — 关闭回收 IDLE 长驻进程回 one-shot,开启无需预热
  const handleCliPersistentEnabledChange = useCallback((enabled: boolean) => {
    setCliPersistentEnabled(enabled);
    const payload = { cliPersistentEnabled: enabled };
    sendAction(UPSTREAM.SET_CLI_PERSISTENT_ENABLED, JSON.stringify(payload));
  }, []);

  // AI session title generation toggle change handler
  const handleAiTitleGenerationEnabledChange = useCallback((enabled: boolean) => {
    setAiTitleGenerationEnabled(enabled);
    const payload = { aiTitleGenerationEnabled: enabled };
    sendAction(UPSTREAM.SET_AI_TITLE_GENERATION_ENABLED, JSON.stringify(payload));
  }, []);

  // Status bar widget toggle change handler
  const handleStatusBarWidgetEnabledChange = useCallback((enabled: boolean) => {
    setStatusBarWidgetEnabled(enabled);
    const payload = { statusBarWidgetEnabled: enabled };
    sendAction(UPSTREAM.SET_STATUS_BAR_WIDGET_ENABLED, JSON.stringify(payload));
  }, []);

  // Task completion notification toggle change handler
  const handleTaskCompletionNotificationEnabledChange = useCallback((enabled: boolean) => {
    setTaskCompletionNotificationEnabled(enabled);
    const payload = { taskCompletionNotificationEnabled: enabled };
    sendAction(UPSTREAM.SET_TASK_COMPLETION_NOTIFICATION_ENABLED, JSON.stringify(payload));
  }, []);

  // AskUserQuestion reminder notification toggle change handler
  const handleAskUserQuestionNotificationEnabledChange = useCallback((enabled: boolean) => {
    setAskUserQuestionNotificationEnabled(enabled);
    const payload = { askUserQuestionNotificationEnabled: enabled };
    sendAction(UPSTREAM.SET_ASK_USER_QUESTION_NOTIFICATION_ENABLED, JSON.stringify(payload));
  }, []);

  const handleDetailedOutputEnabledChange = useCallback(
    (enabled: boolean) => {
      onDetailedOutputEnabledChangeProp?.(enabled);
    },
    [onDetailedOutputEnabledChangeProp],
  );

  // Permission dialog timeout change handler
  const handlePermissionDialogTimeoutChange = useCallback(
    (seconds: number) => {
      const clamped = clampPermissionDialogTimeoutSeconds(seconds);
      // App.tsx owns the canonical state and provides the callback in production.
      onPermissionDialogTimeoutChangeProp?.(clamped);
      const payload = { permissionDialogTimeoutSeconds: clamped };
      sendAction(UPSTREAM.SET_PERMISSION_DIALOG_TIMEOUT, JSON.stringify(payload));
    },
    [onPermissionDialogTimeoutChangeProp],
  );

  const handleCommitAiProviderChange = useCallback(
    (provider: CommitAiProvider) => {
      const providerAvailable = commitAiConfig.availability[provider];
      const nextConfig: CommitAiConfig = {
        ...commitAiConfig,
        provider,
        effectiveProvider: providerAvailable ? provider : null,
        resolutionSource: providerAvailable ? 'manual' : 'unavailable',
      };
      setCommitAiConfig(nextConfig);
      sendAction(
        UPSTREAM.SET_COMMIT_AI_CONFIG,
        JSON.stringify({
          provider,
          models: nextConfig.models,
        }),
      );
    },
    [commitAiConfig],
  );

  const handleCommitAiModelChange = useCallback(
    (model: string) => {
      const activeProvider = commitAiConfig.provider ?? commitAiConfig.effectiveProvider ?? 'codex';
      const nextConfig: CommitAiConfig = {
        ...commitAiConfig,
        models: {
          ...commitAiConfig.models,
          [activeProvider]: model,
        },
      };
      setCommitAiConfig(nextConfig);
      sendAction(
        UPSTREAM.SET_COMMIT_AI_CONFIG,
        JSON.stringify({
          provider: commitAiConfig.provider,
          models: nextConfig.models,
        }),
      );
    },
    [commitAiConfig],
  );

  const handlePromptEnhancerProviderChange = useCallback(
    (provider: PromptEnhancerProvider) => {
      const providerAvailable = promptEnhancerConfig.availability[provider];
      const nextConfig: PromptEnhancerConfig = {
        ...promptEnhancerConfig,
        provider,
        effectiveProvider: providerAvailable ? provider : null,
        resolutionSource: providerAvailable ? 'manual' : 'unavailable',
      };
      setPromptEnhancerConfig(nextConfig);
      sendAction(
        UPSTREAM.SET_PROMPT_ENHANCER_CONFIG,
        JSON.stringify({
          provider,
          models: nextConfig.models,
        }),
      );
    },
    [promptEnhancerConfig],
  );

  const handlePromptEnhancerModelChange = useCallback(
    (model: string) => {
      const activeProvider =
        promptEnhancerConfig.provider ?? promptEnhancerConfig.effectiveProvider ?? 'claude';
      const nextConfig: PromptEnhancerConfig = {
        ...promptEnhancerConfig,
        models: {
          ...promptEnhancerConfig.models,
          [activeProvider]: model,
        },
      };
      setPromptEnhancerConfig(nextConfig);
      sendAction(
        UPSTREAM.SET_PROMPT_ENHANCER_CONFIG,
        JSON.stringify({
          provider: promptEnhancerConfig.provider,
          models: nextConfig.models,
        }),
      );
    },
    [promptEnhancerConfig],
  );

  // Commit AI prompt save handler
  const handleSaveCommitPrompt = useCallback(() => {
    setSavingCommitPrompt(true);
    const payload = { prompt: commitPrompt };
    sendAction(UPSTREAM.SET_COMMIT_PROMPT, JSON.stringify(payload));
  }, [commitPrompt]);

  // Project-level commit AI prompt save handler
  const handleSaveProjectCommitPrompt = useCallback(() => {
    setSavingProjectCommitPrompt(true);
    const payload = { prompt: projectCommitPrompt };
    sendAction(UPSTREAM.SET_PROJECT_COMMIT_PROMPT, JSON.stringify(payload));
  }, [projectCommitPrompt]);

  return {
    nodePath,
    setNodePath,
    nodeVersion,
    setNodeVersion,
    minNodeVersion,
    setMinNodeVersion,
    savingNodePath,
    setSavingNodePath,
    workingDirectory,
    setWorkingDirectory,
    savingWorkingDirectory,
    setSavingWorkingDirectory,
    editorFontConfig,
    setEditorFontConfig,
    uiFontConfig,
    setUiFontConfig,
    codeFontConfig,
    setCodeFontConfig,
    localStreamingEnabled,
    setLocalStreamingEnabled,
    streamingEnabled,
    localShowThinkingEnabled,
    setLocalShowThinkingEnabled,
    showThinkingEnabled,
    codexSandboxMode,
    setCodexSandboxMode,
    localSendShortcut,
    setLocalSendShortcut,
    sendShortcut,
    localAutoOpenFileEnabled,
    setLocalAutoOpenFileEnabled,
    autoOpenFileEnabled,
    commitPrompt,
    setCommitPrompt,
    savingCommitPrompt,
    setSavingCommitPrompt,
    diffExpandedByDefault,
    setDiffExpandedByDefault,
    historyCompletionEnabled,
    setHistoryCompletionEnabled,
    skipNewSessionConfirm,
    setSkipNewSessionConfirm,
    handleSaveNodePath,
    handleSaveWorkingDirectory,
    handleUiFontSelectionChange,
    handleSaveUiFontCustomPath,
    handleBrowseUiFontFile,
    handleCodeFontSelectionChange,
    handleSaveCodeFontCustomPath,
    handleBrowseCodeFontFile,
    handleStreamingEnabledChange,
    handleShowThinkingEnabledChange,
    handleCodexSandboxModeChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleSaveCommitPrompt,
    projectCommitPrompt,
    setProjectCommitPrompt,
    savingProjectCommitPrompt,
    setSavingProjectCommitPrompt,
    handleSaveProjectCommitPrompt,
    commitGenerationEnabled,
    setCommitGenerationEnabled,
    handleCommitGenerationEnabledChange,
    mcpGatewayEnabled,
    setMcpGatewayEnabled,
    handleMcpGatewayEnabledChange,
    cliPersistentEnabled,
    setCliPersistentEnabled,
    handleCliPersistentEnabledChange,
    aiTitleGenerationEnabled,
    setAiTitleGenerationEnabled,
    handleAiTitleGenerationEnabledChange,
    statusBarWidgetEnabled,
    setStatusBarWidgetEnabled,
    handleStatusBarWidgetEnabledChange,
    taskCompletionNotificationEnabled,
    setTaskCompletionNotificationEnabled,
    handleTaskCompletionNotificationEnabledChange,
    askUserQuestionNotificationEnabled,
    setAskUserQuestionNotificationEnabled,
    handleAskUserQuestionNotificationEnabledChange,
    detailedOutputEnabled,
    handleDetailedOutputEnabledChange,
    permissionDialogTimeoutSeconds,
    handlePermissionDialogTimeoutChange,
    commitAiConfig,
    setCommitAiConfig,
    handleCommitAiProviderChange,
    handleCommitAiModelChange,
    promptEnhancerConfig,
    setPromptEnhancerConfig,
    handlePromptEnhancerProviderChange,
    handlePromptEnhancerModelChange,
  };
}
