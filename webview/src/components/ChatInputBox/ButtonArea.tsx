import {memo, useCallback, useEffect, useMemo, useState} from 'react';
import {useTranslation} from 'react-i18next';
import type {ButtonAreaProps, ModelInfo, PermissionMode, ReasoningEffort} from './types';
import {CLAUDE_ROLE_MODEL_IDS} from './types';
import {ConfigSelect, ModelSelect, ModeSelect, ProviderSelect, ReasoningSelect} from './selectors';
import {readClaudeModelMapping, resolveMappedModelName} from '../../utils/claudeModelMapping';
import {getModelsForProvider, getModelRegistrySnapshot, requestModelRegistry, subscribeModelRegistry} from '../../utils/modelRegistry';
import {SendIcon, SparklesIcon, StopIcon} from '../Icons';
import {ClickSpark, SpinLoader} from '../react-bits';

/**
 * ButtonArea - Bottom toolbar component
 * Contains mode selector, model selector, attachment button, prompt enhancer button, send/stop button
 */
export const ButtonArea = memo(function ButtonArea({
  disabled = false,
  hasInputContent = false,
  isLoading = false,
  isEnhancing = false,
  selectedModel = CLAUDE_ROLE_MODEL_IDS.sonnet,
  selectedModelIdentifier,
  permissionMode = 'default',
  currentProvider = 'claude',
  reasoningEffort = 'high',
  onSubmit,
  onStop,
  onModeSelect,
  onModelSelect,
  onProviderSelect,
  onReasoningChange,
  onEnhancePrompt,
  showThinkingEnabled = true,
  onShowThinkingEnabledChange,
  streamingEnabled = true,
  onStreamingEnabledChange,
  selectedAgent,
  onAgentSelect,
  onOpenAgentSettings,
}: ButtonAreaProps) {
  const { t } = useTranslation();
  // const fileInputRef = useRef<HTMLInputElement>(null);

  const [modelRegistryVersion, setModelRegistryVersion] = useState(0);

  useEffect(() => {
    requestModelRegistry();
    return subscribeModelRegistry(() => setModelRegistryVersion(v => v + 1));
  }, []);

  /**
   * Apply model name mapping
   * Maps base model IDs to actual model names (e.g., versions with capacity suffixes)
   * Only applies to built-in Claude models, not custom models
   *
   * When a mapping is configured:
   * - The display label is changed to the mapped name
   * - The model ID remains the Claude role model
   *   (so the backend can resolve the actual request model from settings.json)
   *
   * Example: claude-role-sonnet → mimo-v2.5
   *   label: "mimo-v2.5" (display)
   *   id: "claude-role-sonnet" (sent to backend for role mapping)
   */
  const applyModelMapping = useCallback((model: ModelInfo, mapping: { main?: string; fable?: string; haiku?: string; sonnet?: string; opus?: string }): ModelInfo => {
    const registryModel = getModelRegistrySnapshot().items.find((item) => item.provider === 'claude' && item.id === model.id);
    if (registryModel?.actualModel) {
      return model;
    }

    // A3:用 registry 的 role 字段判定是否内置模型(后端权威下发),不再从 id 离线推导。
    const key = registryModel?.role;
    // Only apply mapping to built-in Claude models, keep custom model labels unchanged
    if (!key) {
      return model;
    }

    // D5:映射解析收口到 claudeModelMapping.resolveMappedModelName(与 ModelSelect 共用单一入口)
    const resolvedMapping = resolveMappedModelName(key, mapping);
    if (resolvedMapping) {
      return { ...model, label: resolvedMapping };
    }
    return model;
  }, []);

  // Select model list based on current provider.
  const availableModels = useMemo(() => {
    const registryModels = getModelsForProvider(currentProvider);
    if (currentProvider === 'codex') {
      return registryModels;
    }
    if (typeof window === 'undefined' || !window.localStorage) {
      // A1:不再回退本地表;registry 空时返回空(SSR/localStorage 不可用场景罕见)。
      return registryModels;
    }

    // Apply model mapping to built-in models
    // A1:不再回退本地表 CLAUDE_MODELS;registry 为权威来源(空态由后端下发填补)。
    let builtInModels = registryModels;
    try {
      const mapping = readClaudeModelMapping();
      if (Object.keys(mapping).length > 0) {
        builtInModels = builtInModels.map((m) => applyModelMapping(m, mapping));
      }
    } catch {
      // ignore
    }
    return builtInModels;
  }, [currentProvider, applyModelMapping, modelRegistryVersion]);

  /**
   * Handle submit button click
   */
  const handleSubmitClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onSubmit?.();
  }, [onSubmit]);

  /**
   * Handle stop button click
   */
  const handleStopClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onStop?.();
  }, [onStop]);

  /**
   * Handle mode selection
   */
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    onModeSelect?.(mode);
  }, [onModeSelect]);

  /**
   * Handle model selection
   */
  const handleModelSelect = useCallback((model: ModelInfo) => {
    onModelSelect?.(model);
  }, [onModelSelect]);

  /**
   * Handle provider selection
   */
  const handleProviderSelect = useCallback((providerId: string) => {
    onProviderSelect?.(providerId);
  }, [onProviderSelect]);

  /**
   * Handle reasoning depth selection
   */
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    onReasoningChange?.(effort);
  }, [onReasoningChange]);

  /**
   * Handle enhance prompt button click
   */
  const handleEnhanceClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onEnhancePrompt?.();
  }, [onEnhancePrompt]);

  return (
    <div className="button-area" data-provider={currentProvider}>
      {/* Left side: selectors */}
      <div className="button-area-left">
        <ConfigSelect
          showThinkingEnabled={showThinkingEnabled}
          onShowThinkingEnabledChange={onShowThinkingEnabledChange}
          streamingEnabled={streamingEnabled}
          onStreamingEnabledChange={onStreamingEnabledChange}
          selectedAgent={selectedAgent}
          onAgentSelect={onAgentSelect}
          onOpenAgentSettings={onOpenAgentSettings}
          currentProvider={currentProvider}
        />
        <span className="selector-separator" />
        <ProviderSelect
          value={currentProvider}
          onChange={handleProviderSelect}
          compact
        />
        {currentProvider !== 'opencode' && <span className="selector-separator" />}
        {currentProvider !== 'opencode' && (
          <ModeSelect value={permissionMode} onChange={handleModeSelect} provider={currentProvider} />
        )}
        <span className="selector-separator" />
        <ModelSelect value={selectedModel} selectedIdentifier={selectedModelIdentifier} onChange={handleModelSelect} models={availableModels} currentProvider={currentProvider} />
        <span className="selector-separator" />
        <ReasoningSelect value={reasoningEffort} onChange={handleReasoningChange} selectedModel={selectedModel} currentProvider={currentProvider} />
      </div>

      {/* Right side: tool buttons */}
      <div className="button-area-right">
        <div className="button-divider" />

        {/* Enhance prompt button */}
        <button
          className="enhance-prompt-button has-tooltip"
          onClick={handleEnhanceClick}
          disabled={disabled || !hasInputContent || isLoading || isEnhancing}
          data-tooltip={`${t('promptEnhancer.tooltip')} (${t('promptEnhancer.shortcut')})`}
        >
          {isEnhancing ? (
            <SpinLoader variant="ring" size={16} strokeWidth={2} />
          ) : (
            <SparklesIcon size={16} />
          )}
        </button>

        {/* Send/Stop button */}
        {isLoading ? (
          <button
            className="submit-button stop-button"
            onClick={handleStopClick}
            title={t('chat.stopGeneration')}
          >
            <StopIcon size={14} />
          </button>
        ) : (
          <ClickSpark
            onClick={handleSubmitClick}
            enabled={!disabled && hasInputContent}
            className="submit-spark"
            style={{display: 'inline-flex', flexShrink: 0}}
          >
            <button
              className="submit-button"
              disabled={disabled || !hasInputContent}
              title={t('chat.sendMessageEnter')}
            >
              <SendIcon size={16} />
            </button>
          </ClickSpark>
        )}
      </div>
    </div>
  );
});
