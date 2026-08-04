import { useMemo } from 'react';

export interface SkeletonProps {
  /** Width of the skeleton (default: '100%') */
  width?: string | number;
  /** Height of the skeleton (default: '16px') */
  height?: string | number;
  /** Border radius (default: '4px') */
  borderRadius?: string | number;
  /** Animation style: 'pulse' | 'shimmer' | 'wave' (default: 'shimmer') */
  variant?: 'pulse' | 'shimmer' | 'wave';
  /** Animation duration in seconds (default: 1.5) */
  duration?: number;
  /** Number of skeleton lines (default: 1) */
  lines?: number;
  /** Gap between lines when lines > 1 (default: '8px') */
  gap?: string | number;
  /** Additional CSS class */
  className?: string;
  /** Whether the skeleton is active (default: true) */
  active?: boolean;
}

/**
 * Skeleton - A loading placeholder with shimmer/pulse animation.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <Skeleton width="200px" height="20px" />
 * <Skeleton lines={3} variant="shimmer" />
 * <Skeleton width="100%" variant="wave" duration={2} />
 */
export const Skeleton = ({
  width = '100%',
  height = '16px',
  borderRadius = '4px',
  variant = 'shimmer',
  duration = 1.5,
  lines = 1,
  gap = '8px',
  className = '',
  active = true,
}: SkeletonProps) => {
  const skeletons = useMemo(() => Array.from({ length: lines }, (_, i) => i), [lines]);

  if (!active) return null;

  const getAnimationStyle = (): React.CSSProperties => {
    switch (variant) {
      case 'pulse':
        return {
          animation: `skeleton-pulse ${duration}s ease-in-out infinite`,
        };
      case 'wave':
        return {
          background: `linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.1) 50%, transparent 100%)`,
          backgroundSize: '200% 100%',
          animation: `skeleton-wave ${duration}s linear infinite`,
        };
      case 'shimmer':
      default:
        return {
          background: `linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.08) 50%, transparent 100%)`,
          backgroundSize: '200% 100%',
          animation: `skeleton-shimmer ${duration}s linear infinite`,
        };
    }
  };

  return (
    <div
      className={`skeleton ${className}`}
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap,
      }}
      aria-busy="true"
      aria-label="Loading"
    >
      {skeletons.map((i) => (
        <span
          key={i}
          style={{
            width: i === skeletons.length - 1 && lines > 1 ? '60%' : width,
            height,
            borderRadius,
            backgroundColor: 'rgba(255,255,255,0.06)',
            ...getAnimationStyle(),
          }}
        />
      ))}
      <style>{`
        @keyframes skeleton-pulse {
          0%, 100% { opacity: 0.6; }
          50% { opacity: 0.3; }
        }
        @keyframes skeleton-shimmer {
          0% { background-position: -200% 0; }
          100% { background-position: 200% 0; }
        }
        @keyframes skeleton-wave {
          0% { background-position: 200% 0; }
          100% { background-position: -200% 0; }
        }
      `}</style>
    </div>
  );
};

export default Skeleton;
