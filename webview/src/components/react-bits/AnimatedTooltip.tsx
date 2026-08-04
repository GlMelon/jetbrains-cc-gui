import { type ReactNode, useState, useRef, useEffect, useCallback } from 'react';

interface TooltipPosition {
  x: number;
  y: number;
}

export interface AnimatedTooltipProps {
  content: ReactNode;
  children: ReactNode;
  /** Position relative to target: 'top' | 'bottom' | 'left' | 'right' (default: 'top') */
  position?: 'top' | 'bottom' | 'left' | 'right';
  /** Offset from target in px (default: 8) */
  offset?: number;
  /** Delay before showing in ms (default: 200) */
  showDelay?: number;
  /** Delay before hiding in ms (default: 100) */
  hideDelay?: number;
  /** Max width of tooltip in px (default: 280) */
  maxWidth?: number;
  /** Whether the tooltip is enabled (default: true) */
  enabled?: boolean;
  /** Additional CSS class for the tooltip */
  className?: string;
}

/**
 * AnimatedTooltip - A unified tooltip component with spring-like entrance animation.
 * Inspired by react-bits, enhanced with project-specific APIs.
 *
 * @example
 * <AnimatedTooltip content="Click to edit">
 *   <button>Edit</button>
 * </AnimatedTooltip>
 *
 * @example
 * <AnimatedTooltip content={<div>Detailed info</div>} position="bottom" maxWidth={400}>
 *   <span>Hover me</span>
 * </AnimatedTooltip>
 */
export const AnimatedTooltip = ({
  content,
  children,
  position = 'top',
  offset = 8,
  showDelay = 200,
  hideDelay = 100,
  maxWidth = 280,
  enabled = true,
  className = '',
}: AnimatedTooltipProps) => {
  const [visible, setVisible] = useState(false);
  const [pos, setPos] = useState<TooltipPosition>({ x: 0, y: 0 });
  const targetRef = useRef<HTMLDivElement>(null);
  const tooltipRef = useRef<HTMLDivElement>(null);
  const showTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const updatePosition = useCallback(() => {
    if (!targetRef.current || !tooltipRef.current) return;

    const targetRect = targetRef.current.getBoundingClientRect();
    const tooltipRect = tooltipRef.current.getBoundingClientRect();

    let x = 0;
    let y = 0;

    switch (position) {
      case 'top':
        x = targetRect.left + (targetRect.width - tooltipRect.width) / 2;
        y = targetRect.top - tooltipRect.height - offset;
        break;
      case 'bottom':
        x = targetRect.left + (targetRect.width - tooltipRect.width) / 2;
        y = targetRect.bottom + offset;
        break;
      case 'left':
        x = targetRect.left - tooltipRect.width - offset;
        y = targetRect.top + (targetRect.height - tooltipRect.height) / 2;
        break;
      case 'right':
        x = targetRect.right + offset;
        y = targetRect.top + (targetRect.height - tooltipRect.height) / 2;
        break;
    }

    // Keep tooltip within viewport
    x = Math.max(4, Math.min(x, window.innerWidth - tooltipRect.width - 4));
    y = Math.max(4, Math.min(y, window.innerHeight - tooltipRect.height - 4));

    setPos({ x, y });
  }, [position, offset]);

  const handleMouseEnter = useCallback(() => {
    if (!enabled) return;
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    showTimerRef.current = setTimeout(() => {
      setVisible(true);
      requestAnimationFrame(() => updatePosition());
    }, showDelay);
  }, [enabled, showDelay, updatePosition]);

  const handleMouseLeave = useCallback(() => {
    if (showTimerRef.current) clearTimeout(showTimerRef.current);
    hideTimerRef.current = setTimeout(() => {
      setVisible(false);
    }, hideDelay);
  }, [hideDelay]);

  useEffect(() => {
    if (!visible) return;
    const handleScroll = () => updatePosition();
    const handleResize = () => updatePosition();
    window.addEventListener('scroll', handleScroll, true);
    window.addEventListener('resize', handleResize);
    return () => {
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', handleResize);
    };
  }, [visible, updatePosition]);

  useEffect(() => {
    return () => {
      if (showTimerRef.current) clearTimeout(showTimerRef.current);
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    };
  }, []);

  return (
    <div
      ref={targetRef}
      style={{ display: 'inline-flex' }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
    >
      {children}
      {visible && (
        <div
          ref={tooltipRef}
          className={`animated-tooltip ${className}`}
          style={{
            position: 'fixed',
            left: `${pos.x}px`,
            top: `${pos.y}px`,
            zIndex: 10000,
            maxWidth: `${maxWidth}px`,
            pointerEvents: 'none',
            animation: 'tooltip-fade-in 0.15s ease-out forwards',
          }}
        >
          {content}
          <style>{`
            .animated-tooltip {
              background: var(--bg-elevated, #1e1e1e);
              border: 1px solid var(--border-secondary, #333);
              border-radius: 6px;
              padding: 4px 8px;
              font-size: 12px;
              line-height: 1.4;
              color: var(--text-secondary, #ccc);
              box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
              white-space: nowrap;
            }
            @keyframes tooltip-fade-in {
              from {
                opacity: 0;
                transform: translateY(4px);
              }
              to {
                opacity: 1;
                transform: translateY(0);
              }
            }
          `}</style>
        </div>
      )}
    </div>
  );
};

export default AnimatedTooltip;