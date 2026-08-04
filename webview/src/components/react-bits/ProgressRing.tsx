export interface ProgressRingProps {
  /** Diameter of the ring in px (default: 24) */
  size?: number;
  /** Stroke width in px (default: 2) */
  strokeWidth?: number;
  /** Progress value between 0 and 1 (null for indeterminate) (default: null) */
  progress?: number | null;
  /** Color of the ring (default: currentColor) */
  color?: string;
  /** Background color of the track (default: rgba(255,255,255,0.1)) */
  trackColor?: string;
  /** Animation duration in seconds for indeterminate state (default: 1) */
  duration?: number;
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

/**
 * ProgressRing - A circular progress indicator.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <ProgressRing />
 * <ProgressRing progress={0.7} size={32} />
 * <ProgressRing progress={1} color="#4caf50" />
 */
export const ProgressRing = ({
  size = 24,
  strokeWidth = 2,
  progress = null,
  color = 'currentColor',
  trackColor = 'rgba(255,255,255,0.1)',
  duration = 1,
  className = '',
  active = true,
}: ProgressRingProps) => {
  if (!active) return null;

  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = progress !== null ? circumference * (1 - progress) : circumference;

  return (
    <div
      className={`progress-ring ${className}`}
      style={{
        width: `${size}px`,
        height: `${size}px`,
        position: 'relative',
      }}
      role="progressbar"
      aria-valuenow={progress !== null ? Math.round(progress * 100) : undefined}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <svg
        width={size}
        height={size}
        style={{
          transform: 'rotate(-90deg)',
          animation: progress === null ? `progress-spin ${duration}s linear infinite` : 'none',
        }}
      >
        {/* Background track */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={trackColor}
          strokeWidth={strokeWidth}
        />
        {/* Progress arc */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth={strokeWidth}
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          strokeLinecap="round"
          style={{
            transition: progress !== null ? 'stroke-dashoffset 0.3s ease' : 'none',
          }}
        />
      </svg>
      <style>{`
        @keyframes progress-spin {
          0% { transform: rotate(-90deg); }
          100% { transform: rotate(270deg); }
        }
      `}</style>
    </div>
  );
};

export default ProgressRing;
