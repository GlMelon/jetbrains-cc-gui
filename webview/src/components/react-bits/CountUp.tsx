import { useState, useEffect, useRef, useCallback } from 'react';

export interface CountUpProps {
  /** Target number to count to */
  end: number;
  /** Start number (default: 0) */
  start?: number;
  /** Duration in ms (default: 2000) */
  duration?: number;
  /** Number of decimal places (default: 0) */
  decimals?: number;
  /** Prefix string (default: '') */
  prefix?: string;
  /** Suffix string (default: '') */
  suffix?: string;
  /** Separator for thousands (default: ',') */
  separator?: string;
  /** Easing function (default: easeOutCubic) */
  easing?: (t: number) => number;
  /** Whether to start animation immediately (default: true) */
  autoStart?: boolean;
  /** Whether to enable viewport-triggered animation (default: false) */
  triggerOnView?: boolean;
  /** Callback when animation completes */
  onComplete?: () => void;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

const easeOutCubic = (t: number): number => 1 - Math.pow(1 - t, 3);

/**
 * CountUp - An animated number counter.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <CountUp end={1000} />
 *
 * @example
 * <CountUp
 *   end={12345.67}
 *   decimals={2}
 *   prefix="$"
 *   duration={3000}
 * />
 */
export const CountUp = ({
  end,
  start = 0,
  duration = 2000,
  decimals = 0,
  prefix = '',
  suffix = '',
  separator = ',',
  easing = easeOutCubic,
  autoStart = true,
  triggerOnView = false,
  onComplete,
  className = '',
  style,
}: CountUpProps) => {
  const [displayValue, setDisplayValue] = useState(start);
  const [hasStarted, setHasStarted] = useState(false);
  const animationRef = useRef<number | null>(null);
  const startTimeRef = useRef<number | null>(null);
  const containerRef = useRef<HTMLSpanElement>(null);

  const formatNumber = useCallback(
    (value: number): string => {
      const fixed = value.toFixed(decimals);
      const [intPart, decPart] = fixed.split('.');
      const withSeparator = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, separator);
      return decPart ? `${withSeparator}.${decPart}` : withSeparator;
    },
    [decimals, separator]
  );

  const animate = useCallback(
    (timestamp: number) => {
      if (!startTimeRef.current) {
        startTimeRef.current = timestamp;
      }

      const elapsed = timestamp - startTimeRef.current;
      const progress = Math.min(elapsed / duration, 1);
      const easedProgress = easing(progress);
      const currentValue = start + (end - start) * easedProgress;

      setDisplayValue(currentValue);

      if (progress < 1) {
        animationRef.current = requestAnimationFrame(animate);
      } else {
        onComplete?.();
      }
    },
    [start, end, duration, easing, onComplete]
  );

  const startAnimation = useCallback(() => {
    if (hasStarted) return;
    setHasStarted(true);
    startTimeRef.current = null;
    animationRef.current = requestAnimationFrame(animate);
  }, [hasStarted, animate]);

  useEffect(() => {
    if (autoStart && !triggerOnView) {
      startAnimation();
    }
    return () => {
      if (animationRef.current) {
        cancelAnimationFrame(animationRef.current);
      }
    };
  }, [autoStart, triggerOnView, startAnimation]);

  useEffect(() => {
    if (!triggerOnView || !containerRef.current) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          startAnimation();
          observer.disconnect();
        }
      },
      { threshold: 0.1 }
    );

    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [triggerOnView, startAnimation]);

  return (
    <span ref={containerRef} className={`count-up ${className}`} style={style}>
      {prefix}
      {formatNumber(displayValue)}
      {suffix}
    </span>
  );
};

export default CountUp;
