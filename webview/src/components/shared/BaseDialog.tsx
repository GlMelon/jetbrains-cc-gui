import { type ReactNode, useEffect, useState, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { useDialogFocus } from '../../hooks/useDialogFocus';
import { CloseIcon } from '../Icons';

/**
 * 退出动画时长（毫秒），须与 variables.less 的 --dlg-out (0.16s) 保持一致。
 * 关闭时先进入 leaving 态播放退出动画，结束后再真正卸载，避免瞬切。
 */
const DIALOG_LEAVE_MS = 160;

export type DialogSize = 'sm' | 'md' | 'lg' | 'xl' | 'auto';
export type DialogAnimation = 'css' | 'pop' | 'slide' | 'scale' | 'flip';

export interface BaseDialogProps {
  isOpen: boolean;
  onClose: () => void;
  overlayClosable?: boolean;
  size?: DialogSize;
  ariaLabel?: string;
  children: ReactNode;
  className?: string;
  /** Animation mode: 'css' uses CSS classes (default), others use inline transform animations */
  animation?: DialogAnimation;
  /** Animation duration in ms (only used when animation != 'css', default: 320) */
  animationDuration?: number;
}

/**
 * BaseDialog - 所有 Dialog 的基础壳组件。
 *
 * 统一处理（A11Y1 已补齐焦点管理）：
 * - portal 到 document.body（保证弹窗在 DOM 末尾，背景 inert 干净）
 * - 遮罩层渲染（role=presentation，纯视觉遮罩 + 点击空白关闭）
 * - 弹窗本体 role=dialog / aria-modal / aria-label（A11Y1：role 从 overlay 下沉到本体）
 * - ESC 键关闭、Tab 焦点循环、初始焦点、关闭后归还焦点（useDialogFocus）
 * - 嵌套弹窗：栈顶接管焦点，其余 inert
 * - 统一的 overlay className
 */
export function BaseDialog({
  isOpen,
  onClose,
  overlayClosable = true,
  size = 'auto',
  ariaLabel,
  children,
  className = '',
  animation = 'css',
  animationDuration = 320,
}: BaseDialogProps) {
  // ESC 键关闭
  useEscapeClose(isOpen, onClose);

  // 延迟卸载：isOpen 关闭时不立即 return null，先进入 leaving 态播放
  // 退出动画（.dialog-leaving），动画结束（DIALOG_LEAVE_MS）后再卸载。
  const [shouldRender, setShouldRender] = useState(isOpen);
  const [leaving, setLeaving] = useState(false);
  const [animState, setAnimState] = useState<'entering' | 'entered' | 'leaving' | 'left'>(
    isOpen ? 'entered' : 'left'
  );
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isOpen) {
      setShouldRender(true);
      setLeaving(false);
      if (animation !== 'css') {
        setAnimState('entering');
      }
    } else if (shouldRender) {
      if (animation !== 'css') {
        setAnimState('leaving');
        const timer = setTimeout(() => {
          setAnimState('left');
          setShouldRender(false);
          setLeaving(false);
        }, animationDuration);
        return () => clearTimeout(timer);
      } else {
        setLeaving(true);
        const timer = setTimeout(() => {
          setShouldRender(false);
          setLeaving(false);
        }, DIALOG_LEAVE_MS);
        return () => clearTimeout(timer);
      }
    }
  }, [isOpen, shouldRender, animation, animationDuration]);

  // Trigger entering state after mount
  useEffect(() => {
    if (animState === 'entering') {
      requestAnimationFrame(() => {
        setAnimState('entered');
      });
    }
  }, [animState]);

  // 焦点管理
  const { dialogRef: focusRef } = useDialogFocus({ open: isOpen, ready: shouldRender });

  if (!shouldRender) {
    return null;
  }

  const sizeClass = size !== 'auto' ? `dialog-size-${size}` : '';

  // Compute animation styles for non-css modes
  const getAnimStyles = (): React.CSSProperties => {
    if (animation === 'css') return {};
    const isEntering = animState === 'entering';
    const isLeaving = animState === 'leaving';
    const base: React.CSSProperties = {
      transition: `transform ${animationDuration}ms cubic-bezier(0.34, 1.6, 0.64, 1), opacity ${animationDuration}ms ease`,
      willChange: 'transform, opacity',
    };
    switch (animation) {
      case 'pop':
        return {
          ...base,
          opacity: isLeaving ? 0 : 1,
          transform: isLeaving ? 'scale(0.95) translateY(8px)' : isEntering ? 'scale(0.95) translateY(8px)' : 'scale(1) translateY(0)',
        };
      case 'slide':
        return {
          ...base,
          opacity: isLeaving ? 0 : 1,
          transform: isLeaving ? 'translateY(100%)' : isEntering ? 'translateY(100%)' : 'translateY(0)',
        };
      case 'scale':
        return {
          ...base,
          opacity: isLeaving ? 0 : 1,
          transform: isLeaving ? 'scale(0.8)' : isEntering ? 'scale(0.8)' : 'scale(1)',
        };
      case 'flip':
        return {
          ...base,
          opacity: isLeaving ? 0 : 1,
          transform: isLeaving
            ? 'perspective(1000px) rotateX(90deg)'
            : isEntering
              ? 'perspective(1000px) rotateX(90deg)'
              : 'perspective(1000px) rotateX(0)',
        };
      default:
        return {};
    }
  };

  const overlayAnimStyle: React.CSSProperties = animation !== 'css'
    ? {
        transition: `opacity ${animationDuration}ms ease`,
        opacity: animState === 'leaving' ? 0 : 1,
      }
    : {};

  // Merge refs
  const setRefs = (el: HTMLDivElement | null) => {
    (dialogRef as React.MutableRefObject<HTMLDivElement | null>).current = el;
    (focusRef as React.MutableRefObject<HTMLDivElement | null>).current = el;
  };

  return createPortal(
    <div
      className={`dialog-overlay ${animation === 'css' ? `${className}${leaving ? ' dialog-leaving' : ''}` : className}`}
      style={overlayAnimStyle}
      onClick={overlayClosable ? onClose : undefined}
    >
      <div
        ref={setRefs}
        className={`dialog-base ${sizeClass}`}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        tabIndex={-1}
        style={getAnimStyles()}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>,
    document.body,
  );
}

/**
 * BaseDialog.Header - 弹窗头部
 */
export function DialogHeader({
  title,
  icon,
  onClose,
  children,
}: {
  title: ReactNode;
  icon?: ReactNode;
  onClose?: () => void;
  children?: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div className="dialog-header">
      <h3>
        {icon && <span style={{ marginRight: '8px' }}>{icon}</span>}
        {title}
      </h3>
      {onClose && (
        <button
          className="close-btn"
          onClick={onClose}
          type="button"
          aria-label={t('common.close', '关闭')}
        >
          <CloseIcon size={16} />
        </button>
      )}
      {children}
    </div>
  );
}

/**
 * BaseDialog.Body - 弹窗主体
 */
export function DialogBody({
  children,
  className = '',
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`dialog-body ${className}`}>
      {children}
    </div>
  );
}

/**
 * BaseDialog.Footer - 弹窗底部
 */
export function DialogFooter({
  align = 'right',
  children,
}: {
  align?: 'left' | 'center' | 'right';
  children: ReactNode;
}) {
  const alignStyle: React.CSSProperties =
    align === 'center'
      ? { justifyContent: 'center' }
      : align === 'left'
        ? { justifyContent: 'flex-start' }
        : { marginLeft: 'auto' };

  return (
    <div className="dialog-footer">
      <div className="footer-actions" style={alignStyle}>
        {children}
      </div>
    </div>
  );
}
