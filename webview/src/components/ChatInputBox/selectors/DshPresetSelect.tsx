import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DSH_PRESETS, getUserDshPresetOptions } from '../types';
import { CheckIcon, ChevronDownIcon, ChevronUpIcon, RobotIcon } from '../../Icons';
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
const PRESET_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', flex: 1, minWidth: 0, overflow: 'hidden' };
const PRESET_TEXT_STYLE: React.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' };

interface DshPresetSelectProps {
  dshPreset: string;
  onDshPresetChange: (preset: string) => void;
}

const BUILT_IN_PRESETS = new Set<string>(DSH_PRESETS);

/**
 * DshPresetSelect - DSH agent preset selector (dsh provider only).
 *
 * Shows a "Default" (none) option plus built-in presets (DSH_PRESETS) and any
 * user-installed presets discovered from the DSH home (window.__INITIAL_DSH_PRESETS__).
 * Labels/descriptions are resolved via i18n keys `dshPresets.{id}.label` /
 * `dshPresets.{id}.description`; user presets fall back to the raw id and
 * `dshPresets.user.description`.
 */
export const DshPresetSelect = ({ dshPreset, onDshPresetChange }: DshPresetSelectProps) => {
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

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    const nextOpen = !isOpen;
    setIsOpen(nextOpen);
    if (nextOpen) {
      recalculate();
    }
  }, [isOpen, recalculate]);

  const handleSelect = useCallback((preset: string) => {
    onDshPresetChange(preset);
    setIsOpen(false);
  }, [onDshPresetChange]);

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

  const allOptions = useMemo(() => [
    { id: '', label: getPresetLabel(''), description: getPresetDescription('') },
    ...presetOptions.map((opt) => ({
      id: opt.id,
      label: getPresetLabel(opt.id),
      description: getPresetDescription(opt.id),
    })),
  ], [presetOptions, getPresetLabel, getPresetDescription]);

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button mode-active-highlight"
        onClick={handleToggle}
        title={t('dshPresets.title')}
      >
        <RobotIcon size={14} />
        <span className="selector-button-text">{getPresetLabel(dshPreset)}</span>
        {isOpen ? <ChevronUpIcon size={14} style={CHEVRON_ICON_STYLE} /> : <ChevronDownIcon size={14} style={CHEVRON_ICON_STYLE} />}
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={{ ...DROPDOWN_STYLE, ...positionedStyle }}
        >
          {allOptions.map((opt) => (
            <div
              key={opt.id || 'none'}
              className={`selector-option ${opt.id === dshPreset ? 'selected' : ''}`}
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
              {opt.id === dshPreset && (
                <CheckIcon size={14} className="check-mark" />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
