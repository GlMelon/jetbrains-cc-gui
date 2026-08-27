import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DSH_PRESETS, getUserDshPresetOptions } from '../types';
import { CheckIcon, RobotIcon } from '../../Icons';
import { useDropdownPosition } from '../../../hooks/useDropdownPosition';

/** 内置 preset id 集合(DSH_PRESETS 常量),用于区分 i18n 内置文案与用户 preset。 */
const BUILT_IN_PRESETS = new Set<string>(DSH_PRESETS);
const CHEVRON_ICON_STYLE: React.CSSProperties = { marginLeft: '2px' };
const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  zIndex: 10000,
  maxWidth: 'calc(100vw - 16px)',
  overflowX: 'hidden',
};
const PRESET_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0, overflow: 'hidden' };
const PRESET_TEXT_STYLE: React.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' };

interface DshPresetSelectProps {
  value: string;
  onChange: (preset: string) => void;
  embedded?: boolean;
  triggerRef?: React.RefObject<HTMLElement | null>;
  onClose?: () => void;
}

export const DshPresetSelect = ({
  value,
  onChange,
  embedded = false,
  triggerRef,
  onClose,
}: DshPresetSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const { positionedStyle, maxHeight, maxWidth, recalculate } = useDropdownPosition({
    buttonRef: (embedded ? triggerRef : buttonRef) as React.RefObject<HTMLElement | null>,
    dropdownRef,
    minWidth: 260,
    maxWidth: 360,
    submenu: embedded,
  });

  const presetOptions = useMemo(() => getUserDshPresetOptions(), []);

  const getPresetLabel = useCallback((id: string): string => {
    if (!id) {
      return t('dshPresets.none.label', { defaultValue: 'Default' });
    }
    if (BUILT_IN_PRESETS.has(id)) {
      return t(`dshPresets.${id}.label`, { defaultValue: id });
    }
    return id;
  }, [t]);

  const getPresetDescription = useCallback((id: string): string => {
    if (!id) {
      return t('dshPresets.none.description', { defaultValue: '' });
    }
    if (BUILT_IN_PRESETS.has(id)) {
      return t(`dshPresets.${id}.description`, { defaultValue: '' });
    }
    return t('dshPresets.user.description', { defaultValue: '' });
  }, [t]);

  const allOptions = useMemo(
    () => presetOptions.map((opt) => ({
      ...opt,
      label: getPresetLabel(opt.id),
      description: getPresetDescription(opt.id),
    })),
    [presetOptions, getPresetLabel, getPresetDescription],
  );

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) {
      recalculate();
    }
  }, [isOpen, recalculate]);

  const handleSelect = useCallback((preset: string) => {
    onChange(preset);
    setIsOpen(false);
    onClose?.();
  }, [onChange, onClose]);

  useEffect(() => {
    if (embedded || !isOpen) return undefined;

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

  const dropdownStyle: React.CSSProperties = embedded
    ? {
        minWidth: 0,
        maxWidth: maxWidth ?? 360,
        ...(maxHeight != null
          ? { maxHeight: `${Math.min(300, maxHeight)}px`, overflowY: 'auto' as const }
          : { overflowY: 'visible' as const }),
        ...positionedStyle,
      }
    : { ...DROPDOWN_STYLE, ...positionedStyle };

  const renderDropdown = () => (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          data-testid="dsh-preset-dropdown"
          style={dropdownStyle}
          onMouseEnter={(event) => event.stopPropagation()}
        >
          {allOptions.map((opt) => (
            <div
              key={opt.id || 'none'}
              className={`selector-option ${opt.id === value ? 'selected' : ''}`}
              onClick={() => handleSelect(opt.id)}
              title={opt.description}
            >
              <RobotIcon size={14} />
              <div style={PRESET_INFO_STYLE}>
                <span style={PRESET_TEXT_STYLE}>{opt.label}</span>
                {opt.description && (
                  <span className="mode-description" style={PRESET_TEXT_STYLE}>{opt.description}</span>
                )}
              </div>
              {opt.id === value && (
                <CheckIcon size={14} className="check-mark" />
              )}
            </div>
          ))}
        </div>
  );

  if (embedded) {
    return renderDropdown();
  }

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title={getPresetDescription(value)}
      >
        <span className="codicon codicon-robot" />
        <span className="selector-button-text">{getPresetLabel(value)}</span>
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && renderDropdown()}
    </div>
  );
};
