import { type ReactNode } from 'react';
import { useEscapeClose } from '../../hooks/useEscapeClose';

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

  if (!isOpen) {
    return null;
  }

  const sizeClass = size !== 'auto' ? `dialog-size-${size}` : '';

  return (
    <div
      className={`dialog-overlay ${className}`}
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
          <span className="codicon codicon-close" />
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
