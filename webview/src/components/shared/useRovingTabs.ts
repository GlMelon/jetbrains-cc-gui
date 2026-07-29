import { useCallback, useRef } from 'react';
import type { KeyboardEventHandler, RefCallback } from 'react';

interface UseRovingTabsOptions<T extends string> {
  values: readonly T[];
  activeValue: T;
  onActivate: (value: T) => boolean | void;
}

interface RovingTabProps {
  tabIndex: 0 | -1;
  ref: RefCallback<HTMLElement>;
  onKeyDown: KeyboardEventHandler<HTMLElement>;
}

/**
 * Shared WAI-ARIA tabs keyboard behavior.
 *
 * Tabs use automatic activation: moving focus with Arrow/Home/End also activates
 * the destination tab. Returning false from onActivate keeps focus on the
 * current tab, which lets callers reject a transition (for example invalid JSON).
 */
export function useRovingTabs<T extends string>({
  values,
  activeValue,
  onActivate,
}: UseRovingTabsOptions<T>) {
  const tabRefs = useRef(new Map<T, HTMLElement>());

  const setTabRef = useCallback(
    (value: T): RefCallback<HTMLElement> =>
      (node) => {
        if (node) {
          tabRefs.current.set(value, node);
        } else {
          tabRefs.current.delete(value);
        }
      },
    [],
  );

  const activateAndFocus = useCallback(
    (value: T) => {
      if (onActivate(value) === false) return;
      tabRefs.current.get(value)?.focus();
    },
    [onActivate],
  );

  const getTabProps = useCallback(
    (value: T): RovingTabProps => ({
      tabIndex: activeValue === value ? 0 : -1,
      ref: setTabRef(value),
      onKeyDown: (event) => {
        const currentIndex = values.indexOf(value);
        if (currentIndex < 0 || values.length === 0) return;

        let nextIndex: number;
        switch (event.key) {
          case 'ArrowRight':
          case 'ArrowDown':
            nextIndex = (currentIndex + 1) % values.length;
            break;
          case 'ArrowLeft':
          case 'ArrowUp':
            nextIndex = (currentIndex - 1 + values.length) % values.length;
            break;
          case 'Home':
            nextIndex = 0;
            break;
          case 'End':
            nextIndex = values.length - 1;
            break;
          default:
            return;
        }

        event.preventDefault();
        activateAndFocus(values[nextIndex]);
      },
    }),
    [activeValue, activateAndFocus, setTabRef, values],
  );

  return { getTabProps };
}
