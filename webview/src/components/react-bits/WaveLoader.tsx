import { useMemo } from 'react';

interface WaveLoaderProps {
  /** Number of bars to display (default: 5) */
  count?: number;
  /** Width of each bar in px (default: 3) */
  barWidth?: number;
  /** Height of the loader in px (default: 20) */
  height?: number;
  /** Color of the bars (default: currentColor) */
  color?: string;
  /** Animation duration in seconds (default: 1) */
  duration?: number;
  /** Gap between bars in px (default: 2) */
  gap?: number;
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

/**
 * WaveLoader - A set of bars animating in a wave pattern.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <WaveLoader />
 * <WaveLoader count={7} height={30} color="#4ea1ff" />
 * <WaveLoader active={isStreaming} duration={0.8} />
 */
export const WaveLoader = ({
  count = 5,
  barWidth = 3,
  height = 20,
  color = 'currentColor',
  duration = 1,
  gap = 2,
  className = '',
  active = true,
}: WaveLoaderProps) => {
  const bars = useMemo(() => Array.from({ length: count }, (_, i) => i), [count]);

  if (!active) return null;

  return (
    <div
      className={`wave-loader ${className}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: `${gap}px`,
        height: `${height}px`,
      }}
      role="status"
      aria-label="Loading"
    >
      {bars.map((i) => {
        const delay = i * (duration / count);
        return (
          <span
            key={i}
            style={{
              width: `${barWidth}px`,
              height: `${height}px`,
              borderRadius: `${barWidth / 2}px`,
              backgroundColor: color,
              animation: `wave-bar ${duration}s ease-in-out ${delay}s infinite`,
              transformOrigin: 'center',
            }}
          />
        );
      })}
      <style>{`
        @keyframes wave-bar {
          0%, 100% {
            transform: scaleY(0.3);
            opacity: 0.5;
          }
          50% {
            transform: scaleY(1);
            opacity: 1;
          }
        }
      `}</style>
    </div>
  );
};

export default WaveLoader;
