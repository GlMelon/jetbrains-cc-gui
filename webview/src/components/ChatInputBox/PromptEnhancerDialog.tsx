import { useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckIcon, EditIcon, SparklesIcon, CloseIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';
import { FadeContent } from '../react-bits';

interface PromptEnhancerDialogProps {
  isOpen: boolean;
  isLoading: boolean;
  originalPrompt: string;
  enhancedPrompt: string;
  onUseEnhanced: () => void;
  onKeepOriginal: () => void;
  onClose: () => void;
}

/**
 * PromptEnhancerDialog - Prompt enhancement dialog
 * Displays original and enhanced prompts, letting the user choose which version to use
 */
export const PromptEnhancerDialog = ({
  isOpen,
  isLoading,
  originalPrompt,
  enhancedPrompt,
  onUseEnhanced,
  onKeepOriginal,
  onClose,
}: PromptEnhancerDialogProps) => {
  const { t } = useTranslation();

  // Handle keyboard events
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      onClose();
    } else if (e.key === 'Enter' && !isLoading && enhancedPrompt) {
      e.preventDefault();
      onUseEnhanced();
    }
  }, [onClose, onUseEnhanced, isLoading, enhancedPrompt]);

  useEffect(() => {
    if (isOpen) {
      window.addEventListener('keydown', handleKeyDown);
      return () => window.removeEventListener('keydown', handleKeyDown);
    }
  }, [isOpen, handleKeyDown]);

  if (!isOpen) {
    return null;
  }

  // Close on overlay click
  const handleOverlayClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <FadeContent duration={160} offset={0}>
      <div className="prompt-enhancer-overlay" onClick={handleOverlayClick}>
        <FadeContent duration={200} offset={12}>
          <div className="prompt-enhancer-dialog" onClick={(e) => e.stopPropagation()}>
            {/* Header */}
            <div className="prompt-enhancer-header">
              <div className="prompt-enhancer-title">
                <SparklesIcon size={16} />
                <h3>{t('promptEnhancer.title')}</h3>
              </div>
              <button className="prompt-enhancer-close" onClick={onClose}>
                <CloseIcon size={16} />
              </button>
            </div>

            {/* Content area */}
            <div className="prompt-enhancer-content">
              {/* Original prompt */}
              <div className="prompt-section">
                <div className="prompt-section-header">
                  <EditIcon size={16} />
                  <span>{t('promptEnhancer.originalPrompt')}</span>
                </div>
                <div className="prompt-text original-prompt">
                  {originalPrompt}
                </div>
              </div>

              {/* Enhanced prompt */}
              <div className="prompt-section">
                <div className="prompt-section-header">
                  <SparklesIcon size={16} />
                  <span>{t('promptEnhancer.enhancedPrompt')}</span>
                </div>
                <div className="prompt-text enhanced-prompt">
                  {isLoading ? (
                    <div className="prompt-loading">
                      <UnifiedLoader type="wave" size={16} />
                      <span>{t('promptEnhancer.enhancing')}</span>
                    </div>
                  ) : (
                    enhancedPrompt || t('promptEnhancer.enhancing')
                  )}
                </div>
              </div>
            </div>

            {/* Footer buttons */}
            <div className="prompt-enhancer-footer">
              <button
                className="prompt-enhancer-btn secondary"
                onClick={onKeepOriginal}
                disabled={isLoading}
              >
                <CloseIcon size={16} />
                {t('promptEnhancer.keepOriginal')}
              </button>
              <button
                className="prompt-enhancer-btn primary"
                onClick={onUseEnhanced}
                disabled={isLoading || !enhancedPrompt}
              >
                <CheckIcon size={16} />
                {t('promptEnhancer.useEnhanced')}
              </button>
            </div>
          </div>
        </FadeContent>
      </div>
    </FadeContent>
  );
};
