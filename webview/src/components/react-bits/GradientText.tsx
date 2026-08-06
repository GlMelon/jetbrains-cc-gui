import { type ReactNode } from 'react';

export interface GradientTextProps {
  children: ReactNode;
  /** Gradient colors (default: ['#4ea1ff', '#7c3aed', '#ec4899']) */
  colors?: string[];
  /** Gradient direction in degrees (default: 90) */
  angle?: number;
  /** Whether to animate the gradient (default: false) */
  animated?: boolean;
  /** Animation duration in seconds (default: 3) */
  animationDuration?: number;
  /** Text color fallback for non-gradient browsers (default: transparent) */
  fallbackColor?: string;
  /** Whether the effect is enabled (default: true) */
  enabled?: boolean;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * GradientText - A text component with gradient color effect.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <GradientText>Hello World</GradientText>
 *
 * @example
 * <GradientText
 *   colors={['#ff6b6b', '#ffd93d', '#6bcb77']}
 *   angle={45}
 *   animated
 * >
 *   Animated Gradient
 * </GradientText>
 */
export const GradientText = ({
  children,
  colors = ['#4ea1ff', '#7c3aed', '#ec4899'],
  angle = 90,
  animated = false,
  animationDuration = 3,
  fallbackColor = 'transparent',
  enabled = true,
  className = '',
  style,
}: GradientTextProps) => {
  if (!enabled) {
    return (
      <span className={className} style={{ color: fallbackColor, ...style }}>
        {children}
      </span>
    );
  }

  const gradientColors = colors.join(', ');
  const backgroundImage = `linear-gradient(${angle}deg, ${gradientColors})`;

  const animationStyles: React.CSSProperties = animated
    ? {
        backgroundImage: `linear-gradient(${angle}deg, ${gradientColors})`,
        backgroundSize: animated ? '200% 200%' : '100% 100%',
        backgroundClip: 'text',
        WebkitBackgroundClip: 'text',
        WebkitTextFillColor: 'transparent',
        animation: animated
          ? `gradient-shift ${animationDuration}s ease infinite`
          : undefined,
      }
    : {
        backgroundImage,
        backgroundClip: 'text',
        WebkitBackgroundClip: 'text',
        WebkitTextFillColor: 'transparent',
      };

  return (
    <span
      className={`gradient-text ${className}`}
      style={{
        display: 'inline-block',
        ...style,
        ...animationStyles,
      }}
    >
      {children}
      {animated && (
        <style>{`
          @keyframes gradient-shift {
            0%, 100% {
              background-position: 0% 50%;
            }
            50% {
              background-position: 100% 50%;
            }
          }
        `}</style>
      )}
    </span>
  );
};

export default GradientText;
