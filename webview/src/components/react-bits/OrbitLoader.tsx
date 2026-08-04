export interface OrbitLoaderProps {
  /** Outer diameter of the loader in px (default: 40) */
  size?: number;
  /** Color of the orbits (default: currentColor) */
  color?: string;
  /** Animation duration in seconds (default: 1.5) */
  duration?: number;
  /** Number of orbit rings (default: 2) */
  rings?: number;
  /** Additional CSS class */
  className?: string;
  /** Whether the loader is active (default: true) */
  active?: boolean;
}

/**
 * OrbitLoader - Concentric rings rotating in opposite directions.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <OrbitLoader />
 * <OrbitLoader size={48} color="#4ea1ff" />
 * <OrbitLoader active={isProcessing} duration={2} />
 */
export const OrbitLoader = ({
  size = 40,
  color = 'currentColor',
  duration = 1.5,
  rings = 2,
  className = '',
  active = true,
}: OrbitLoaderProps) => {
  if (!active) return null;

  const ringConfigs = Array.from({ length: rings }, (_, i) => ({
    scale: 1 - i * 0.3,
    duration: duration * (1 - i * 0.2),
    direction: i % 2 === 0 ? 'normal' : 'reverse',
    borderWidth: Math.max(1.5, 2.5 - i * 0.5),
  }));

  return (
    <div
      className={`orbit-loader ${className}`}
      style={{
        position: 'relative',
        width: `${size}px`,
        height: `${size}px`,
      }}
      role="status"
      aria-label="Loading"
    >
      {ringConfigs.map((config, i) => (
        <span
          key={i}
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: '50%',
            border: `${config.borderWidth}px solid transparent`,
            borderTopColor: color,
            borderRightColor: i === 0 ? color : 'transparent',
            transform: `scale(${config.scale})`,
            animation: `orbit-spin ${config.duration}s linear infinite ${config.direction}`,
            willChange: 'transform',
          }}
        />
      ))}
      <style>{`
        @keyframes orbit-spin {
          0% { transform: rotate(0deg) scale(var(--orbit-scale, 1)); }
          100% { transform: rotate(360deg) scale(var(--orbit-scale, 1)); }
        }
      `}</style>
    </div>
  );
};

export default OrbitLoader;
