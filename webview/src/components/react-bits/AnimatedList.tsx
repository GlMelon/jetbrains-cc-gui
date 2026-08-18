import type { ReactNode } from 'react';
import { Children, isValidElement } from 'react';
import { FadeContent } from './FadeContent';

export interface AnimatedListProps {
  children: ReactNode;
  /** Delay between adjacent items in milliseconds. */
  stagger?: number;
  /** Enter animation duration in milliseconds. */
  duration?: number;
  /** Vertical offset in pixels before each item enters. */
  offset?: number;
  /** Additional class name for each item. */
  itemClassName?: string;
  /** Disable the animation while preserving the list children. */
  disabled?: boolean;
}

/**
 * AnimatedList - Decorates list items without adding a layout wrapper.
 *
 * It intentionally handles enter animation only. Existing removal and state
 * lifecycles remain owned by the caller.
 */
export function AnimatedList({
  children,
  stagger = 50,
  duration = 300,
  offset = 8,
  itemClassName,
  disabled = false,
}: AnimatedListProps) {
  if (disabled) return children;

  return Children.map(children, (child, index) => {
    if (!isValidElement(child)) return child;
    return (
      <FadeContent
        delay={index * stagger}
        duration={duration}
        offset={offset}
        className={itemClassName}
      >
        {child}
      </FadeContent>
    );
  });
}

export default AnimatedList;
