import { useMemo } from 'react';

export interface RippleLoaderProps {
  /** Size of the center dot in px (default: 8) */
  size?: number;
  /** Number of ripple rings (default: 3) */
  ripples?: number;
  /** Color of the loader (default: currentColor) */
  color?: string;
  /** Animation duration in seconds (default: 2) */
  duration?: number;
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

/**
 * RippleLoader - A center dot with expanding ripple rings.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <RippleLoader />
 * <RippleLoader size={12} ripples={4} color="#4ea1ff" />
 * <RippleLoader active={isGenerating} duration={1.5} />
 */
export const RippleLoader = ({
  size = 8,
  ripples = 3,
  color = 'currentColor',
  duration = 2,
  className = '',
  active = true,
}: RippleLoaderProps) => {
  const rings = useMemo(() => Array.from({ length: ripples }, (_, i) => i), [ripples]);

  if (!active) return null;

  return (
    <div
      className={`ripple-loader ${className}`}
      style={{
        position: 'relative',
        width: `${size * 4}px`,
        height: `${size * 4}px`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
      role="status"
      aria-label="Loading"
    >
      {/* Center dot */}
      <span
        style={{
          position: 'absolute',
          width: `${size}px`,
          height: `${size}px`,
          borderRadius: '50%',
          backgroundColor: color,
          animation: `ripple-center ${duration}s ease-in-out infinite`,
          willChange: 'transform, opacity',
          zIndex: ripples + 1,
        }}
      />
      {/* Ripple rings */}
      {rings.map((i) => (
        <span
          key={i}
          style={{
            position: 'absolute',
            width: `${size}px`,
            height: `${size}px`,
            borderRadius: '50%',
            border: `1.5px solid ${color}`,
            animation: `ripple-ring ${duration}s ease-out ${i * (duration / ripples)}s infinite`,
            willChange: 'transform, opacity',
            zIndex: ripples - i,
          }}
        />
      ))}
      <style>{`
        @keyframes ripple-center {
          0%, 100% {
            transform: scale(1);
            opacity: 1;
          }
          50% {
            transform: scale(0.75);
            opacity: 0.5;
          }
        }
        @keyframes ripple-ring {
          0% {
            transform: scale(1);
            opacity: 0.5;
          }
          100% {
            transform: scale(2.8);
            opacity: 0;
          }
        }
      `}</style>
    </div>
  );
};

export default RippleLoader;
