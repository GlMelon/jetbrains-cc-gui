import { type ReactNode, useCallback, useRef, useState } from 'react';

interface Spark {
  id: number;
  x: number;
  y: number;
  angle: number;
  velocity: number;
  size: number;
  color: string;
  opacity: number;
}

export interface ClickSparkProps {
  children: ReactNode;
  /** Number of particles per click (default: 8) */
  particleCount?: number;
  /** Particle colors (default: ['#4ea1ff', '#ff6b6b', '#ffd93d', '#6bcb77']) */
  colors?: string[];
  /** Particle size range in px [min, max] (default: [2, 6]) */
  sizeRange?: [number, number];
  /** Particle velocity range [min, max] (default: [50, 150]) */
  velocityRange?: [number, number];
  /** Animation duration in ms (default: 600) */
  duration?: number;
  /** Whether the effect is enabled (default: true) */
  enabled?: boolean;
  /** Click handler */
  onClick?: (e: React.MouseEvent) => void;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * ClickSpark - A wrapper that creates particle burst effects on click.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <ClickSpark>
 *   <button>Click me</button>
 * </ClickSpark>
 *
 * @example
 * <ClickSpark particleCount={12} colors={['#4ea1ff']}>
 *   <div className="action-card">...</div>
 * </ClickSpark>
 */
export const ClickSpark = ({
  children,
  particleCount = 8,
  colors = ['#4ea1ff', '#ff6b6b', '#ffd93d', '#6bcb77'],
  sizeRange = [2, 6],
  velocityRange = [50, 150],
  duration = 600,
  enabled = true,
  onClick,
  className = '',
  style,
}: ClickSparkProps) => {
  const [sparks, setSparks] = useState<Spark[]>([]);
  const sparkIdRef = useRef(0);
  const containerRef = useRef<HTMLDivElement>(null);

  const createSparks = useCallback(
    (e: React.MouseEvent) => {
      if (!enabled || !containerRef.current) return;

      const rect = containerRef.current.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      const newSparks: Spark[] = Array.from({ length: particleCount }, () => ({
        id: sparkIdRef.current++,
        x,
        y,
        angle: Math.random() * Math.PI * 2,
        velocity: velocityRange[0] + Math.random() * (velocityRange[1] - velocityRange[0]),
        size: sizeRange[0] + Math.random() * (sizeRange[1] - sizeRange[0]),
        color: colors[Math.floor(Math.random() * colors.length)],
        opacity: 1,
      }));

      setSparks((prev) => [...prev, ...newSparks]);

      // Clean up after animation
      setTimeout(() => {
        setSparks((prev) => prev.filter((s) => !newSparks.some((ns) => ns.id === s.id)));
      }, duration);
    },
    [enabled, particleCount, colors, sizeRange, velocityRange, duration]
  );

  const handleClick = useCallback(
    (e: React.MouseEvent) => {
      createSparks(e);
      onClick?.(e);
    },
    [createSparks, onClick]
  );

  return (
    <div
      ref={containerRef}
      className={`click-spark ${className}`}
      style={{
        position: 'relative',
        overflow: 'hidden',
        cursor: 'pointer',
        ...style,
      }}
      onClick={handleClick}
    >
      {children}
      {sparks.map((spark) => {
        const dx = Math.cos(spark.angle) * spark.velocity;
        const dy = Math.sin(spark.angle) * spark.velocity;
        return (
          <span
            key={spark.id}
            style={{
              position: 'absolute',
              left: spark.x,
              top: spark.y,
              width: spark.size,
              height: spark.size,
              borderRadius: '50%',
              backgroundColor: spark.color,
              pointerEvents: 'none',
              animation: `spark-fly ${duration}ms ease-out forwards`,
              // @ts-expect-error CSS custom properties for animation
              '--dx': `${dx}px`,
              '--dy': `${dy}px`,
            }}
          />
        );
      })}
      <style>{`
        @keyframes spark-fly {
          0% {
            transform: translate(0, 0) scale(1);
            opacity: 1;
          }
          100% {
            transform: translate(var(--dx), var(--dy)) scale(0);
            opacity: 0;
          }
        }
      `}</style>
    </div>
  );
};

export default ClickSpark;
