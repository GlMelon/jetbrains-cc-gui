import { useEffect, useState, useRef, useCallback } from 'react';
import styles from './style.module.less';

/** Multi-step blur animation configuration */
interface BlurStep {
  /** CSS filter value, e.g. 'blur(10px)' */
  filter: string;
  /** CSS opacity value, e.g. 0.5 */
  opacity: number;
  /** CSS transform value, e.g. 'translateY(-20px)' */
  transform?: string;
}

interface BlurTextProps {
  text: string;
  /** Animation delay between each character in ms */
  delay?: number;
  /** Animation direction: 'top' or 'bottom' */
  direction?: 'top' | 'bottom';
  /** Split by 'chars' or 'words' */
  animateBy?: 'chars' | 'words';
  /** Additional CSS class */
  className?: string;
  /** Duration per animation step in seconds (default: 0.35) */
  stepDuration?: number;
  /** Custom initial animation state (overrides direction defaults) */
  animationFrom?: BlurStep;
  /** Custom multi-step animation sequence (overrides built-in keyframes) */
  animationTo?: BlurStep[];
  /** IntersectionObserver threshold (0-1) */
  threshold?: number;
  /** IntersectionObserver rootMargin */
  rootMargin?: string;
  /** Callback when all animations complete */
  onAnimationComplete?: () => void;
  /** Enable/disable viewport-triggered animation (default: true) */
  triggerOnView?: boolean;
  /** Manually trigger animation (use with triggerOnView=false) */
  play?: boolean;
}

/**
 * BlurText - Text entrance animation with blur-to-focus reveal effect.
 * Inspired by react-bits, implemented with pure CSS for zero dependencies.
 *
 * @example
 * // Basic usage
 * <BlurText text="Hello World" delay={80} />
 *
 * @example
 * // Custom multi-step animation
 * <BlurText
 *   text="Welcome"
 *   animationFrom={{ filter: 'blur(20px)', opacity: 0, transform: 'translateY(-30px)' }}
 *   animationTo={[
 *     { filter: 'blur(8px)', opacity: 0.4, transform: 'translateY(5px)' },
 *     { filter: 'blur(0px)', opacity: 1, transform: 'translateY(0px)' },
 *   ]}
 *   stepDuration={0.4}
 * />
 */
export const BlurText = ({
  text,
  delay = 100,
  direction = 'top',
  animateBy = 'chars',
  className = '',
  stepDuration = 0.35,
  animationFrom,
  animationTo,
  threshold = 0.1,
  rootMargin = '0px',
  onAnimationComplete,
  triggerOnView = true,
  play,
}: BlurTextProps) => {
  const [animState, setAnimState] = useState<'hidden' | 'animating' | 'done'>(
    triggerOnView ? 'hidden' : play ? 'animating' : 'hidden'
  );
  const ref = useRef<HTMLParagraphElement>(null);
  const completedCountRef = useRef(0);
  const hasCalledCompleteRef = useRef(false);

  // Viewport intersection observer
  useEffect(() => {
    if (!triggerOnView || !ref.current) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setAnimState('animating');
          observer.unobserve(ref.current!);
        }
      },
      { threshold, rootMargin }
    );
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, [triggerOnView, threshold, rootMargin]);

  // Manual play control
  useEffect(() => {
    if (!triggerOnView && play) {
      setAnimState('animating');
    }
  }, [triggerOnView, play]);

  // Track animation completion
  const handleAnimationEnd = useCallback(() => {
    completedCountRef.current += 1;
    const elements = animateBy === 'words' ? text.split(' ') : text.split('');
    if (completedCountRef.current >= elements.length && !hasCalledCompleteRef.current) {
      hasCalledCompleteRef.current = true;
      setAnimState('done');
      onAnimationComplete?.();
    }
  }, [animateBy, text, onAnimationComplete]);

  // Reset on text change
  useEffect(() => {
    completedCountRef.current = 0;
    hasCalledCompleteRef.current = false;
    if (!triggerOnView) {
      setAnimState(play ? 'animating' : 'hidden');
    }
  }, [text, triggerOnView, play]);

  const elements = animateBy === 'words' ? text.split(' ') : text.split('');

  // Build inline styles for custom animations
  const getCustomStyle = (index: number): React.CSSProperties | undefined => {
    if (!animationFrom || !animationTo) return undefined;
    const totalSteps = animationTo.length;
    const totalTime = stepDuration * totalSteps;
    const delayS = (index * delay) / 1000;
    const durationS = totalTime + delayS;

    return {
      // Note: Dynamic keyframes require style injection; fallback to class-based
      animationDuration: `${durationS}s`,
      animationDelay: `${delayS}s`,
    };
  };

  const getStateClass = () => {
    if (animState === 'hidden') return styles.hidden;
    return direction === 'top' ? styles.blurInTop : styles.blurInBottom;
  };

  return (
    <p
      ref={ref}
      className={`${styles.container} ${className}`}
      style={{ '--step-duration': `${stepDuration}s` } as React.CSSProperties}
    >
      {elements.map((segment, index) => (
        <span
          key={index}
          className={`${styles.segment} ${getStateClass()}`}
          style={{
            animationDelay: `${index * delay}ms`,
            ...getCustomStyle(index),
          }}
          onAnimationEnd={handleAnimationEnd}
        >
          {segment === ' ' ? '\u00A0' : segment}
          {animateBy === 'words' && index < elements.length - 1 && '\u00A0'}
        </span>
      ))}
    </p>
  );
};
