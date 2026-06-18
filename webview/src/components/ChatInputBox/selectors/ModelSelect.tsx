import { useCallback, useDeferredValue, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AVAILABLE_MODELS,
  CLAUDE_ROLE_MODEL_IDS,
  getClaudeRoleFromModelId,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from '../types';
import type { ModelInfo } from '../types';
import { readClaudeModelMapping } from '../../../utils/claudeModelMapping';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';

const RELATIVE_INLINE_BLOCK_STYLE: React.CSSProperties = { position: 'relative', display: 'inline-block' };
const CHEVRON_ICON_STYLE: React.CSSProperties = { fontSize: '10px', marginLeft: '2px' };
const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  zIndex: 10000,
  maxWidth: 'calc(100vw - 16px)',
  overflowX: 'hidden',
};
const MODEL_OPTION_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0, overflow: 'hidden' };
const MODEL_TEXT_STYLE: React.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' };
const MAX_VISIBLE_MODEL_OPTIONS = 100;

interface ModelSelectProps {
  value: string;
  onChange: (modelId: string) => void;
  models?: ModelInfo[];
  currentProvider?: string;
}

const DEFAULT_MODEL_MAP: Record<string, ModelInfo> = AVAILABLE_MODELS.reduce(
  (acc, model) => {
    acc[model.id] = model;
    return acc;
  },
  {} as Record<string, ModelInfo>
);

const MODEL_LABEL_KEYS: Record<string, string> = {
  [CLAUDE_ROLE_MODEL_IDS.sonnet]: 'models.claude.roles.sonnet.label',
  [CLAUDE_ROLE_MODEL_IDS.opus]: 'models.claude.roles.opus.label',
  [CLAUDE_ROLE_MODEL_IDS.fable]: 'models.claude.roles.fable.label',
  [CLAUDE_ROLE_MODEL_IDS.haiku]: 'models.claude.roles.haiku.label',
};

const MODEL_DESCRIPTION_KEYS: Record<string, string> = {
  [CLAUDE_ROLE_MODEL_IDS.sonnet]: 'models.claude.roles.sonnet.description',
  [CLAUDE_ROLE_MODEL_IDS.opus]: 'models.claude.roles.opus.description',
  [CLAUDE_ROLE_MODEL_IDS.fable]: 'models.claude.roles.fable.description',
  [CLAUDE_ROLE_MODEL_IDS.haiku]: 'models.claude.roles.haiku.description',
};

/**
 * Maps model IDs to mapping keys for looking up actual model names
 * from the 'claude-model-mapping' localStorage entry.
 */
const MODEL_ID_TO_MAPPING_KEY: Record<string, string> = {
  [CLAUDE_ROLE_MODEL_IDS.sonnet]: 'sonnet',
  [CLAUDE_ROLE_MODEL_IDS.opus]: 'opus',
  [CLAUDE_ROLE_MODEL_IDS.fable]: 'fable',
  [CLAUDE_ROLE_MODEL_IDS.haiku]: 'haiku',
};

const resolveMappedModelName = (
  mappingKey: string | undefined,
  modelMapping: Record<string, string | undefined>
): string | undefined => {
  if (!mappingKey) {
    return modelMapping.main?.trim() || undefined;
  }

  const mapped = modelMapping[mappingKey]
    || (mappingKey === 'opus_1m' ? modelMapping.opus : undefined)
    || modelMapping.main;

  return mapped?.trim() || undefined;
};

const getRoleModelLabel = (modelId: string): string | null => {
  const role = getClaudeRoleFromModelId(modelId);
  if (!role) {
    return null;
  }
  return role[0].toUpperCase() + role.slice(1);
};

/**
 * Resolve the display model name for icon matching.
 * For mapped Claude models, returns the mapped name; otherwise the original ID.
 */
const resolveModelIdForIcon = (
  modelId: string,
  modelMapping: Record<string, string | undefined>,
  mappingKeyMap: Record<string, string>
): string => {
  const mappingKey = mappingKeyMap[modelId];
  if (!mappingKey) {
    return modelId;
  }
  const mapped = resolveMappedModelName(mappingKey, modelMapping);
  if (mapped) {
    return mapped;
  }
  return modelId;
};

/**
 * ModelSelect - Model selector component
 * Supports switching between Sonnet 4.5, Opus 4.5, and other models, including Codex models
 */
export const ModelSelect = ({ value, onChange, models = AVAILABLE_MODELS, currentProvider = 'claude' }: ModelSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const deferredSearchQuery = useDeferredValue(searchQuery);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, maxHeight, recalculate } = useDropdownPosition({
    buttonRef,
    dropdownRef,
    isOpen,
    preferredAlignment: 'right',
  });

  // Strip [1m] suffix for finding the model in the list
  const strippedValue = strip1MContextSuffix(value);
  const normalizedValue = currentProvider === 'claude' ? normalizeClaudeModelId(strippedValue) : strippedValue;
  const exactSelectedModel = models.find(m => m.id === strippedValue);
  const currentModel = exactSelectedModel || models.find(m => m.id === normalizedValue) || models[0];
  const modelMapping = readClaudeModelMapping();

  const isSelectedModel = (modelId: string): boolean => {
    if (modelId === strippedValue) {
      return true;
    }
    if (currentProvider !== 'claude') {
      return false;
    }
    if (exactSelectedModel) {
      return false;
    }
    return normalizeClaudeModelId(modelId) === normalizedValue;
  };

  const getModelLabel = (model: ModelInfo, show1MContext = false): string => {
    const mappingKey = MODEL_ID_TO_MAPPING_KEY[model.id];
    if (mappingKey) {
      const mappedName = resolveMappedModelName(mappingKey, modelMapping);
      if (mappedName) {
        // Strip [1m] suffix from mapped name for clean display
        const cleanName = strip1MContextSuffix(mappedName);
        return append1MContextSuffix(cleanName, model.id, show1MContext);
      }
    }

    const defaultModel = DEFAULT_MODEL_MAP[model.id];
    const labelKey = MODEL_LABEL_KEYS[model.id];
    const hasCustomLabel = defaultModel && model.label && model.label !== defaultModel.label;

    if (hasCustomLabel) {
      // Strip [1m] suffix from custom label for clean display
      const cleanLabel = strip1MContextSuffix(model.label ?? '');
      return append1MContextSuffix(cleanLabel, model.id, show1MContext);
    }

    if (labelKey) {
      const fallback = getRoleModelLabel(model.id) ?? model.label ?? model.id;
      return append1MContextSuffix(t(labelKey, { defaultValue: fallback }), model.id, show1MContext);
    }

    return append1MContextSuffix(model.label ?? '', model.id, show1MContext);
  };

  const append1MContextSuffix = (label: string, _modelId: string, _show1MContext: boolean): string => {
    // 1M context suffix removed - configuration is now centralized in settings
    return label;
  };

  const getModelDescription = (model: ModelInfo): string | undefined => {
    const descriptionKey = MODEL_DESCRIPTION_KEYS[model.id];
    if (descriptionKey) {
      return t(descriptionKey, { defaultValue: model.description ?? '' });
    }
    return model.description;
  };

  const normalizedSearchQuery = deferredSearchQuery.trim().toLowerCase();
  const filteredModels = normalizedSearchQuery
    ? models.filter((model) => {
        const label = getModelLabel(model, false);
        const description = getModelDescription(model) ?? '';
        return [model.id, label, description].some((value) => value.toLowerCase().includes(normalizedSearchQuery));
      })
    : models;
  const visibleModels = filteredModels.slice(0, MAX_VISIBLE_MODEL_OPTIONS);
  const hiddenModelCount = Math.max(0, filteredModels.length - visibleModels.length);
  const showSearch = models.length > MAX_VISIBLE_MODEL_OPTIONS || searchQuery.length > 0;

  /**
   * Toggle dropdown
   */
  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (!nextOpen) {
      setSearchQuery('');
    }
    if (nextOpen) {
      recalculate();
    }
  }, [isOpen, recalculate]);

  /**
   * Select model
   */
  const handleSelect = useCallback((modelId: string) => {
    onChange(modelId);
    setIsOpen(false);
    setSearchQuery('');
  }, [onChange]);

  /**
   * Close on outside click
   */
  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
        setSearchQuery('');
      }
    };

    // Delay adding event listener to prevent immediate trigger
    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  useLayoutEffect(() => {
    if (isOpen) {
      recalculate();
    }
  }, [isOpen, filteredModels.length, recalculate]);

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title={t('chat.currentModel', { model: getModelLabel(currentModel, true) })}
      >
        <ProviderModelIcon
          providerId={currentProvider}
          modelId={resolveModelIdForIcon(currentModel.id, modelMapping, MODEL_ID_TO_MAPPING_KEY)}
          size={12}
          colored
        />
        <span className="selector-button-text">{getModelLabel(currentModel, true)}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{ ...DROPDOWN_STYLE, ...positionedStyle, maxHeight, overflowY: 'auto' }}
        >
          {showSearch && (
            <div className="selector-search-row">
              <input
                className="selector-search-input"
                data-testid="model-search-input"
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder={t('models.searchPlaceholder', { defaultValue: 'Search models' })}
                autoFocus
              />
            </div>
          )}
          {visibleModels.map((model) => (
            <div
              key={model.id}
              className={`selector-option ${isSelectedModel(model.id) ? 'selected' : ''}`}
              onClick={() => handleSelect(model.id)}
            >
              <ProviderModelIcon
                providerId={currentProvider}
                modelId={resolveModelIdForIcon(model.id, modelMapping, MODEL_ID_TO_MAPPING_KEY)}
                size={16}
                colored
              />
              <div style={MODEL_OPTION_INFO_STYLE}>
                <span style={MODEL_TEXT_STYLE}>{getModelLabel(model, false)}</span>
                {getModelDescription(model) && (
                  <span className="model-description" style={MODEL_TEXT_STYLE}>{getModelDescription(model)}</span>
                )}
              </div>
              {isSelectedModel(model.id) && (
                <span className="codicon codicon-check check-mark" />
              )}
            </div>
          ))}
          {visibleModels.length === 0 && (
            <div className="selector-option selector-option-status">
              {t('models.noModelsFound', { defaultValue: 'No models found' })}
            </div>
          )}
          {hiddenModelCount > 0 && (
            <div className="selector-option selector-option-status" data-testid="model-hidden-count">
              {t('models.hiddenModelCount', {
                count: hiddenModelCount,
                defaultValue: `+ ${hiddenModelCount} more models. Type to search.`,
              })}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
