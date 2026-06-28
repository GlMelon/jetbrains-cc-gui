import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { DOWNSTREAM } from '../generated/protocol';
import { subscribeEvent } from '../bridge/typed';
import type { PermissionMode } from '../components/ChatInputBox/types';
import { DEFAULT_CONTEXT_WINDOW, strip1MContextSuffix } from '../components/ChatInputBox/types';
import { isSpecialProviderId } from '../types/provider';
import { useClaudeProvider } from './providers/useClaudeProvider';
import { useCodexProvider } from './providers/useCodexProvider';
import { useOpenCodeProvider } from './providers/useOpenCodeProvider';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';
import { getModelsForProvider, normalizeProvider, subscribeModelRegistry } from '../utils/modelRegistry';
import type { ViewMode } from '../types';

// D3:ViewMode 真相源在 types/index.ts,此处 re-export 保持 hooks/index 与 useMessageSender 下游 import 兼容
export type {ViewMode};

export interface UseModelProviderStateOptions {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: TFunction;
}

/**
 * Orchestrates provider/model/permission state. Composes four single-purpose
 * sub-hooks (Claude / Codex / usage tracking / provider settings) plus a
 * persistence hook, then wires the cross-slice state (currentProvider +
 * permissionMode) and the cross-provider handlers (mode/model/provider switch,
 * long-context toggle, always-thinking toggle).
 *
 * The flat return shape is preserved as the public API: callers (App,
 * ChatScreen, AppDialogs, useMessageSender) destructure individual fields.
 *
 * `currentProviderRef` is exposed for window callbacks registered with stable
 * identity that must read the current provider when fired by the JCEF bridge.
 * The ref is updated via render-time assignment (no useEffect mirror).
 */
export function useModelProviderState({ addToast, t }: UseModelProviderStateOptions) {
  // ── Cross-slice state owned by the orchestrator ──
  const [currentProvider, setCurrentProvider] = useState('claude');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('default');

  // External-facing ref so window callbacks can read the latest provider
  // without re-binding. Render-time assignment avoids the useRef + useEffect
  // mirror anti-pattern (rule 5.15).
  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  // ── Provider-specific sub-hooks ──
  const claude = useClaudeProvider();
  const codex = useCodexProvider();
  const opencode = useOpenCodeProvider();
  const { isSdkInstalled, ...usage } = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });

  const {
    selectedClaudeModel, setSelectedClaudeModel,
    claudePermissionMode, setClaudePermissionMode,
    longContextEnabled, setLongContextEnabled,
    setClaudeSettingsAlwaysThinkingEnabled,
  } = claude;
  const {
    selectedCodexModel, setSelectedCodexModel,
    codexPermissionMode, setCodexPermissionMode,
    reasoningEffort, setReasoningEffort,
    codexFastMode, setCodexFastMode,
  } = codex;
  const { selectedOpenCodeModel, setSelectedOpenCodeModel } = opencode;

  // ── Persistence: load on mount + save on change ──
  useModelStatePersistence({
    setCurrentProvider,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    setCodexFastMode,
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    selectedOpenCodeModel,
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    reasoningEffort,
    codexFastMode,
  });

  // ── Computed values ──
  const selectedModel = currentProvider === 'codex' ? selectedCodexModel
    : currentProvider === 'opencode' ? selectedOpenCodeModel
    : selectedClaudeModel;
  const currentSdkInstalled = useMemo(
    () => isSdkInstalled(currentProvider),
    [isSdkInstalled, currentProvider],
  );

  const [modelRegistryVersion, setModelRegistryVersion] = useState(0);
  useEffect(() => subscribeModelRegistry(() => setModelRegistryVersion((version) => version + 1)), []);

  useEffect(() => subscribeEvent(DOWNSTREAM.MODEL_SELECTION, (json) => {
    try {
      const data = typeof json === 'string' ? JSON.parse(json) : json;
      if (!data || typeof data !== 'object') {
        return;
      }
      const selection = data as {
        provider?: string;
        selectedModel?: string;
        effectiveContextWindow?: number;
        supportsLongContext?: boolean;
      };
      const selected = strip1MContextSuffix(selection.selectedModel);
      if (!selected) {
        return;
      }
      const provider = normalizeProvider(selection.provider);
      setCurrentProvider(provider);
      if (provider === 'codex') {
        setSelectedCodexModel(selected);
        return;
      }
      if (provider === 'opencode') {
        setSelectedOpenCodeModel(selected);
        return;
      }
      setSelectedClaudeModel(selected);
      // A4:longContext 直接读后端权威布尔 supportsLongContext(见 ModelProviderHandler),不再前端按 effectiveContextWindow 数值推断。
      setLongContextEnabled(selection.supportsLongContext === true);
    } catch {
      // Ignore malformed backend selection events; existing state remains authoritative for display.
    }
  }), [setLongContextEnabled, setSelectedClaudeModel, setSelectedCodexModel, setSelectedOpenCodeModel]);

  // ── Cross-provider handlers ──
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    if (currentProvider === 'codex') {
      setPermissionMode(mode);
      setCodexPermissionMode(mode);
        sendAction(UPSTREAM.SET_SESSION_MODE, mode);
      return;
    }
    setPermissionMode(mode);
    setClaudePermissionMode(mode);
      sendAction(UPSTREAM.SET_SESSION_MODE, mode);
  }, [currentProvider, setCodexPermissionMode, setClaudePermissionMode]);

    const handleModelSelect = useCallback((modelId: string, contextWindow?: number) => {
    if (currentProvider === 'claude') {
      const strippedModelId = strip1MContextSuffix(modelId);
      const registryModels = getModelsForProvider('claude');
      // A3:不再前端归一化,保留 stripped 原值;registry 是否命中不影响 state,由后端 session 下发纠正。
      setSelectedClaudeModel(strippedModelId);
      // A4:supports1M 读 registry item.supports1MContext(后端权威),取代前端字符串推断。
      const supports1M = registryModels.find((model) => model.id === strippedModelId)?.supports1MContext ?? false;

      // Auto-reset: disable longContext if new model doesn't support 1M
      if (longContextEnabled && !supports1M) {
        setLongContextEnabled(false);
      }

      // 仅发送「意图」(模型 ID + 长上下文开关);effectiveContextWindow 由后端权威计算,
      // 并通过 MODEL_SELECTION 下行回推(见上方订阅)。前端不再硬编码 1M/200k 计算。
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: strippedModelId,
        longContextEnabled: longContextEnabled && supports1M,
      }));
    } else if (currentProvider === 'codex') {
      setSelectedCodexModel(modelId);
        const registryModels = getModelsForProvider('codex');
        const modelInfo = registryModels.find((model) => model.id === strip1MContextSuffix(modelId));
        const effectiveContextWindow = contextWindow ?? modelInfo?.contextWindow;
        const payload = effectiveContextWindow
            ? JSON.stringify({model: modelId, contextWindow: effectiveContextWindow})
            : modelId;
        sendAction(UPSTREAM.SET_SESSION_MODEL, payload);
    } else if (currentProvider === 'opencode') {
      setSelectedOpenCodeModel(modelId);
      const registryModels = getModelsForProvider('opencode');
      const modelInfo = registryModels.find((model) => model.id === strip1MContextSuffix(modelId));
      const effectiveContextWindow = contextWindow ?? modelInfo?.contextWindow;
      const payload = effectiveContextWindow
        ? JSON.stringify({model: modelId, contextWindow: effectiveContextWindow})
        : modelId;
      sendAction(UPSTREAM.SET_SESSION_MODEL, payload);
    }
  }, [currentProvider, longContextEnabled, setSelectedClaudeModel, setSelectedCodexModel, setSelectedOpenCodeModel]);

    const handleProviderSelect = useCallback((providerId: string, contextWindow?: number) => {
    setCurrentProvider(providerId);
      sendAction(UPSTREAM.SET_SESSION_PROVIDER, providerId);

    const modeToSet: PermissionMode = providerId === 'codex'
      ? codexPermissionMode
      : providerId === 'opencode'
        ? 'default'
        : claudePermissionMode;
    setPermissionMode(modeToSet);
      sendAction(UPSTREAM.SET_SESSION_MODE, modeToSet);

    const newModel = providerId === 'codex'
      ? selectedCodexModel
      : providerId === 'opencode'
        ? selectedOpenCodeModel
        : selectedClaudeModel;  // Clean ID, no [1m]

    const newProviderModels = getModelsForProvider(providerId);

    // 切换 provider 后重发当前模型。claude 仅发送意图(longContextEnabled),
    // effectiveContextWindow 由后端权威计算;codex 仍发送 registry contextWindow。
    if (providerId === 'claude') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: newModel,
        longContextEnabled: longContextEnabled && (newProviderModels.find((model) => model.id === strip1MContextSuffix(selectedClaudeModel))?.supports1MContext ?? false),
      }));
    } else {
      // Codex/OpenCode: send registry contextWindow
      const refModel = providerId === 'codex' ? selectedCodexModel : providerId === 'opencode' ? selectedOpenCodeModel : selectedClaudeModel;
      const registryContextWindow = newProviderModels.find((model) => model.id === strip1MContextSuffix(refModel))?.contextWindow;
      const effectiveContextWindow = contextWindow ?? registryContextWindow ?? DEFAULT_CONTEXT_WINDOW;
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: newModel,
        contextWindow: effectiveContextWindow,
      }));
    }
  }, [
    claudePermissionMode,
    codexPermissionMode,
    selectedCodexModel,
    selectedClaudeModel,
    selectedOpenCodeModel,
    longContextEnabled,
  ]);

    const handleLongContextChange = useCallback((enabled: boolean) => {
    setLongContextEnabled(enabled);
    if (currentProvider === 'claude') {
      const registryModels = getModelsForProvider('claude');
      const supports1M = registryModels.find((model) => model.id === strip1MContextSuffix(selectedClaudeModel))?.supports1MContext ?? false;
      // 仅发送意图;effectiveContextWindow 由后端计算并通过 MODEL_SELECTION 回推。
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedClaudeModel,
        longContextEnabled: enabled && supports1M,
      }));
    }
  }, [currentProvider, selectedClaudeModel, setLongContextEnabled]);

  useEffect(() => {
    if (modelRegistryVersion === 0) {
      return;
    }

    const registryModels = getModelsForProvider(currentProvider);
    const selectedRegistryModel = registryModels.find((model) => (
      model.id === strip1MContextSuffix(selectedModel)
    ));
    if (!selectedRegistryModel) {
      return;
    }

    if (currentProvider === 'claude') {
      const supports1M = registryModels.find((model) => model.id === strip1MContextSuffix(selectedClaudeModel))?.supports1MContext ?? false;
      if (longContextEnabled && !supports1M) {
        setLongContextEnabled(false);
      }
      // 仅发送意图;effectiveContextWindow 由后端权威计算。
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedClaudeModel,
        longContextEnabled: longContextEnabled && supports1M,
      }));
      return;
    }

    if (currentProvider === 'opencode') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedOpenCodeModel,
        contextWindow: selectedRegistryModel.contextWindow ?? DEFAULT_CONTEXT_WINDOW,
      }));
      return;
    }

    sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
      model: selectedCodexModel,
      contextWindow: selectedRegistryModel.contextWindow ?? DEFAULT_CONTEXT_WINDOW,
    }));
  }, [
    currentProvider,
    longContextEnabled,
    modelRegistryVersion,
    selectedClaudeModel,
    selectedCodexModel,
    selectedOpenCodeModel,
    selectedModel,
    setLongContextEnabled,
  ]);

  const handleToggleThinking = useCallback((enabled: boolean) => {
    const config = settings.activeProviderConfig;
    const isSpecialProvider = isSpecialProviderId(config?.id || '');

    setClaudeSettingsAlwaysThinkingEnabled(enabled);

    if (!config || isSpecialProvider) {
      settings.setActiveProviderConfig(prev => prev ? {
        ...prev,
        settingsConfig: {
          ...prev.settingsConfig,
          alwaysThinkingEnabled: enabled,
        },
      } : prev);
      sendAction(UPSTREAM.SET_THINKING_ENABLED, JSON.stringify({ enabled }));
      addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
      return;
    }

    settings.setActiveProviderConfig(prev => prev ? {
      ...prev,
      settingsConfig: {
        ...prev.settingsConfig,
        alwaysThinkingEnabled: enabled,
      },
    } : null);

    sendAction(UPSTREAM.UPDATE_PROVIDER, JSON.stringify({
      id: config.id,
      updates: {
        settingsConfig: {
          ...(config.settingsConfig || {}),
          alwaysThinkingEnabled: enabled,
        },
      },
    }));
    addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
  }, [settings, setClaudeSettingsAlwaysThinkingEnabled, addToast, t]);

  return {
    ...claude,
    ...codex,
    ...opencode,
    ...usage,
    ...settings,
    currentProvider, setCurrentProvider,
    permissionMode, setPermissionMode,
    selectedModel,
    currentSdkInstalled,
    currentProviderRef,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleLongContextChange,
    handleToggleThinking,
  };
}
