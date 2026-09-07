import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { OPENCODE_PERMISSION_FAMILY } from '../../../hooks/providers/cliProviders';
import { useTranslation } from 'react-i18next';
import i18n from '../../../i18n/config';
import { AVAILABLE_MODES, type ModeInfo, type ModelInfo, type PermissionMode } from '../types';
import { ChatIcon, CheckIcon, CompassIcon, RobotIcon, ShieldIcon, ZapIcon } from '../../Icons';
import { useOmpRoles } from '../../../hooks/providers/useCliModels';
import { NATIVE_AUTO_APPROVAL_PROVIDERS } from '../../../hooks/providers/cliProviders';
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
const OPENCODE_FAMILY = OPENCODE_PERMISSION_FAMILY; // 分类表 opencodePermissionFamily 维度(cliProviders.ts SSOT)

/** Icons for the well-known omp roles; any other dynamic role gets a sparkle. */
const OMP_ROLE_ICONS: Record<string, string> = {
  smol: 'codicon-zap',
  slow: 'codicon-lightbulb',
  plan: 'codicon-tasklist',
};

/**
 * Maps a dynamic omp role (listModels payload) to a ModeInfo. Label is the
 * capitalized role id; description/tooltip carry the resolved model selector.
 * Roles with an ompModes.* i18n entry get translated text via getModeText.
 */
function roleToModeInfo(role: ModelInfo): ModeInfo {
  return {
    // role id 是 omp 的动态 mode 值(后端 SessionState 白名单含 smol/slow/plan;
    // 动态 role 透传),PermissionMode 静态类型之外,与 ompModeForModelId 同样按 cast 处理。
    id: role.id as PermissionMode,
    label: role.id.charAt(0).toUpperCase() + role.id.slice(1),
    icon: OMP_ROLE_ICONS[role.id] ?? 'codicon-sparkle',
    description: role.description,
    tooltip: role.description,
  };
}

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
// auto(原生自动审批)=盾牌 / bypassPermissions(Full Auto)=闪电。单色 currentColor,
// 着色由 .selector-button CSS 统一处理
// (bypassPermissions 额外有 .mode-full-auto-active 橙色高亮,呼应 Full Auto 警示语义)。
function getModeIcon(modeId: PermissionMode) {
  switch (modeId) {
    case 'default':
      return <ChatIcon size={14} />;
    case 'plan':
      return <CompassIcon size={14} />;
    case 'acceptEdits':
    case 'autoEdit': // acceptEdits 历史别名(C2 值域对齐),UI 同为 Agent=机器人
      return <RobotIcon size={14} />;
    case 'auto':
      return <ShieldIcon size={14} />;
    case 'bypassPermissions':
      return <ZapIcon size={14} />;
    default:
      return <ChatIcon size={14} />;
  }
}

/**
 * ModeSelect - Mode selector component
 * Supports switching between default, agent, provider-native auto, plan, and
 * Full Auto modes; for the omp provider the menu lists Default plus the
 * dynamic model roles.
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

  // Dynamic omp model roles (subscribed unconditionally per hook rules; only
  // consumed for provider 'omp'). Static smol/slow/plan until roles load.
  const ompRoles = useOmpRoles();

  const modeOptions = useMemo(() => {
    if (provider === 'omp') {
      // OMP model-role modes: [Default, ...roles]. Roles are dynamic from the
      // listModels payload, falling back to static smol/slow/plan (460a62b5
      // 语义:roles 只出现在 mode selector,不进模型下拉)。
      const defaultMode = AVAILABLE_MODES.find((mode) => mode.id === 'default');
      const roleModes = ompRoles.map(roleToModeInfo);
      return defaultMode ? [defaultMode, ...roleModes] : roleModes;
    }
    const effectiveProvider = provider ?? 'claude';
    return AVAILABLE_MODES.filter((mode) => {
      // plan 仅 claude 有真实等价物;后端(SessionSendService)对其它 provider
      // 统一降级为 default,菜单不暴露(omp 的 plan 是 model role,走上方分支)。
      if (mode.id === 'plan') return effectiveProvider === 'claude';
      // auto(原生自动审批)仅 claude/codex/grok 可用,静态集合镜像后端降级规则。
      if (mode.id === 'auto') return NATIVE_AUTO_APPROVAL_PROVIDERS.has(effectiveProvider);
      return true;
    });
  }, [provider, ompRoles]);

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
        className={`selector-button${value === 'bypassPermissions' ? ' mode-full-auto-active' : ''}`}
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

