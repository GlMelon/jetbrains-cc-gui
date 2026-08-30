import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { REASONING_LEVELS, type ReasoningEffort } from '../types';
import { useReasoningEffortGuard } from '../reasoningUtils';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';
import { CheckIcon, ReasoningGaugeIcon } from '../../Icons';

/** ReasoningEffort → 油表档位(0-4),与 REASONING_LEVELS 顺序一致(low/medium/high/xhigh/max) */
const REASONING_GAUGE_LEVEL: Record<ReasoningEffort, 0 | 1 | 2 | 3 | 4> = {
  low: 0,
  medium: 1,
  high: 2,
  xhigh: 3,
  max: 4,
};

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
const LEVEL_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', flex: 1 };
/** Five two-line rows; only the viewport should clip, not a 300px design cap. */
const SUBMENU_MAX_HEIGHT_PX = 480;

interface ReasoningSelectProps {
  value: ReasoningEffort;
  onChange: (effort: ReasoningEffort) => void;
  disabled?: boolean;
  selectedModel?: string;
  currentProvider?: string;
  sessionThinkingAvailable?: boolean;
  embedded?: boolean;
  triggerRef?: React.RefObject<HTMLElement | null>;
  onClose?: () => void;
}

/**
 * ReasoningSelect - Reasoning Effort Selector
 * Controls the depth of reasoning for AI models.
 *
 * 三 provider 统一 5 档(low/medium/high/xhigh/max),对齐 Claude Code CLI 全集:
 * - Claude:可选级别来自后端权威下发的 supportedReasoningLevels(派生自 ClaudeRole.reasoningLevels)
 *   —— sonnet/opus/fable=5 档、haiku=3 档(low/medium/high)。
 * - Codex/OpenCode:后端 capability 下发前展示全集 5 档,值透传由 CLI/SDK 消费
 *   (Codex 透传 OpenAI reasoning.effort;OpenCode CLI 映射 --variant);
 *   模型不支持的高档由 CLI/模型层 clamp/降级(插件不建 per-model 映射表)。
 * - 未配置 role 的自定义 Claude 模型:不下发 supportedReasoningLevels → 隐藏。
 *
 * registry 未加载时返回 null,组件隐藏(loading 态,registry 下发后回填)。
 */
export const ReasoningSelect = ({
  value,
  onChange,
  disabled,
  selectedModel,
  currentProvider,
  sessionThinkingAvailable,
  embedded = false,
  triggerRef,
  onClose,
}: ReasoningSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, maxHeight, maxWidth, recalculate } = useDropdownPosition({
    buttonRef: (embedded ? triggerRef : buttonRef) as React.RefObject<HTMLElement | null>,
    dropdownRef,
    isOpen,
    preferredAlignment: 'right',
    submenu: embedded,
    minWidth: embedded ? 180 : 200,
    maxWidth: 280,
    submenuMaxHeight: SUBMENU_MAX_HEIGHT_PX,
  });

  const { isVisible, availableLevels, currentLevel } = useReasoningEffortGuard(
    value,
    onChange,
    selectedModel,
    currentProvider,
    sessionThinkingAvailable,
  );

  /**
   * Get translated text for reasoning level
   */
  const getReasoningText = (levelId: ReasoningEffort, field: 'label' | 'description') => {
    const key = `reasoning.${levelId}.${field}`;
    const fallback = REASONING_LEVELS.find(l => l.id === levelId)?.[field] || levelId;
    return t(key, { defaultValue: fallback });
  };

  /**
   * Toggle dropdown
   */
  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (disabled) return;
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) {
      recalculate();
    }
  }, [isOpen, disabled, recalculate]);

  /**
   * Select reasoning level
   */
  const handleSelect = useCallback((effort: ReasoningEffort) => {
    onChange(effort);
    setIsOpen(false);
    onClose?.();
  }, [onChange, onClose]);

  /**
   * Close on outside click
   */
  useEffect(() => {
    if (embedded || !isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [embedded, isOpen]);

  useLayoutEffect(() => {
    if (embedded || isOpen) {
      recalculate();
    }
  }, [embedded, isOpen, recalculate]);

  if (!isVisible) return null;
  if (!currentLevel) return null;

  const dropdownStyle: React.CSSProperties = embedded
    ? {
        minWidth: 0,
        maxWidth: maxWidth ?? 280,
        ...(maxHeight != null
          ? { maxHeight: `${maxHeight}px`, overflowY: 'auto' as const }
          : { overflowY: 'visible' as const }),
        ...positionedStyle,
      }
    : { ...DROPDOWN_STYLE, ...positionedStyle };

  const renderDropdown = () => (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          data-testid="reasoning-selector-dropdown"
          style={dropdownStyle}
          onMouseEnter={(e) => e.stopPropagation()}
        >
          {availableLevels.map((level) => (
            <div
              key={level.id}
              className={`selector-option ${level.id === value ? 'selected' : ''}`}
              onClick={() => handleSelect(level.id)}
              title={getReasoningText(level.id, 'description')}
            >
              <ReasoningGaugeIcon level={REASONING_GAUGE_LEVEL[level.id]} size={16} />
              <div style={LEVEL_INFO_STYLE}>
                <span>{getReasoningText(level.id, 'label')}</span>
                <span className="mode-description">{getReasoningText(level.id, 'description')}</span>
              </div>
              {level.id === value && (
                <CheckIcon size={16} className="check-mark" />
              )}
            </div>
          ))}
        </div>
  );

  if (embedded) {
    return renderDropdown();
  }

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        disabled={disabled}
        title={t('reasoning.title', { defaultValue: 'Select reasoning depth' })}
      >
        <span className="codicon codicon-lightbulb" />
        <span className="selector-button-text">{getReasoningText(currentLevel.id, 'label')}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && renderDropdown()}
    </div>
  );
};

