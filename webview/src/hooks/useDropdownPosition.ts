import { useCallback, useEffect, useState, type CSSProperties, type RefObject } from 'react';
import { getAppViewport } from '../utils/viewport';

type DropdownAlignment = 'left' | 'right';

interface UseDropdownPositionOptions {
  buttonRef: RefObject<HTMLElement | null>;
  dropdownRef?: RefObject<HTMLElement | null>;
  preferredAlignment?: DropdownAlignment;
  minWidth?: number;
  submenuMaxHeight?: number;
  submenuBottomClearance?: number;
  submenu?: boolean;
  /**
   * When true, the hook attaches capture-phase scroll + resize listeners so
   * the (position:fixed) dropdown repositions itself as its anchor button
   * moves — e.g. while streaming output auto-scrolls the chat. Omit to keep
   * the legacy "compute once" behavior.
   */
  isOpen?: boolean;
}

interface PositionState {
  left?: number;
  top?: number | string;
  bottom?: number;
  maxHeight: number;
  submenuSide?: 'right' | 'left';
  submenuOverlap?: number;
}

const FALLBACK_ABSOLUTE_LEFT: CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  left: 0,
};

const FALLBACK_ABSOLUTE_RIGHT: CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  marginBottom: '4px',
  right: 0,
};

const FALLBACK_SUBMENU_RIGHT: CSSProperties = {
  position: 'absolute',
  top: 0,
  left: '100%',
};

export function useDropdownPosition({
  buttonRef,
  dropdownRef,
  preferredAlignment = 'left',
  minWidth = 200,
  submenuMaxHeight = 300,
  submenuBottomClearance = 96,
  submenu = false,
  isOpen = false,
}: UseDropdownPositionOptions): {
  positionedStyle: CSSProperties;
  maxHeight: number | undefined;
  recalculate: () => void;
} {
  const [positionState, setPositionState] = useState<PositionState | null>(null);

  const recalculate = useCallback(() => {
    const button = buttonRef.current;
    if (!button) return;

    const rect = button.getBoundingClientRect();
    const { width: viewportWidth, height: viewportHeight, left: viewportLeft, top: viewportTop } = getAppViewport();
    const padding = 8;
    const gap = 4;
    const buttonLeft = rect.left - viewportLeft;
    const buttonRight = rect.right - viewportLeft;
    const buttonTop = rect.top - viewportTop;
    const dropdown = dropdownRef?.current;

    if (submenu) {
      const availableRight = Math.max(0, viewportWidth - padding - buttonRight);
      const availableLeft = Math.max(0, buttonLeft - padding);
      const side: 'right' | 'left' = availableRight >= minWidth
        ? 'right'
        : availableLeft >= minWidth
          ? 'left'
          : availableRight >= availableLeft ? 'right' : 'left';
      const availableSideWidth = side === 'right' ? availableRight : availableLeft;
      const submenuOverlap = Math.max(0, minWidth - availableSideWidth);
      const measuredHeight = dropdown
        ? Math.max(dropdown.getBoundingClientRect().height, dropdown.scrollHeight)
        : submenuMaxHeight;
      const desiredHeight = Math.min(submenuMaxHeight, Math.max(1, measuredHeight));
      const availableBelow = viewportHeight - padding - buttonTop;
      const minTopOffset = padding - buttonTop;
      const topOffset = Math.max(
        minTopOffset,
        Math.min(0, availableBelow - desiredHeight - submenuBottomClearance),
      );
      const availableHeight = viewportHeight - padding - buttonTop - topOffset;
      const maxHeight = Math.max(1, Math.min(submenuMaxHeight, availableHeight));

      setPositionState({ top: topOffset, maxHeight, submenuSide: side, submenuOverlap });
      return;
    }

    const dropdownWidth = dropdown
      ? Math.min(
          Math.max(minWidth, dropdown.getBoundingClientRect().width),
          viewportWidth - (padding * 2),
        )
      : minWidth;
    const leftAlignedLeft = buttonLeft;
    const rightAlignedLeft = buttonRight - dropdownWidth;
    let left: number;

    if (preferredAlignment === 'right') {
      left = rightAlignedLeft >= padding ? rightAlignedLeft : leftAlignedLeft;
    } else {
      left = leftAlignedLeft + dropdownWidth + padding <= viewportWidth ? leftAlignedLeft : rightAlignedLeft;
    }
    left = Math.max(padding, Math.min(left, viewportWidth - dropdownWidth - padding));

    const bottomValue = viewportHeight - buttonTop + gap;
    const dropdownMaxHeight = buttonTop - gap - padding;

    setPositionState({ left, bottom: bottomValue, maxHeight: dropdownMaxHeight, submenuSide: 'right' });
  }, [buttonRef, dropdownRef, preferredAlignment, minWidth, submenu, submenuBottomClearance, submenuMaxHeight]);

  // While open, keep the fixed-positioned dropdown aligned with its anchor as
  // the page scrolls or resizes (e.g. chat auto-scroll during streaming).
  // Throttled to one recalculation per animation frame to avoid layout thrash.
  useEffect(() => {
    if (!isOpen) return;
    let rafId: number | null = null;
    const schedule = () => {
      if (rafId !== null) return;
      rafId = requestAnimationFrame(() => {
        rafId = null;
        recalculate();
      });
    };
    // capture phase: the dropdown is position:fixed, so ancestor scroll moves
    // the button without bubbling a scroll event on window.
    window.addEventListener('scroll', schedule, true);
    window.addEventListener('resize', schedule);
    return () => {
      window.removeEventListener('scroll', schedule, true);
      window.removeEventListener('resize', schedule);
      if (rafId !== null) cancelAnimationFrame(rafId);
    };
  }, [isOpen, recalculate]);

  if (!positionState) {
    if (submenu) {
      return { positionedStyle: FALLBACK_SUBMENU_RIGHT, maxHeight: undefined, recalculate };
    }
    return {
      positionedStyle: preferredAlignment === 'left' ? FALLBACK_ABSOLUTE_LEFT : FALLBACK_ABSOLUTE_RIGHT,
      maxHeight: undefined,
      recalculate,
    };
  }

  if (submenu) {
    const sideStyle: CSSProperties = positionState.submenuSide === 'left'
      ? { right: '100%', marginRight: `-${positionState.submenuOverlap ?? 0}px` }
      : { left: '100%', marginLeft: `-${positionState.submenuOverlap ?? 0}px` };

    return {
      positionedStyle: {
        position: 'absolute',
        top: positionState.top,
        ...sideStyle,
        zIndex: 10001,
      },
      maxHeight: positionState.maxHeight,
      recalculate,
    };
  }

  const { fixedPosDivisor } = getAppViewport();
  return {
    positionedStyle: {
      position: 'fixed',
      left: (positionState.left ?? 0) / fixedPosDivisor,
      bottom: (positionState.bottom ?? 0) / fixedPosDivisor,
      zIndex: 10000,
    },
    maxHeight: positionState.maxHeight / fixedPosDivisor,
    recalculate,
  };
}
