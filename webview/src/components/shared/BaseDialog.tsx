import { type ReactNode, useEffect, useState } from 'react';
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

export interface BaseDialogProps {
  isOpen: boolean;
  onClose: () => void;
  overlayClosable?: boolean;
  size?: DialogSize;
  ariaLabel?: string;
  children: ReactNode;
  className?: string;
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
}: BaseDialogProps) {
  // ESC 键关闭
  useEscapeClose(isOpen, onClose);

  // 延迟卸载：isOpen 关闭时不立即 return null，先进入 leaving 态播放
  // 退出动画（.dialog-leaving），动画结束（DIALOG_LEAVE_MS）后再卸载。
  const [shouldRender, setShouldRender] = useState(isOpen);
  const [leaving, setLeaving] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setShouldRender(true);
      setLeaving(false);
    } else if (shouldRender) {
      setLeaving(true);
      const timer = setTimeout(() => {
        setShouldRender(false);
        setLeaving(false);
      }, DIALOG_LEAVE_MS);
      return () => clearTimeout(timer);
    }
  }, [isOpen, shouldRender]);

  // 焦点管理：open=isOpen（逻辑开关，render 阶段捕获触发者 + 关闭瞬间归还焦点），
  // ready=shouldRender（DOM 已挂载）。两者共同作为 effect 依赖，确保 dialogRef.current
  // 就绪后才初始化焦点陷阱，避免延迟卸载导致的 dialogRef 时序空窗。
  const { dialogRef } = useDialogFocus({ open: isOpen, ready: shouldRender });

  if (!shouldRender) {
    return null;
  }

  const sizeClass = size !== 'auto' ? `dialog-size-${size}` : '';

  return createPortal(
    // 遮罩层：纯视觉，role=presentation，承担点击空白关闭。
    // A11Y1：role=dialog 不再放在遮罩上，下沉到内层弹窗本体。
    <div
      className={`dialog-overlay ${className}${leaving ? ' dialog-leaving' : ''}`}
      onClick={overlayClosable ? onClose : undefined}
    >
      {/* 弹窗本体：role=dialog 在此层，承接焦点管理（tabIndex=-1 允许无子焦点时自身接收焦点） */}
      <div
        ref={dialogRef}
        className={`dialog-base ${sizeClass}`}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        tabIndex={-1}
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
