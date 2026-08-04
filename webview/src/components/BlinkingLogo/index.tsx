import { useEffect, useState, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { motion, AnimatePresence } from 'motion/react';
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

  const prevProviderRef = useRef(provider);
  const prevModelIdRef = useRef(modelId);

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

  const blinkVariants = {
    idle: { scaleY: 1, opacity: 1 },
    closing: { scaleY: 0.05, opacity: 0.3 },
    opening: { scaleY: 1, opacity: 1 },
  };

  return (
    <div style={ROOT_STYLE}>
      <motion.div
        ref={containerRef}
        className={styles.container}
        onClick={handleToggle}
        style={logoStyle}
        variants={blinkVariants}
        animate={animationState}
        transition={{ duration: 0.2, ease: 'easeInOut' }}
      >
        <ProviderModelIcon
          providerId={provider}
          modelId={modelId}
          size={provider === 'codex' ? 64 : 58}
          colored
        />
      </motion.div>

      <AnimatePresence>
        {isOpen && (
          <motion.div
            ref={dropdownRef}
            className="selector-dropdown"
            style={DROPDOWN_STYLE}
            initial={{ opacity: 0, y: -8, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -8, scale: 0.95 }}
            transition={{ duration: 0.15, ease: 'easeOut' }}
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
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {showToast && (
          <motion.div
            className="selector-toast"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.2, ease: 'easeOut' }}
          >
            {toastMessage}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};
