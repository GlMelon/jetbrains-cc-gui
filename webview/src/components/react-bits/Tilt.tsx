import { type ReactNode, useState, useRef, useCallback } from 'react';

export interface TiltProps {
  children: ReactNode;
  /** Maximum tilt angle in degrees (default: 5) */
  maxTilt?: number;
  /** Perspective in px (default: 1000) */
  perspective?: number;
  /** Scale on hover (default: 1.02) */
  scale?: number;
  /** Transition duration in ms (default: 200) */
  duration?: number;
  /** Whether the effect is enabled (default: true) */
  enabled?: boolean;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * Tilt - A wrapper that adds a 3D tilt effect on hover.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <Tilt>
 *   <div className="card">...</div>
 * </Tilt>
 *
 * @example
 * <Tilt maxTilt={10} scale={1.05}>
 *   <ServerCard {...props} />
 * </Tilt>
 */
export const Tilt = ({
  children,
  maxTilt = 5,
  perspective = 1000,
  scale = 1.02,
  duration = 200,
  enabled = true,
  className = '',
  style,
}: TiltProps) => {
  const [transform, setTransform] = useState('');
  const containerRef = useRef<HTMLDivElement>(null);

  const handleMouseMove = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (!enabled || !containerRef.current) return;

      const rect = containerRef.current.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const centerX = rect.width / 2;
      const centerY = rect.height / 2;

      const rotateX = ((y - centerY) / centerY) * -maxTilt;
      const rotateY = ((x - centerX) / centerX) * maxTilt;

      setTransform(
        `perspective(${perspective}px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale3d(${scale}, ${scale}, ${scale})`
      );
    },
    [enabled, maxTilt, perspective, scale]
  );

  const handleMouseLeave = useCallback(() => {
    setTransform('');
  }, []);

  if (!enabled) {
    return <div className={className} style={style}>{children}</div>;
  }

  return (
    <div
      ref={containerRef}
      className={`tilt ${className}`}
      style={{
        ...style,
        transform,
        transition: `transform ${duration}ms ease`,
        willChange: 'transform',
        transformStyle: 'preserve-3d',
      }}
      onMouseMove={handleMouseMove}
      onMouseLeave={handleMouseLeave}
    >
      {children}
    </div>
  );
};

export default Tilt;
