import { useEffect, useRef, useState, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { FadeContent } from '../react-bits';
import './ContextMenu.css';

/**
 * Exit animation duration in ms.
 * Must match --dlg-out (0.16s = 160ms) in the CSS variables.
 */
const CONTEXT_MENU_EXIT_ANIMATION_MS = 160;

type ContextMenuItem =
  | { separator: true }
  | { separator?: false; label: string; action: () => void; disabled?: boolean };

interface ContextMenuProps {
  x: number;
  y: number;
  items: ContextMenuItem[];
  onClose: () => void;
}

export function ContextMenu({ x, y, items, onClose }: ContextMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const [isExiting, setIsExiting] = useState(false);

  // State-driven position to avoid React overwriting imperative DOM changes
  const [pos, setPos] = useState({ left: x, top: y });

  // Adjust position after mount to keep menu within viewport
  useEffect(() => {
    const menu = menuRef.current;
    if (!menu) return;
    const rect = menu.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    setPos({
      left: x + rect.width > vw ? vw - rect.width - 4 : x,
      top: y + rect.height > vh ? vh - rect.height - 4 : y,
    });
  }, [x, y]);

  const handleClose = useCallback(() => {
    setIsExiting(true);
    setTimeout(() => onCloseRef.current(), CONTEXT_MENU_EXIT_ANIMATION_MS);
  }, []);

  // Close on outside click, escape, scroll
  // Use menuRef.contains check instead of stopPropagation to avoid
  // native vs React event ordering issues in JBCefBrowser
  useEffect(() => {
    const handleMouseDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        handleClose();
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose();
    };
    const handleScroll = () => handleClose();
    document.addEventListener('mousedown', handleMouseDown);
    document.addEventListener('keydown', handleKeyDown);
    window.addEventListener('scroll', handleScroll, true);
    return () => {
      document.removeEventListener('mousedown', handleMouseDown);
      document.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('scroll', handleScroll, true);
    };
  }, [handleClose]);

  const menuStyle: React.CSSProperties = { left: pos.left, top: pos.top };

  return createPortal(
    <FadeContent disabled={isExiting} duration={160} offset={4}>
      <div
        ref={menuRef}
        className={`context-menu ${isExiting ? 'context-menu-exit' : ''}`}
        role="menu"
        aria-label="Context menu"
        style={menuStyle}
      >
        {items.map((item, i) =>
          item.separator ? (
            <div key={`sep-${i}`} className="context-menu-separator" role="separator" />
          ) : (
            <div
              key={`item-${i}`}
              className={`context-menu-item${item.disabled ? ' disabled' : ''}`}
              role="menuitem"
              aria-disabled={item.disabled || false}
              tabIndex={item.disabled ? -1 : 0}
              onClick={() => {
                if (!item.disabled) {
                  item.action();
                  handleClose();
                }
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !item.disabled) {
                  item.action();
                  handleClose();
                }
              }}
            >
              {item.label}
            </div>
          )
        )}
      </div>
    </FadeContent>,
    document.body
  );
}
