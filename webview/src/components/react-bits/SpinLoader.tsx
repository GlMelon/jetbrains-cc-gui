import { useMemo } from 'react';

export interface SpinLoaderProps {
  /** Diameter of the spinner in px (default: 24) */
  size?: number;
  /** Stroke width in px (default: 2) */
  strokeWidth?: number;
  /** Color of the spinner (default: currentColor) */
  color?: string;
  /** Animation duration in seconds (default: 1) */
  duration?: number;
  /** Spinner style: 'ring' | 'dots' | 'bars' (default: 'ring') */
  variant?: 'ring' | 'dots' | 'bars';
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

const DOT_COUNT = 8;
const BAR_COUNT = 3;

const makeArray = (n: number) => Array.from({ length: n }, (_, i) => i);

/**
 * SpinLoader - A spinning loader with multiple variants.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <SpinLoader />
 * <SpinLoader variant="dots" size={32} />
 * <SpinLoader variant="bars" color="#4ea1ff" />
 */
export const SpinLoader = ({
  size = 24,
  strokeWidth = 2,
  color = 'currentColor',
  duration = 1,
  variant = 'ring',
  className = '',
  active = true,
}: SpinLoaderProps) => {
  const dots = useMemo(() => makeArray(DOT_COUNT), []);
  const bars = useMemo(() => makeArray(BAR_COUNT), []);

  if (!active) return null;

  if (variant === 'dots') {
    return (
      <div
        className={`spin-loader spin-loader-dots ${className}`}
        style={{
          position: 'relative',
          width: `${size}px`,
          height: `${size}px`,
        }}
        role="status"
        aria-label="Loading"
      >
        {dots.map((i) => {
          const angle = (i * 360) / DOT_COUNT;
          const delay = i * (duration / DOT_COUNT);
          return (
            <span
              key={i}
              style={{
                position: 'absolute',
                left: '50%',
                top: '50%',
                width: `${size * 0.15}px`,
                height: `${size * 0.15}px`,
                borderRadius: '50%',
                backgroundColor: color,
                transform: `rotate(${angle}deg) translateY(-${size * 0.35}px)`,
                transformOrigin: `0 ${size * 0.35}px`,
                animation: `spin-dot ${duration}s linear ${delay}s infinite`,
                opacity: 0.3 + (i / DOT_COUNT) * 0.7,
              }}
            />
          );
        })}
        <style>{`
          @keyframes spin-dot {
            0% { opacity: 0.3; }
            50% { opacity: 1; }
            100% { opacity: 0.3; }
          }
        `}</style>
      </div>
    );
  }

  if (variant === 'bars') {
    return (
      <div
        className={`spin-loader spin-loader-bars ${className}`}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: `${size * 0.1}px`,
          height: `${size}px`,
        }}
        role="status"
        aria-label="Loading"
      >
        {bars.map((i) => (
          <span
            key={i}
            style={{
              width: `${size * 0.2}px`,
              height: `${size}px`,
              borderRadius: `${size * 0.1}px`,
              backgroundColor: color,
              animation: `spin-bar ${duration}s ease-in-out ${i * 0.15}s infinite`,
            }}
          />
        ))}
        <style>{`
          @keyframes spin-bar {
            0%, 100% {
              transform: scaleY(0.4);
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
  }

  // Default: ring variant
  return (
    <div
      className={`spin-loader spin-loader-ring ${className}`}
      style={{
        width: `${size}px`,
        height: `${size}px`,
        borderRadius: '50%',
        border: `${strokeWidth}px solid transparent`,
        borderTopColor: color,
        borderRightColor: color,
        animation: `spin-ring ${duration}s linear infinite`,
        willChange: 'transform',
      }}
      role="status"
      aria-label="Loading"
    >
      <style>{`
        @keyframes spin-ring {
          0% { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
      `}</style>
    </div>
  );
};

export default SpinLoader;
