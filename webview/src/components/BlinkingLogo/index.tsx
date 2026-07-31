import { useEffect, useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';
import { AVAILABLE_PROVIDERS } from '../ChatInputBox/types';
import { ProviderModelIcon } from '../shared/ProviderModelIcon';
import { CheckIcon } from '../Icons';

const ROOT_STYLE: React.CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
  flexDirection: 'column',
  alignItems: 'center',
};

const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  top: '100%',
  left: '50%',
  transform: 'translateX(-50%)',
  marginTop: '8px',
  zIndex: 10000,
};

function getProviderOptionStyle(enabled: boolean): React.CSSProperties {
  return {
    opacity: enabled ? 1 : 0.5,
    cursor: enabled ? 'pointer' : 'not-allowed',
  };
}

interface BlinkingLogoProps {
  provider: string;
  /** Current model ID, used to show vendor-specific icon */
  modelId?: string;
  onProviderChange?: (providerId: string) => void;
}

export const BlinkingLogo = ({ provider, modelId, onProviderChange }: BlinkingLogoProps) => {
  const { t } = useTranslation();
  const [animationState, setAnimationState] = useState<'idle' | 'closing' | 'opening'>('idle');

  // Track previous provider/model only to detect changes and trigger the blink
  // animation. The icon itself renders DIRECTLY from props (always reflects the
  // current provider) — these refs are NOT a mirrored display state.
  //
  // The prior displayProvider/displayModelId mirror synced via a 200ms setTimeout
  // and could desync from props during a switch: Claude switches round-trip a
  // MODEL_SELECTION event (plus a longContext re-negotiation), so modelId changes
  // again inside the closing window, and effect 2's timer (which depended on
  // [animationState, provider, modelId]) got cleared/reset, leaving the local
  // state stuck on the previous Codex modelId. That stale 'gpt-5-codex' then won
  // over providerId='claude' in resolveIconVendor() (modelId has higher priority)
  // and rendered the OpenAI/Codex icon even after switching to Claude.
  const prevProviderRef = useRef(provider);
  const prevModelIdRef = useRef(modelId);

  // Dropdown state
  const [isOpen, setIsOpen] = useState(false);
  const [showToast, setShowToast] = useState(false);
  const [toastMessage, setToastMessage] = useState('');
  const dropdownRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (provider !== prevProviderRef.current || modelId !== prevModelIdRef.current) {
      prevProviderRef.current = provider;
      prevModelIdRef.current = modelId;
      if (animationState === 'idle' || animationState === 'opening') {
        setAnimationState('closing');
      }
    }
  }, [provider, modelId, animationState]);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>;

    if (animationState === 'closing') {
      timer = setTimeout(() => setAnimationState('opening'), 200);
    } else if (animationState === 'opening') {
      timer = setTimeout(() => setAnimationState('idle'), 200);
    }

    return () => {
      if (timer) clearTimeout(timer);
    };
  }, [animationState]);

  // Click outside handler
  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  const handleToggle = (e: React.MouseEvent) => {
    if (onProviderChange) {
       e.stopPropagation();
       setIsOpen(!isOpen);
    }
  };

  const showToastMessage = (message: string) => {
    setToastMessage(message);
    setShowToast(true);
    setTimeout(() => {
      setShowToast(false);
    }, 1500);
  };

  const handleSelect = (providerId: string) => {
    const provider = AVAILABLE_PROVIDERS.find(p => p.id === providerId);
    if (!provider) return;

    if (!provider.enabled) {
      showToastMessage(t('settings.provider.featureComingSoon'));
      setIsOpen(false);
      return;
    }

    if (onProviderChange) {
      onProviderChange(providerId);
    }
    setIsOpen(false);
  };

  const getProviderLabel = (providerId: string) => {
    return t(`providers.${providerId}.label`);
  };

  const logoStyle: React.CSSProperties = {
    cursor: onProviderChange ? 'pointer' : 'default',
  };

  return (
    <div style={ROOT_STYLE}>
      <div
        ref={containerRef}
        className={`${styles.container} ${styles[animationState]}`}
        onClick={handleToggle}
        style={logoStyle}
      >
        <ProviderModelIcon
          providerId={provider}
          modelId={modelId}
          size={provider === 'codex' ? 64 : 58}
          colored
        />
      </div>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={DROPDOWN_STYLE}
        >
          {AVAILABLE_PROVIDERS.map((p) => (
            <div
              key={p.id}
              className={`selector-option ${p.id === provider ? 'selected' : ''} ${!p.enabled ? 'disabled' : ''}`}
              onClick={(e) => {
                e.stopPropagation();
                handleSelect(p.id);
              }}
              style={getProviderOptionStyle(!!p.enabled)}
            >
              <ProviderModelIcon providerId={p.id} size={16} colored />
              <span>{getProviderLabel(p.id)}</span>
              {p.id === provider && (
                <CheckIcon size={16} className="check-mark" />
              )}
            </div>
          ))}
        </div>
      )}

      {/* Toast notification */}
      {showToast && (
        <div className="selector-toast">
          {toastMessage}
        </div>
      )}
    </div>
  );
};
