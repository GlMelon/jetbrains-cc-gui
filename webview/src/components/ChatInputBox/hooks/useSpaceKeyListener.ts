import { useEffect } from 'react';

/**
 * useSpaceKeyListener - Attaches a keydown listener for space-triggered tag rendering
 *
 * Uses native DOM listener to ensure consistent behavior across environments.
 */
export function useSpaceKeyListener({ editableRef, onKeyDown }: {
  editableRef: React.RefObject<HTMLDivElement | null>;
  onKeyDown: (e: KeyboardEvent) => void;
}): void {
  useEffect(() => {
    const el = editableRef.current;
    if (!el) return;

    el.addEventListener('keydown', onKeyDown);
    return () => {
      el.removeEventListener('keydown', onKeyDown);
    };
  }, [editableRef, onKeyDown]);
}

