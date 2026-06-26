import { useCallback, useDeferredValue, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  CLAUDE_ROLE_MODEL_IDS,
  strip1MContextSuffix,
} from '../types';
import type { ModelInfo } from '../types';
import { readClaudeModelMapping, resolveMappedModelName } from '../../../utils/claudeModelMapping';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';
import { getModelRegistrySnapshot } from '../../../utils/modelRegistry';
import { CheckIcon, ChevronDownIcon, ChevronUpIcon } from '../../Icons';

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

// A1:DEFAULT_MODEL_MAP(基于已删除的 AVAILABLE_MODELS/CLAUDE_MODELS)已移除。
// 模型 label 由后端 ModelConfig.label 权威下发 + i18n labelKey 翻译,无需前端默认表对比。

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

// D5:角色解析收口——用 registry 的 role 字段(后端权威下发)判定内置 Claude 模型并取角色,
// 替代 MODEL_ID_TO_MAPPING_KEY 离线表;映射名解析复用 utils/claudeModelMapping.resolveMappedModelName
// (与 ButtonArea.applyModelMapping 共用单一入口,顺带移除 opus_1m 死代码分支)。
function getRoleForModelId(modelId: string): string | undefined {
  return getModelRegistrySnapshot().items.find((it) => it.provider === 'claude' && it.id === modelId)?.role;
}

// A3(2026-06-23):getRoleModelLabel(从 id 离线推导 role 名作 label 兜底)已移除。
// label 兜底改用后端下发的 model.label(见 getModelLabel)。

/**
 * Resolve the display model name for icon matching.
 * For mapped Claude models, returns the mapped name; otherwise the original ID.
 */
const resolveModelIdForIcon = (
  modelId: string,
  modelMapping: Record<string, string | undefined>,
): string => {
  const role = getRoleForModelId(modelId);
  if (!role) {
    return modelId;
  }
  const mapped = resolveMappedModelName(role, modelMapping);
  if (mapped) {
    return mapped;
  }
  return modelId;
};

/**
 * ModelSelect - Model selector component
 * Supports switching between Sonnet 4.5, Opus 4.5, and other models, including Codex models
 */
export const ModelSelect = ({ value, onChange, models = [], currentProvider = 'claude' }: ModelSelectProps) => {
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
  const hasModels = models.length > 0;
  const exactSelectedModel = models.find(m => m.id === strippedValue);
  // Guard against empty models (e.g. Codex provider before config.toml is set):
  // models[0] would be undefined and crash on .id access. Fall back to a null placeholder.
  const resolvedModel: ModelInfo | null = hasModels
    ? (exactSelectedModel || models[0])
    : null;
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
    // A3:不再归一化比较;仅剥离 [1m] 后缀做精确匹配。
    return strip1MContextSuffix(modelId) === strippedValue;
  };

  const getModelLabel = (model: ModelInfo, show1MContext = false): string => {
    // 仅内置 Claude role 模型(claude-role-*)套用全局 role→实际模型名映射,
    // 显示用户在映射里配置的实际模型名。
    // 自定义 Claude 模型(如 mimo-v2.5,虽也带 role=sonnet,但有自身 actualModel/label)
    // 不应被 role 映射覆盖——否则会与内置 sonnet 显示成相同的映射名,用户无法区分/选择。
    // (与 ButtonArea.applyModelMapping 的语义保持一致:自定义模型保留自身 label。)
    const isBuiltinRoleModel = (
      Object.values(CLAUDE_ROLE_MODEL_IDS) as readonly string[]
    ).includes(model.id);
    if (isBuiltinRoleModel) {
      const role = getRoleForModelId(model.id);
      if (role) {
        const mappedName = resolveMappedModelName(role, modelMapping);
        if (mappedName) {
          // Strip [1m] suffix from mapped name for clean display
          const cleanName = strip1MContextSuffix(mappedName);
          return append1MContextSuffix(cleanName, model.id, show1MContext);
        }
      }
    }

    const labelKey = MODEL_LABEL_KEYS[model.id];

    if (labelKey) {
      // A3:label 兜底用后端下发的 model.label,不再从 id 离线推导 role 名。
      const fallback = model.label ?? model.id;
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
        disabled={!hasModels}
        title={resolvedModel
          ? t('chat.currentModel', { model: getModelLabel(resolvedModel, true) })
          : t('chat.noModelConfigured', 'No model configured')}
      >
        {resolvedModel ? (
          <>
            <ProviderModelIcon
              providerId={currentProvider}
              modelId={resolveModelIdForIcon(resolvedModel.id, modelMapping)}
              size={14}
              colored
            />
            <span className="selector-button-text">{getModelLabel(resolvedModel, true)}</span>
          </>
        ) : (
          <span className="selector-button-text">{t('chat.noModelConfigured', 'No model configured')}</span>
        )}
        {isOpen ? <ChevronUpIcon size={16} style={CHEVRON_ICON_STYLE} /> : <ChevronDownIcon size={16} style={CHEVRON_ICON_STYLE} />}
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
                modelId={resolveModelIdForIcon(model.id, modelMapping)}
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
                <CheckIcon size={16} className="check-mark" />
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
