import { type ReactNode, useState } from 'react';

export interface HoverLiftProps {
  children: ReactNode;
  /** Lift distance in px (default: 4) */
  lift?: number;
  /** Shadow intensity multiplier (default: 1) */
  shadowIntensity?: number;
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
 * HoverLift - A wrapper that adds a subtle lift effect on hover.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <HoverLift>
 *   <div className="card">...</div>
 * </HoverLift>
 *
 * @example
 * <HoverLift lift={8} shadowIntensity={1.5}>
 *   <ServerCard {...props} />
 * </HoverLift>
 */
export const HoverLift = ({
  children,
  lift = 4,
  shadowIntensity = 1,
  duration = 200,
  enabled = true,
  className = '',
  style,
}: HoverLiftProps) => {
  const [isHovered, setIsHovered] = useState(false);

  if (!enabled) {
    return <div className={className} style={style}>{children}</div>;
  }

  return (
    <div
      className={`hover-lift ${className}`}
      style={{
        ...style,
        transform: isHovered ? `translateY(-${lift}px)` : 'translateY(0)',
        transition: `transform ${duration}ms ease, box-shadow ${duration}ms ease`,
        willChange: 'transform',
      }}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      {children}
      <style>{`
        .hover-lift {
          position: relative;
        }
        .hover-lift:hover {
          box-shadow: 0 ${4 * shadowIntensity}px ${8 * shadowIntensity}px rgba(0, 0, 0, 0.1),
                      0 ${2 * shadowIntensity}px ${4 * shadowIntensity}px rgba(0, 0, 0, 0.06);
        }
      `}</style>
    </div>
  );
};

export default HoverLift;
