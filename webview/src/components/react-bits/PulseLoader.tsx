import { useMemo } from 'react';

export interface PulseLoaderProps {
  /** Number of dots to display (default: 3) */
  count?: number;
  /** Size of each dot in px (default: 8) */
  size?: number;
  /** Color of the dots (default: currentColor) */
  color?: string;
  /** Animation duration in seconds (default: 1.2) */
  duration?: number;
  /** Gap between dots in px (default: 4) */
  gap?: number;
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

/**
 * PulseLoader - A set of pulsing dots indicating loading state.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <PulseLoader />
 * <PulseLoader count={5} size={12} color="#4ea1ff" />
 * <PulseLoader active={isLoading} duration={0.8} />
 */
export const PulseLoader = ({
  count = 3,
  size = 8,
  color = 'currentColor',
  duration = 1.2,
  gap = 4,
  className = '',
  active = true,
}: PulseLoaderProps) => {
  const dots = useMemo(() => Array.from({ length: count }, (_, i) => i), [count]);

  if (!active) return null;

  return (
    <div
      className={`pulse-loader ${className}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: `${gap}px`,
      }}
      role="status"
      aria-label="Loading"
    >
      {dots.map((i) => (
        <span
          key={i}
          style={{
            width: `${size}px`,
            height: `${size}px`,
            borderRadius: '50%',
            backgroundColor: color,
            animation: `pulse-dot ${duration}s ease-in-out ${i * (duration / (count * 2))}s infinite`,
            willChange: 'transform, opacity',
          }}
        />
      ))}
      <style>{`
        @keyframes pulse-dot {
          0%, 100% {
            transform: scale(1);
            opacity: 1;
          }
          50% {
            transform: scale(0.6);
            opacity: 0.4;
          }
        }
      `}</style>
    </div>
  );
};

export default PulseLoader;
