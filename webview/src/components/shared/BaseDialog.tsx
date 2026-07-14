import { type ReactNode, useEffect, useState } from 'react';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { CloseIcon } from '../Icons';

/**
 * 退出动画时长（毫秒），须与 variables.less 的 --dlg-out (0.16s) 保持一致。
 * 关闭时先进入 leaving 态播放退出动画，结束后再真正卸载，避免瞬切。
 */
const DIALOG_LEAVE_MS = 160;

export type DialogSize = 'sm' | 'md' | 'lg' | 'xl' | 'auto';

export interface BaseDialogProps {
  /** 弹窗是否打开 */
  isOpen: boolean;
  /** 关闭回调 */
  onClose: () => void;
  /** 点击遮罩是否关闭，默认 true */
  overlayClosable?: boolean;
  /** 弹窗尺寸 */
  size?: DialogSize;
  /** 无障碍标签 */
  ariaLabel?: string;
  /** 弹窗内容 */
  children: ReactNode;
  /** 额外的 className */
  className?: string;
}

/**
 * BaseDialog - 所有 Dialog 的基础壳组件。
 *
 * 统一处理：
 * - 遮罩层渲染
 * - ESC 键关闭
 * - 点击遮罩关闭
 * - 无障碍属性 (role=dialog, aria-modal, aria-label)
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

  if (!shouldRender) {
    return null;
  }

  const sizeClass = size !== 'auto' ? `dialog-size-${size}` : '';

  return (
    <div
      className={`dialog-overlay ${className}${leaving ? ' dialog-leaving' : ''}`}
      onClick={overlayClosable ? onClose : undefined}
      role="dialog"
      aria-modal="true"
      aria-label={ariaLabel}
    >
      <div
        className={`dialog-base ${sizeClass}`}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
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
  return (
    <div className="dialog-header">
      <h3>
        {icon && <span style={{ marginRight: '8px' }}>{icon}</span>}
        {title}
      </h3>
      {onClose && (
        <button className="close-btn" onClick={onClose}>
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
