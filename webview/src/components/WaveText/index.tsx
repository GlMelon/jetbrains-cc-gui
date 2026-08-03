import { useState, useEffect, useRef } from 'react';
import styles from './style.module.less';

interface WaveTextProps {
  text: string;
  /** Animation delay between each character in ms */
  delay?: number;
  /** Additional CSS class */
  className?: string;
  /** Wave amplitude in px (default: 8) */
  amplitude?: number;
  /** Wave animation duration in seconds (default: 1.5) */
  duration?: number;
  /** Enable/disable animation (default: true) */
  active?: boolean;
  /** Trigger animation on mount or manually (default: true) */
  triggerOnMount?: boolean;
  /** Callback when wave animation completes one cycle */
  onWaveComplete?: () => void;
}

/**
 * WaveText - Wave animation for text characters.
 * Inspired by react-bits, with enhanced configuration options.
 *
 * @example
 * // Basic usage
 * <WaveText text="Processing..." delay={80} />
 *
 * @example
 * // Custom amplitude and duration
 * <WaveText
 *   text="Queue waiting..."
 *   amplitude={12}
 *   duration={1.0}
 *   delay={60}
 * />
 *
 * @example
 * // Controlled animation
 * <WaveText text="Click me" active={isAnimating} triggerOnMount={false} />
 */
export const WaveText = ({
  text,
  delay = 100,
  className = '',
  amplitude = 8,
  duration = 1.5,
  active = true,
  triggerOnMount = true,
  onWaveComplete,
}: WaveTextProps) => {
  const [isVisible, setIsVisible] = useState(!triggerOnMount);
  const ref = useRef<HTMLParagraphElement>(null);
  const cycleCountRef = useRef(0);

  // Viewport intersection trigger
  useEffect(() => {
    if (!triggerOnMount || !ref.current) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setIsVisible(true);
          observer.unobserve(ref.current!);
        }
      },
      { threshold: 0.1 }
    );
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, [triggerOnMount]);

  // Track wave cycles
  useEffect(() => {
    if (!active || !isVisible) return;
    const interval = setInterval(() => {
      cycleCountRef.current += 1;
      onWaveComplete?.();
    }, duration * 1000);
    return () => clearInterval(interval);
  }, [active, isVisible, duration, onWaveComplete]);

  const chars = text.split('');
  const shouldAnimate = active && isVisible;

  return (
    <p
      ref={ref}
      className={`${styles.container} ${className}`}
      style={{
        '--wave-amplitude': `${amplitude}px`,
        '--wave-duration': `${duration}s`,
      } as React.CSSProperties}
    >
      {chars.map((char, index) => (
        <span
          key={index}
          className={`${styles.waveChar} ${shouldAnimate ? styles.waveAnimating : ''}`}
          style={{
            animationDelay: shouldAnimate ? `${index * delay}ms` : '0ms',
          }}
        >
          {char === ' ' ? '\u00A0' : char}
        </span>
      ))}
    </p>
  );
};
