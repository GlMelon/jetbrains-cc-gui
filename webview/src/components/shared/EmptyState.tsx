import { type ReactNode } from 'react';

export interface EmptyStateProps {
  /** 图标（emoji 或 codicon class） */
  icon?: string;
  /** 标题 */
  title: string;
  /** 副标题/描述 */
  description?: string;
  /** 提示文本 */
  hint?: string;
  /** 额外的操作按钮 */
  action?: ReactNode;
  /** 额外的 className */
  className?: string;
}

/**
 * EmptyState - 空状态组件。
 *
 * 统一处理：
 * - 图标 + 标题 + 副标题 + 提示 的居中布局
 * - 可选的操作按钮
 */
export function EmptyState({
  icon,
  title,
  description,
  hint,
  action,
  className = '',
}: EmptyStateProps) {
  return (
    <div className={`empty-state ${className}`}>
      {icon && (
        <div className="empty-state-icon">
          {icon.startsWith('codicon-') ? (
            <span className={`codicon ${icon}`} />
          ) : (
            <span>{icon}</span>
          )}
        </div>
      )}
      <div className="empty-state-title">{title}</div>
      {description && (
        <div className="empty-state-description">{description}</div>
      )}
      {hint && (
        <div className="empty-state-hint">{hint}</div>
      )}
      {action && (
        <div className="empty-state-action">{action}</div>
      )}
    </div>
  );
}
