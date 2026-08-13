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
 * Robustness notes:
 * - Re-animates whenever `end` changes (no latching guard), so late-arriving
 *   values (e.g. per-turn usage stamped after streaming ends) animate correctly.
 * - A setTimeout safety net guarantees the final value is reached even if
 *   requestAnimationFrame is starved (e.g. JCEF webview backgrounded at mount),
 *   so the count never freezes at its initial value.
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
  // triggerOnView=false means the element is considered in view from the start.
  const [inView, setInView] = useState(!triggerOnView);
  const animationRef = useRef<number | null>(null);
  const startTimeRef = useRef<number | null>(null);
  // Track the latest displayed value so the next animation starts from there
  // (keeps a smooth transition when `end` changes mid-animation).
  const fromRef = useRef(start);
  const containerRef = useRef<HTMLSpanElement>(null);
  // Keep the latest onComplete in a ref so the animation effect doesn't have to
  // re-run (and restart) whenever the callback identity changes.
  const onCompleteRef = useRef(onComplete);
  onCompleteRef.current = onComplete;

  const formatNumber = useCallback(
    (value: number): string => {
      const fixed = value.toFixed(decimals);
      const [intPart, decPart] = fixed.split('.');
      const withSeparator = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, separator);
      return decPart ? `${withSeparator}.${decPart}` : withSeparator;
    },
    [decimals, separator]
  );

  // (Re)start the animation whenever the target changes. This replaces the old
  // `hasStarted` latch that silently ignored subsequent `end` changes.
  useEffect(() => {
    if (!autoStart || !inView) return;

    if (animationRef.current != null) {
      cancelAnimationFrame(animationRef.current);
      animationRef.current = null;
    }
    startTimeRef.current = null;

    const from = fromRef.current;
    const to = end;

    // Nothing to animate — snap to the target and bail out (avoids leaving the
    // display stuck at `start` when from === to).
    if (from === to) {
      setDisplayValue(to);
      onCompleteRef.current?.();
      return;
    }

    const animate = (timestamp: number) => {
      if (startTimeRef.current == null) {
        startTimeRef.current = timestamp;
      }
      const elapsed = timestamp - startTimeRef.current;
      const progress = Math.min(elapsed / duration, 1);
      const easedProgress = easing(progress);
      const currentValue = from + (to - from) * easedProgress;

      setDisplayValue(currentValue);

      if (progress < 1) {
        animationRef.current = requestAnimationFrame(animate);
      } else {
        // Snap to the exact target to avoid floating-point drift on the last frame.
        setDisplayValue(to);
        animationRef.current = null;
        onCompleteRef.current?.();
      }
    };
    animationRef.current = requestAnimationFrame(animate);

    // Safety net: if requestAnimationFrame is starved (the webview can be
    // backgrounded at mount in JCEF, or the tab is off-screen), force the final
    // value so the counter never freezes at its initial value. Only acts when
    // the animation has NOT already completed (animationRef cleared on success),
    // avoiding a duplicate onComplete/setDisplayValue after a normal finish.
    const fallbackTimer = window.setTimeout(() => {
      if (animationRef.current != null) {
        cancelAnimationFrame(animationRef.current);
        animationRef.current = null;
        setDisplayValue(to);
        onCompleteRef.current?.();
      }
    }, duration + 200);

    return () => {
      if (animationRef.current != null) {
        cancelAnimationFrame(animationRef.current);
        animationRef.current = null;
      }
      window.clearTimeout(fallbackTimer);
    };
  }, [end, duration, easing, autoStart, inView]);

  // Keep fromRef in sync with the latest rendered value so a subsequent
  // animation (after `end` changes) starts from where we left off.
  useEffect(() => {
    fromRef.current = displayValue;
  }, [displayValue]);

  useEffect(() => {
    if (!triggerOnView || !containerRef.current) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          observer.disconnect();
        }
      },
      { threshold: 0.1 }
    );

    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [triggerOnView]);

  return (
    <span ref={containerRef} className={`count-up ${className}`} style={style}>
      {prefix}
      {formatNumber(displayValue)}
      {suffix}
    </span>
  );
};

export default CountUp;
