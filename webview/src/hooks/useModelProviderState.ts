import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { DOWNSTREAM } from '../generated/protocol';
import { subscribeEvent } from '../bridge/typed';
import type { ModelInfo, PermissionMode } from '../components/ChatInputBox/types';
import { strip1MContextSuffix, getDefaultContextWindowForProvider } from '../components/ChatInputBox/types';
import { useClaudeProvider } from './providers/useClaudeProvider';
import { useCodexProvider } from './providers/useCodexProvider';
import { useOpenCodeProvider } from './providers/useOpenCodeProvider';
import { useGrokProvider } from './providers/useGrokProvider';
import { useKimiProvider } from './providers/useKimiProvider';
import { usePiProvider } from './providers/usePiProvider';
import { isCliOnlyProvider, normalizeCliPermissionMode } from './providers/cliProviders';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';
import { getModelsForProvider, normalizeProvider, subscribeModelRegistry } from '../utils/modelRegistry';
import type { ViewMode } from '../types';

// D3:ViewMode 真相源在 types/index.ts,此处 re-export 保持 hooks/index 与 useMessageSender 下游 import 兼容
export type {ViewMode};

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
export function useModelProviderState({ addToast, t }: { addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void; t: TFunction }) {
  // ── Cross-slice state owned by the orchestrator ──
  const [currentProvider, setCurrentProvider] = useState('claude');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('default');
  const [selectedModelIdentifiers, setSelectedModelIdentifiers] = useState<Record<string, string>>({});

  // External-facing ref so window callbacks can read the latest provider
  // without re-binding. Render-time assignment avoids the useRef + useEffect
  // mirror anti-pattern (rule 5.15).
  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  // ── Provider-specific sub-hooks ──
  const claude = useClaudeProvider();
  const codex = useCodexProvider();
  const opencode = useOpenCodeProvider();
  const grok = useGrokProvider();
  const kimi = useKimiProvider();
  const pi = usePiProvider();
  const usage = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });

  const {
    selectedClaudeModel, setSelectedClaudeModel,
    claudePermissionMode, setClaudePermissionMode,
    longContextEnabled, setLongContextEnabled,
  } = claude;
  const {
    selectedCodexModel, setSelectedCodexModel,
    codexPermissionMode, setCodexPermissionMode,
    reasoningEffort, setReasoningEffort,
    codexFastMode, setCodexFastMode,
  } = codex;
  const { selectedOpenCodeModel, setSelectedOpenCodeModel } = opencode;
  const {
    selectedGrokModel, setSelectedGrokModel,
    grokPermissionMode, setGrokPermissionMode,
  } = grok;
  const {
    selectedKimiModel, setSelectedKimiModel,
    kimiPermissionMode, setKimiPermissionMode,
  } = kimi;
  const {
    selectedPiModel, setSelectedPiModel,
    piPermissionMode, setPiPermissionMode,
  } = pi;

  // ── Persistence: load on mount + save on change ──
  useModelStatePersistence({
    setCurrentProvider,
    setSelectedModelIdentifiers,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setSelectedGrokModel,
    setSelectedKimiModel,
    setSelectedPiModel,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    setCodexFastMode,
    currentProvider,
    selectedModelIdentifiers,
    selectedClaudeModel,
    selectedCodexModel,
    selectedOpenCodeModel,
    selectedGrokModel,
    selectedKimiModel,
    selectedPiModel,
    claudePermissionMode,
    codexPermissionMode,
    grokPermissionMode,
    kimiPermissionMode,
    openCodePermissionMode: 'default',
    piPermissionMode,
    longContextEnabled,
    reasoningEffort,
    codexFastMode,
  });

  // ── Computed values ──
  const selectedModel = currentProvider === 'codex'
    ? selectedCodexModel
    : currentProvider === 'grok'
      ? selectedGrokModel
      : currentProvider === 'kimi'
        ? selectedKimiModel
        : currentProvider === 'opencode'
          ? selectedOpenCodeModel
          : currentProvider === 'pi'
            ? selectedPiModel
            : selectedClaudeModel;
  const selectedModelIdentifier = selectedModelIdentifiers[currentProvider];

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
        identifier?: string;
        effectiveContextWindow?: number;
        supportsLongContext?: boolean;
      };
      const selected = strip1MContextSuffix(selection.selectedModel);
      if (!selected) {
        return;
      }
      const provider = normalizeProvider(selection.provider);
      setCurrentProvider(provider);
      setSelectedModelIdentifiers((previous) => {
        const identifier = typeof selection.identifier === 'string' ? selection.identifier.trim() : '';
        if (identifier) {
          return previous[provider] === identifier ? previous : { ...previous, [provider]: identifier };
        }
        if (!(provider in previous)) {
          return previous;
        }
        const next = { ...previous };
        delete next[provider];
        return next;
      });
      if (provider === 'codex') {
        setSelectedCodexModel(selected);
        return;
      }
      if (provider === 'opencode') {
        setSelectedOpenCodeModel(selected);
        return;
      }
      if (provider === 'grok') {
        setSelectedGrokModel(selected);
        return;
      }
      if (provider === 'kimi') {
        setSelectedKimiModel(selected);
        return;
      }
      if (provider === 'pi') {
        setSelectedPiModel(selected);
        return;
      }
      setSelectedClaudeModel(selected);
      // A4:longContext 直读后端权威布尔 supportsLongContext(见 ModelProviderHandler),不再前端按 effectiveContextWindow 数值推断。
      setLongContextEnabled(selection.supportsLongContext === true);
    } catch {
      // Ignore malformed backend selection events; existing state remains authoritative for display.
    }
  }), [setLongContextEnabled, setSelectedClaudeModel, setSelectedCodexModel, setSelectedOpenCodeModel, setSelectedGrokModel, setSelectedKimiModel, setSelectedPiModel]);

  // ── Cross-provider handlers ──
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    if (currentProvider === 'opencode') {
      return;
    }
    if (currentProvider === 'codex') {
      setPermissionMode(mode);
      setCodexPermissionMode(mode);
      sendAction(UPSTREAM.SET_SESSION_MODE, mode);
      return;
    }
    if (isCliOnlyProvider(currentProvider)) {
      const cliMode = normalizeCliPermissionMode(mode);
      setPermissionMode(cliMode);
      if (currentProvider === 'grok') setGrokPermissionMode(cliMode);
      if (currentProvider === 'kimi') setKimiPermissionMode(cliMode);
      if (currentProvider === 'pi') setPiPermissionMode(cliMode);
      sendAction(UPSTREAM.SET_SESSION_MODE, cliMode);
      return;
    }
    setPermissionMode(mode);
    setClaudePermissionMode(mode);
    sendAction(UPSTREAM.SET_SESSION_MODE, mode);
  }, [currentProvider, setCodexPermissionMode, setClaudePermissionMode, setGrokPermissionMode, setKimiPermissionMode, setPiPermissionMode]);

  const handleModelSelect = useCallback((model: ModelInfo) => {
    const modelId = strip1MContextSuffix(model.id);
    setSelectedModelIdentifiers((previous) => ({ ...previous, [currentProvider]: model.identifier }));

    if (currentProvider === 'claude') {
      setSelectedClaudeModel(modelId);
      const supports1M = model.supports1MContext ?? false;
      if (longContextEnabled && !supports1M) {
        setLongContextEnabled(false);
      }
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: modelId,
        identifier: model.identifier,
        longContextEnabled: longContextEnabled && supports1M,
      }));
      return;
    }

    if (currentProvider === 'codex') setSelectedCodexModel(modelId);
    if (currentProvider === 'opencode') setSelectedOpenCodeModel(modelId);
    if (currentProvider === 'grok') setSelectedGrokModel(modelId);
    if (currentProvider === 'kimi') setSelectedKimiModel(modelId);
    if (currentProvider === 'pi') setSelectedPiModel(modelId);

    sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
      model: modelId,
      identifier: model.identifier,
      contextWindow: model.contextWindow ?? getDefaultContextWindowForProvider(currentProvider),
    }));
  }, [currentProvider, longContextEnabled, setSelectedClaudeModel, setSelectedCodexModel, setSelectedOpenCodeModel, setSelectedGrokModel, setSelectedKimiModel, setSelectedPiModel, setLongContextEnabled]);

  const handleProviderSelect = useCallback((providerId: string, contextWindow?: number) => {
    setCurrentProvider(providerId);
    sendAction(UPSTREAM.SET_SESSION_PROVIDER, providerId);

    let modeToSet: PermissionMode = claudePermissionMode;
    if (providerId === 'codex') {
      modeToSet = codexPermissionMode;
    } else if (providerId === 'opencode') {
      modeToSet = 'default';
    } else if (providerId === 'grok') {
      modeToSet = normalizeCliPermissionMode(grokPermissionMode);
    } else if (providerId === 'kimi') {
      modeToSet = normalizeCliPermissionMode(kimiPermissionMode);
    } else if (providerId === 'pi') {
      modeToSet = normalizeCliPermissionMode(piPermissionMode);
    }
    setPermissionMode(modeToSet);
    sendAction(UPSTREAM.SET_SESSION_MODE, modeToSet);

    let newModel = providerId === 'codex'
      ? selectedCodexModel
      : providerId === 'opencode'
        ? selectedOpenCodeModel
        : providerId === 'grok'
          ? selectedGrokModel
          : providerId === 'kimi'
            ? selectedKimiModel
            : providerId === 'pi'
              ? selectedPiModel
              : selectedClaudeModel;

    const newProviderModels = getModelsForProvider(providerId);
    const savedIdentifier = selectedModelIdentifiers[providerId];
    const selectedRegistryModel = (savedIdentifier
      ? newProviderModels.find((model) => model.identifier === savedIdentifier)
      : undefined)
      ?? newProviderModels.find((model) => model.id === strip1MContextSuffix(newModel))
      ?? newProviderModels[0];
    if (selectedRegistryModel) {
      newModel = selectedRegistryModel.id;
      setSelectedModelIdentifiers((previous) => ({
        ...previous,
        [providerId]: selectedRegistryModel.identifier,
      }));
    }

    if (providerId === 'codex') {
      setSelectedCodexModel(newModel);
    } else if (providerId === 'opencode') {
      setSelectedOpenCodeModel(newModel);
    } else if (providerId === 'grok') {
      setSelectedGrokModel(newModel);
    } else if (providerId === 'kimi') {
      setSelectedKimiModel(newModel);
    } else if (providerId === 'pi') {
      setSelectedPiModel(newModel);
    } else {
      setSelectedClaudeModel(newModel);
    }

    if (providerId === 'claude') {
      const supports1M = selectedRegistryModel?.supports1MContext ?? false;
      setLongContextEnabled(supports1M);
      setReasoningEffort('high');
      setCodexFastMode('normal');
    } else if (providerId === 'codex') {
      setLongContextEnabled(false);
    } else if (providerId === 'opencode') {
      setLongContextEnabled(false);
      setReasoningEffort('high');
      setCodexFastMode('normal');
    } else {
      // grok/kimi/pi: CLI providers don't support long context
      setLongContextEnabled(false);
    }

    if (providerId === 'claude') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: newModel,
        identifier: selectedRegistryModel?.identifier,
        longContextEnabled: longContextEnabled && (selectedRegistryModel?.supports1MContext ?? false),
      }));
    } else {
      const registryContextWindow = selectedRegistryModel?.contextWindow;
      const effectiveContextWindow = contextWindow ?? registryContextWindow ?? getDefaultContextWindowForProvider(currentProvider);
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: newModel,
        identifier: selectedRegistryModel?.identifier,
        contextWindow: effectiveContextWindow,
      }));
    }
  }, [
    claudePermissionMode,
    codexPermissionMode,
    grokPermissionMode,
    kimiPermissionMode,
    piPermissionMode,
    selectedCodexModel,
    selectedClaudeModel,
    selectedOpenCodeModel,
    selectedGrokModel,
    selectedKimiModel,
    selectedPiModel,
    longContextEnabled,
    selectedModelIdentifiers,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setSelectedGrokModel,
    setSelectedKimiModel,
    setSelectedPiModel,
    setLongContextEnabled,
    setReasoningEffort,
    setCodexFastMode,
  ]);

  const handleLongContextChange = useCallback((enabled: boolean) => {
    setLongContextEnabled(enabled);
    if (currentProvider === 'claude') {
      const registryModels = getModelsForProvider('claude');
      const selectedRegistryModel = (selectedModelIdentifiers.claude
        ? registryModels.find((model) => model.identifier === selectedModelIdentifiers.claude)
        : undefined) ?? registryModels.find((model) => model.id === strip1MContextSuffix(selectedClaudeModel));
      const supports1M = selectedRegistryModel?.supports1MContext ?? false;
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedClaudeModel,
        identifier: selectedRegistryModel?.identifier,
        longContextEnabled: enabled && supports1M,
      }));
    }
  }, [currentProvider, selectedClaudeModel, selectedModelIdentifiers, setLongContextEnabled]);

  useEffect(() => {
    if (modelRegistryVersion === 0) {
      return;
    }

    const registryModels = getModelsForProvider(currentProvider);
    const selectedRegistryModel = (selectedModelIdentifier
      ? registryModels.find((model) => model.identifier === selectedModelIdentifier)
      : undefined) ?? registryModels.find((model) => (
      model.id === strip1MContextSuffix(selectedModel)
    ));
    if (!selectedRegistryModel) {
      return;
    }
    if (selectedModelIdentifier !== selectedRegistryModel.identifier) {
      setSelectedModelIdentifiers((previous) => ({
        ...previous,
        [currentProvider]: selectedRegistryModel.identifier,
      }));
    }

    if (currentProvider === 'claude') {
      const supports1M = selectedRegistryModel.supports1MContext ?? false;
      if (longContextEnabled && !supports1M) {
        setLongContextEnabled(false);
      }
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedClaudeModel,
        identifier: selectedRegistryModel.identifier,
        longContextEnabled: longContextEnabled && supports1M,
      }));
      return;
    }

    if (currentProvider === 'opencode') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedOpenCodeModel,
        identifier: selectedRegistryModel.identifier,
        contextWindow: selectedRegistryModel.contextWindow ?? getDefaultContextWindowForProvider('opencode'),
      }));
      return;
    }

    if (currentProvider === 'grok') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedGrokModel,
        identifier: selectedRegistryModel.identifier,
        contextWindow: selectedRegistryModel.contextWindow ?? getDefaultContextWindowForProvider('grok'),
      }));
      return;
    }

    if (currentProvider === 'kimi') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedKimiModel,
        identifier: selectedRegistryModel.identifier,
        contextWindow: selectedRegistryModel.contextWindow ?? getDefaultContextWindowForProvider('kimi'),
      }));
      return;
    }

    if (currentProvider === 'pi') {
      sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
        model: selectedPiModel,
        identifier: selectedRegistryModel.identifier,
        contextWindow: selectedRegistryModel.contextWindow ?? getDefaultContextWindowForProvider('pi'),
      }));
      return;
    }

    sendAction(UPSTREAM.SET_SESSION_MODEL, JSON.stringify({
      model: selectedCodexModel,
      identifier: selectedRegistryModel.identifier,
      contextWindow: selectedRegistryModel.contextWindow ?? getDefaultContextWindowForProvider('codex'),
    }));
  }, [
    currentProvider,
    longContextEnabled,
    modelRegistryVersion,
    selectedClaudeModel,
    selectedCodexModel,
    selectedOpenCodeModel,
    selectedGrokModel,
    selectedKimiModel,
    selectedPiModel,
    selectedModel,
    selectedModelIdentifier,
    setLongContextEnabled,
  ]);

  return {
    ...claude,
    ...codex,
    ...opencode,
    ...grok,
    ...kimi,
    ...pi,
    ...usage,
    ...settings,
    currentProvider, setCurrentProvider,
    permissionMode, setPermissionMode,
    selectedModel,
    selectedModelIdentifier,
    currentProviderRef,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleLongContextChange,
  };
}
