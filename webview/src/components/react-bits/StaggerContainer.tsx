import { type ReactNode, useMemo } from 'react';

export interface StaggerContainerProps {
  children: ReactNode;
  /** Delay between each child in ms (default: 50) */
  stagger?: number;
  /** Initial delay before first child in ms (default: 0) */
  initialDelay?: number;
  /** Animation duration per child in ms (default: 300) */
  duration?: number;
  /** Animation type: 'fadeInUp' | 'fadeIn' | 'scaleIn' | 'slideRight' (default: 'fadeInUp') */
  variant?: 'fadeInUp' | 'fadeIn' | 'scaleIn' | 'slideRight';
  /** Distance for translate animations in px (default: 20) */
  distance?: number;
  /** Whether the animation is enabled (default: true) */
  enabled?: boolean;
  /** Additional CSS class */
  className?: string;
  /** Additional inline styles */
  style?: React.CSSProperties;
}

/**
 * StaggerContainer - Wraps children with staggered entrance animations.
 * Each direct child receives a sequential animation delay.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <StaggerContainer stagger={80}>
 *   <Card>Item 1</Card>
 *   <Card>Item 2</Card>
 *   <Card>Item 3</Card>
 * </StaggerContainer>
 *
 * @example
 * <StaggerContainer variant="scaleIn" stagger={100} duration={400}>
 *   {items.map(item => <Card key={item.id} {...item} />)}
 * </StaggerContainer>
 */
export const StaggerContainer = ({
  children,
  stagger = 50,
  initialDelay = 0,
  duration = 300,
  variant = 'fadeInUp',
  distance = 20,
  enabled = true,
  className = '',
  style,
}: StaggerContainerProps) => {
  const animationName = useMemo(() => {
    switch (variant) {
      case 'fadeIn':
        return 'stagger-fade-in';
      case 'scaleIn':
        return 'stagger-scale-in';
      case 'slideRight':
        return 'stagger-slide-right';
      case 'fadeInUp':
      default:
        return 'stagger-fade-in-up';
    }
  }, [variant]);

  if (!enabled) {
    return <div className={className} style={style}>{children}</div>;
  }

  return (
    <div className={`stagger-container ${className}`} style={style}>
      {Array.isArray(children)
        ? children.map((child, index) => {
            if (!child) return null;
            const delay = initialDelay + index * stagger;
            return (
              <div
                key={index}
                style={{
                  animation: `${animationName} ${duration}ms ease-out ${delay}ms both`,
                  willChange: 'transform, opacity',
                }}
              >
                {child}
              </div>
            );
          })
        : children}
      <style>{`
        @keyframes stagger-fade-in-up {
          from {
            opacity: 0;
            transform: translateY(${distance}px);
          }
          to {
            opacity: 1;
            transform: translateY(0);
          }
        }
        @keyframes stagger-fade-in {
          from { opacity: 0; }
          to { opacity: 1; }
        }
        @keyframes stagger-scale-in {
          from {
            opacity: 0;
            transform: scale(0.9);
          }
          to {
            opacity: 1;
            transform: scale(1);
          }
        }
        @keyframes stagger-slide-right {
          from {
            opacity: 0;
            transform: translateX(-${distance}px);
          }
          to {
            opacity: 1;
            transform: translateX(0);
          }
        }
      `}</style>
    </div>
  );
};

export default StaggerContainer;
