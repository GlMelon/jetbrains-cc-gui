import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { WaveText } from './WaveText';

interface WaitingIndicatorProps {
  queueAheadCount?: number;
  loading?: boolean;
  onExitComplete?: () => void;
}

type AnimationPhase = 'entering' | 'unmounting';

export const WaitingIndicator = ({
  queueAheadCount = 0,
  loading = true,
  onExitComplete,
}: WaitingIndicatorProps) => {
  const { t } = useTranslation();
  const [phase, setPhase] = useState<AnimationPhase>('entering');

  // ── Refs for timer cleanup ──
  const transitionTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const prevLoadingRef = useRef(loading);

  // ── Cleanup timer on unmount ──
  useEffect(() => {
    return () => {
      if (transitionTimerRef.current) {
        clearTimeout(transitionTimerRef.current);
      }
    };
  }, []);

  // ── Animation state machine ──
  useEffect(() => {
    const wasLoading = prevLoadingRef.current;
    prevLoadingRef.current = loading;

    // Case 1: loading → false  →  container exit
    if (wasLoading && !loading) {
      if (transitionTimerRef.current) {
        clearTimeout(transitionTimerRef.current);
      }
      setPhase('unmounting');
      transitionTimerRef.current = setTimeout(() => {
        onExitComplete?.();
      }, 250);
    }
  }, [loading, onExitComplete]);

  // ── CSS class resolution ──
  const containerClass = [
    'waiting-indicator',
    'waiting-indicator-queued',
    phase === 'unmounting' ? 'waiting-indicator-exit' : '',
  ].filter(Boolean).join(' ');

  const contentEnterClass = 'queue-pill-enter';
  const contentClass = [phase === 'entering' ? contentEnterClass : '']
    .filter(Boolean).join(' ');

  // ── Render ──
  return (
    <div className={containerClass}>
      <div className={`generating-strip queued ${contentClass}`}>
        <div className="gen-orb" />
        <span className="gen-text">
          <WaveText
            text={t('chat.queueWaiting', { count: queueAheadCount })}
            delay={80}
          />
        </span>
      </div>
    </div>
  );
};

export default WaitingIndicator;
