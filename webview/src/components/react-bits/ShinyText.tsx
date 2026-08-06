import { type ReactNode } from 'react';

export interface ShinyTextProps {
  children: ReactNode;
  /** Base text color (default: currentColor) */
  color?: string;
  /** Shiny highlight color (default: linear-gradient with white) */
  shineColor?: string;
  /** Animation duration in seconds (default: 2) */
  duration?: number;
  /** Shine angle in degrees (default: 90) */
  angle?: number;
  /** Whether to enable the shine effect (default: true) */
  enabled?: boolean;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * ShinyText - A text component with a metallic shine sweep animation.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <ShinyText>Premium</ShinyText>
 *
 * @example
 * <ShinyText
 *   color="#4ea1ff"
 *   shineColor="linear-gradient(90deg, transparent, rgba(255,255,255,0.8), transparent)"
 *   duration={3}
 * >
 *   Pro Version
 * </ShinyText>
 */
export const ShinyText = ({
  children,
  color = 'currentColor',
  shineColor,
  duration = 2,
  angle = 90,
  enabled = true,
  className = '',
  style,
}: ShinyTextProps) => {
  if (!enabled) {
    return (
      <span className={className} style={{ color, ...style }}>
        {children}
      </span>
    );
  }

  const defaultShine = `linear-gradient(${angle}deg, transparent 0%, transparent 40%, rgba(255,255,255,0.8) 50%, transparent 60%, transparent 100%)`;

  return (
    <span
      className={`shiny-text ${className}`}
      style={{
        color,
        backgroundImage: shineColor || defaultShine,
        backgroundSize: '200% 100%',
        backgroundClip: 'text',
        WebkitBackgroundClip: 'text',
        WebkitTextFillColor: 'transparent',
        animation: `shiny-sweep ${duration}s ease-in-out infinite`,
        display: 'inline-block',
        ...style,
      }}
    >
      {children}
      <style>{`
        @keyframes shiny-sweep {
          0% {
            background-position: 200% 0;
          }
          100% {
            background-position: -200% 0;
          }
        }
      `}</style>
    </span>
  );
};

export default ShinyText;
