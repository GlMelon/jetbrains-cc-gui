import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from '../../../i18n/config';
import { AVAILABLE_MODES, type PermissionMode } from '../types';
import { ChatIcon, CheckIcon, CompassIcon, RobotIcon, ZapIcon } from '../../Icons';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';

const RELATIVE_INLINE_BLOCK_STYLE: React.CSSProperties = { position: 'relative', display: 'inline-block' };
const CHEVRON_ICON_STYLE: React.CSSProperties = { marginLeft: '2px' };
const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  zIndex: 10000,
  maxWidth: 'calc(100vw - 16px)',
  overflowX: 'hidden',
};
const MODE_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0, overflow: 'hidden' };
const MODE_TEXT_STYLE: React.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' };

// OpenCode 系列(opencode/grok/kimi/pi 共用 OpenCode CLI 内核)无 plan/acceptEdits 能力:
// 这两个模式不传 flag,由 opencode.json 原生权限配置(allow/ask/deny)管控。
// 在下拉项右侧标「原生管控」徽标,让"选了但不传 flag"的行为对用户可见。
const OPENCODE_FAMILY = new Set(['opencode', 'grok', 'kimi', 'pi']);

function getModeOptionStyle(disabled: boolean): React.CSSProperties {
  return {
    opacity: disabled ? 0.5 : 1,
    cursor: disabled ? 'not-allowed' : 'pointer',
  };
}

interface ModeSelectProps {
  value: PermissionMode;
  onChange: (mode: PermissionMode) => void;
  provider?: string;
}

// Map mode ID to SVG icon component.
// 方案一(Claude 官方风格):default=对话气泡 / plan=罗盘 / acceptEdits(Agent)=机器人 /
// bypassPermissions(Auto)=闪电。单色 currentColor,着色由 .selector-button CSS 统一处理
// (bypassPermissions 额外有 .mode-auto-active 橙色高亮,呼应 Auto 警示语义)。
function getModeIcon(modeId: PermissionMode) {
  switch (modeId) {
    case 'default':
      return <ChatIcon size={14} />;
    case 'plan':
      return <CompassIcon size={14} />;
    case 'acceptEdits':
    case 'autoEdit': // acceptEdits 历史别名(C2 值域对齐),UI 同为 Agent=机器人
      return <RobotIcon size={14} />;
    case 'bypassPermissions':
      return <ZapIcon size={14} />;
    default:
      return <ChatIcon size={14} />;
  }
}

/**
 * ModeSelect - Mode selector component
 * Supports switching between default, agent, plan, and auto modes
 */
export const ModeSelect = ({ value, onChange, provider }: ModeSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, recalculate } = useDropdownPosition({
    buttonRef,
    dropdownRef,
    isOpen,
    preferredAlignment: 'right',
  });

  const modeOptions = useMemo(() => {
    if (provider === 'codex') {
      return AVAILABLE_MODES;
    }
    return AVAILABLE_MODES;
  }, [provider]);

  const currentMode = modeOptions.find(m => m.id === value) || modeOptions[0];

  // Helper function to get translated mode text
  const getModeText = (modeId: PermissionMode, field: 'label' | 'shortLabel' | 'tooltip' | 'description') => {
    if (provider === 'codex') {
      const codexKey = `codexModes.${modeId}.${field}`;
      const fallbackKey = `modes.${modeId}.${field}`;
      if (field === 'shortLabel') {
        return t(codexKey, { defaultValue: t(fallbackKey, { defaultValue: t(`codexModes.${modeId}.label`) }) });
      }
      return t(codexKey, { defaultValue: t(fallbackKey) });
    }
    if (provider === 'omp') {
      const ompKey = `ompModes.${modeId}.${field}`;
      if (i18n.exists(ompKey)) return t(ompKey);
      const fallbackKey = `modes.${modeId}.${field}`;
      if (i18n.exists(fallbackKey)) return t(fallbackKey);
      if (field === 'shortLabel' && i18n.exists(`ompModes.${modeId}.label`)) return t(`ompModes.${modeId}.label`);
      if (field === 'shortLabel' && i18n.exists(`modes.${modeId}.label`)) return t(`modes.${modeId}.label`);
      // Dynamic role with no i18n entry: show the raw ModeInfo strings
      // (capitalized role id / resolved model selector).
      const info = modeOptions.find((mode) => mode.id === modeId);
      if (field === 'label' || field === 'shortLabel') return info?.label ?? modeId;
      return info?.[field] ?? info?.description ?? '';
    }

    if (field === 'shortLabel') {
      return t(`modes.${modeId}.shortLabel`, { defaultValue: t(`modes.${modeId}.label`) });
    }
    return t(`modes.${modeId}.${field}`);
  };

  /**
   * Toggle dropdown
   */
  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) {
      recalculate();
    }
  }, [isOpen, recalculate]);

  /**
   * Select mode
   */
  const handleSelect = useCallback((mode: PermissionMode, disabled?: boolean) => {
    if (disabled) return; // Disabled options cannot be selected
    onChange(mode);
    setIsOpen(false);
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
  }, [isOpen, recalculate]);

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className={`selector-button${value === 'bypassPermissions' ? ' mode-auto-active' : ' mode-active-highlight'}`}
        onClick={handleToggle}
        title={getModeText(currentMode.id, 'tooltip') || `${t('chat.currentMode', { mode: getModeText(currentMode.id, 'label') })}`}
      >
        <span className={`codicon ${currentMode.icon}`} />
        <span className="selector-button-text">{getModeText(currentMode.id, 'shortLabel')}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{ ...DROPDOWN_STYLE, ...positionedStyle }}
        >
          {modeOptions.map((mode) => (
            <div
              key={mode.id}
              data-testid={`mode-option-${mode.id}`}
              className={`selector-option ${mode.id === value ? 'selected' : ''} ${mode.disabled ? 'disabled' : ''}`}
              onClick={() => handleSelect(mode.id, mode.disabled)}
              title={getModeText(mode.id, 'tooltip')}
              style={getModeOptionStyle(!!mode.disabled)}
            >
              {getModeIcon(mode.id)}
              <div style={MODE_INFO_STYLE}>
                <span style={MODE_TEXT_STYLE}>{getModeText(mode.id, 'label')}</span>
                <span className="mode-description" style={MODE_TEXT_STYLE}>{getModeText(mode.id, 'description')}</span>
              </div>
              {OPENCODE_FAMILY.has(provider || '') && (mode.id === 'plan' || mode.id === 'acceptEdits') && (
                <span className="mode-native-badge" title={t('modes.nativeBadgeTooltip')}>
                  {t('modes.nativeBadge')}
                </span>
              )}
              {mode.id === value && (
                <CheckIcon size={14} className="check-mark" />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

