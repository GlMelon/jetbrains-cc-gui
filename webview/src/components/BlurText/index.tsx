import { useEffect, useState, useRef, useMemo } from 'react';
import { motion, type Transition } from 'motion/react';
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
 * Build keyframes from start and end states
 */
const buildKeyframes = (
  from: Record<string, string | number>,
  steps: Array<Record<string, string | number>>
): Record<string, Array<string | number>> => {
  const keys = new Set<string>([...Object.keys(from), ...steps.flatMap((s) => Object.keys(s))]);

  const keyframes: Record<string, Array<string | number>> = {};
  keys.forEach((k) => {
    keyframes[k] = [from[k], ...steps.map((s) => s[k])];
  });
  return keyframes;
};

/**
 * BlurText - Text entrance animation with blur-to-focus reveal effect.
 * Based on react-bits implementation, enhanced with project-specific APIs.
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
  const [inView, setInView] = useState(!triggerOnView);
  const ref = useRef<HTMLParagraphElement>(null);
  const completedCountRef = useRef(0);
  const hasCalledCompleteRef = useRef(false);

  // Viewport intersection observer
  useEffect(() => {
    if (!triggerOnView || !ref.current) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
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
      setInView(true);
    }
  }, [triggerOnView, play]);

  // Default animation states based on direction
  const defaultFrom = useMemo(
    () =>
      direction === 'top'
        ? { filter: 'blur(10px)', opacity: 0, y: -50 }
        : { filter: 'blur(10px)', opacity: 0, y: 50 },
    [direction]
  );

  const defaultTo = useMemo(
    () => [
      {
        filter: 'blur(5px)',
        opacity: 0.5,
        y: direction === 'top' ? 5 : -5,
      },
      { filter: 'blur(0px)', opacity: 1, y: 0 },
    ],
    [direction]
  );

  // Convert custom animation props to motion format
  const fromSnapshot = useMemo(() => {
    if (!animationFrom) return defaultFrom;
    // Convert transform string to motion-compatible format
    const { transform, ...rest } = animationFrom;
    if (!transform) return rest;

    // Parse translateY
    const translateYMatch = transform.match(/translateY\(([^)]+)\)/);
    if (translateYMatch) {
      const yValue = parseFloat(translateYMatch[1]);
      return { ...rest, y: yValue };
    }
    return rest;
  }, [animationFrom, defaultFrom]);

  const toSnapshots = useMemo(() => {
    if (!animationTo) return defaultTo;
    return animationTo.map(({ transform, ...rest }) => {
      if (!transform) return rest;
      const translateYMatch = transform.match(/translateY\(([^)]+)\)/);
      if (translateYMatch) {
        const yValue = parseFloat(translateYMatch[1]);
        return { ...rest, y: yValue };
      }
      return rest;
    });
  }, [animationTo, defaultTo]);

  const elements = animateBy === 'words' ? text.split(' ') : text.split('');

  // Build animation keyframes
  const animateKeyframes = useMemo(
    () => buildKeyframes(fromSnapshot, toSnapshots),
    [fromSnapshot, toSnapshots]
  );

  // Calculate transition timing
  const stepCount = toSnapshots.length + 1;
  const totalDuration = stepDuration * (stepCount - 1);
  const times = Array.from(
    { length: stepCount },
    (_, i) => (stepCount === 1 ? 0 : i / (stepCount - 1))
  );

  // Reset on text change
  useEffect(() => {
    completedCountRef.current = 0;
    hasCalledCompleteRef.current = false;
    if (!triggerOnView) {
      setInView(play ?? false);
    }
  }, [text, triggerOnView, play]);

  // 每个元素动画完成时计数;全部完成后触发一次回调。
  // 必须挂到所有元素上(而非仅末元素),否则计数器永远到不了 elements.length。
  const handleElementComplete = () => {
    completedCountRef.current += 1;
    if (completedCountRef.current >= elements.length && !hasCalledCompleteRef.current) {
      hasCalledCompleteRef.current = true;
      onAnimationComplete?.();
    }
  };

  return (
    <p
      ref={ref}
      className={`${styles.container} ${className}`}
      style={{ '--step-duration': `${stepDuration}s` } as React.CSSProperties}
    >
      {elements.map((segment, index) => {
        const spanTransition: Transition = {
          duration: totalDuration,
          times,
          delay: (index * delay) / 1000,
          ease: (t: number) => t, // Linear easing, can be customized
        };

        return (
          <motion.span
            key={index}
            className={styles.segment}
            initial={fromSnapshot}
            animate={inView ? animateKeyframes : fromSnapshot}
            transition={spanTransition}
            onAnimationComplete={handleElementComplete}
            style={{
              display: 'inline-block',
              willChange: 'transform, filter, opacity',
            }}
          >
            {segment === ' ' ? '\u00A0' : segment}
            {animateBy === 'words' && index < elements.length - 1 && '\u00A0'}
          </motion.span>
        );
      })}
    </p>
  );
};
