import {memo, useCallback, useEffect, useMemo, useState} from 'react';
import {useTranslation} from 'react-i18next';
import type {ButtonAreaProps, ModelInfo, PermissionMode, ReasoningEffort} from './types';
import {CLAUDE_MODELS, strip1MContextSuffix} from './types';
import {ConfigSelect, ModelSelect, ModeSelect, ProviderSelect, ReasoningSelect} from './selectors';
import {PROVIDER_PRESETS} from '../../types/provider';
import {readClaudeModelMapping} from '../../utils/claudeModelMapping';
import {getModelsForProvider, requestModelRegistry, subscribeModelRegistry} from '../../utils/modelRegistry';
import {SendIcon, SparklesIcon, StopIcon} from '../Icons';

/**
 * ButtonArea - Bottom toolbar component
 * Contains mode selector, model selector, attachment button, prompt enhancer button, send/stop button
 */
export const ButtonArea = memo(function ButtonArea({
  disabled = false,
  hasInputContent = false,
  isLoading = false,
  isEnhancing = false,
  selectedModel = 'claude-sonnet-4-6',
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
  alwaysThinkingEnabled = false,
  onToggleThinking,
  streamingEnabled = true,
  onStreamingEnabledChange,
  selectedAgent,
  onAgentSelect,
  onOpenAgentSettings,
  onAddModel,
  longContextEnabled = true,
  onLongContextChange,
}: ButtonAreaProps) {
  const { t } = useTranslation();
  // const fileInputRef = useRef<HTMLInputElement>(null);

  const [modelRegistryVersion, setModelRegistryVersion] = useState(0);

  // Get provider preset for current provider
  const currentProviderPreset = useMemo(() => {
    return PROVIDER_PRESETS.find(p => p.id === currentProvider);
  }, [currentProvider]);

  useEffect(() => {
    requestModelRegistry();
    return subscribeModelRegistry(() => setModelRegistryVersion(v => v + 1));
  }, []);

  /**
   * Apply model name mapping
   * Maps base model IDs to actual model names (e.g., versions with capacity suffixes)
   */
  const applyModelMapping = useCallback((model: ModelInfo, mapping: { main?: string; haiku?: string; sonnet?: string; opus?: string }): ModelInfo => {
    const modelKeyMap: Record<string, keyof typeof mapping> = {
      'claude-sonnet-4-6': 'sonnet',
      'claude-opus-4-8': 'opus',
      'claude-opus-4-7': 'opus',
      'claude-haiku-4-5': 'haiku',
    };

    const key = modelKeyMap[model.id];
    const resolvedMapping = (key ? mapping[key] : undefined) || mapping.main;
    if (resolvedMapping) {
      const actualModel = String(resolvedMapping).trim();
      if (actualModel.length > 0) {
        // Keep the original id as unique identifier, only modify label to custom name
        // This ensures id remains unique even if multiple models share the same displayName
        return { ...model, label: actualModel };
      }
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
      return registryModels.length > 0 ? registryModels : CLAUDE_MODELS;
    }

    // Apply model mapping to built-in models
    let builtInModels = registryModels.length > 0 ? registryModels : CLAUDE_MODELS;
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
  const handleModelSelect = useCallback((modelId: string) => {
      // Strip [1m] suffix and look up contextWindow from the merged model list
      const stripped = strip1MContextSuffix(modelId);
      const modelInfo = availableModels.find(m => m.id === stripped);
      // Pass clean model ID (no [1m]) to the bridge
      onModelSelect?.(stripped, modelInfo?.contextWindow);
  }, [onModelSelect, availableModels]);

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
          alwaysThinkingEnabled={alwaysThinkingEnabled}
          onToggleThinking={onToggleThinking}
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
        <span className="selector-separator" />
        <ModeSelect value={permissionMode} onChange={handleModeSelect} provider={currentProvider} />
        <span className="selector-separator" />
        <ModelSelect value={selectedModel} onChange={handleModelSelect} models={availableModels} currentProvider={currentProvider} onAddModel={onAddModel} longContextEnabled={longContextEnabled} onLongContextChange={onLongContextChange} providerPreset={currentProviderPreset} />
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
            <svg className="icon spinning" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 12a9 9 0 11-6.219-8.56" />
            </svg>
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
          <button
            className="submit-button"
            onClick={handleSubmitClick}
            disabled={disabled || !hasInputContent}
            title={t('chat.sendMessageEnter')}
          >
            <SendIcon size={16} />
          </button>
        )}
      </div>
    </div>
  );
});

