import { useMemo } from 'react';

interface BounceLoaderProps {
  /** Number of dots to display (default: 3) */
  count?: number;
  /** Size of each dot in px (default: 10) */
  size?: number;
  /** Color of the dots (default: currentColor) */
  color?: string;
  /** Animation duration in seconds (default: 0.6) */
  duration?: number;
  /** Gap between dots in px (default: 6) */
  gap?: number;
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

/**
 * BounceLoader - A set of bouncing dots.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <BounceLoader />
 * <BounceLoader count={5} size={14} color="#4ea1ff" />
 * <BounceLoader active={isLoading} duration={0.4} />
 */
export const BounceLoader = ({
  count = 3,
  size = 10,
  color = 'currentColor',
  duration = 0.6,
  gap = 6,
  className = '',
  active = true,
}: BounceLoaderProps) => {
  const dots = useMemo(() => Array.from({ length: count }, (_, i) => i), [count]);

  if (!active) return null;

  return (
    <div
      className={`bounce-loader ${className}`}
      style={{
        display: 'inline-flex',
        alignItems: 'flex-end',
        gap: `${gap}px`,
        height: `${size * 2}px`,
      }}
      role="status"
      aria-label="Loading"
    >
      {dots.map((i) => {
        const delay = i * (duration / count);
        return (
          <span
            key={i}
            style={{
              width: `${size}px`,
              height: `${size}px`,
              borderRadius: '50%',
              backgroundColor: color,
              animation: `bounce-dot ${duration}s ease-in-out ${delay}s infinite`,
            }}
          />
        );
      })}
      <style>{`
        @keyframes bounce-dot {
          0%, 100% {
            transform: translateY(0);
          }
          50% {
            transform: translateY(-${size}px);
          }
        }
      `}</style>
    </div>
  );
};

export default BounceLoader;
