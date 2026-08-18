import type { CSSProperties, ReactElement, ReactNode } from 'react';
import { cloneElement, isValidElement } from 'react';

export interface FadeContentProps {
  children: ReactNode;
  /** Enter animation duration in milliseconds. */
  duration?: number;
  /** Initial delay in milliseconds. */
  delay?: number;
  /** Vertical offset in pixels before the content enters. */
  offset?: number;
  /** Additional class name applied to the child without adding a wrapper. */
  className?: string;
  /** Additional inline styles applied to the child. */
  style?: CSSProperties;
  /** Disable the animation while preserving the child element. */
  disabled?: boolean;
}

/**
 * FadeContent - A layout-preserving enter animation.
 *
 * The component decorates a single child instead of introducing a wrapper.
 * This keeps existing flex/grid measurements, hit areas and positioning intact.
 */
export function FadeContent({
  children,
  duration = 300,
  delay = 0,
  offset = 8,
  className,
  style,
  disabled = false,
}: FadeContentProps) {
  if (disabled || !isValidElement(children)) {
    return children;
  }

  const child = children as ReactElement<{ className?: string; style?: CSSProperties }>;
  const childClassName = [child.props.className, 'rb-fade-content', className].filter(Boolean).join(' ');
  const childStyle: CSSProperties = {
    ...child.props.style,
    ...style,
    '--rb-fade-duration': `${duration}ms`,
    '--rb-fade-delay': `${delay}ms`,
    '--rb-fade-offset': `${offset}px`,
  } as CSSProperties;

  return cloneElement(child, { className: childClassName, style: childStyle });
}

export default FadeContent;
