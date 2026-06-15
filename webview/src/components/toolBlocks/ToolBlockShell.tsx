import { type ReactNode } from 'react';

export interface ToolBlockShellProps {
  /** 是否展开 */
  expanded: boolean;
  /** 切换展开状态 */
  onToggle: () => void;
  /** 工具是否已完成（有结果或被拒绝） */
  isCompleted: boolean;
  /** 工具是否出错 */
  isError: boolean;
  /** 标题区域的内容（icon + title + description） */
  titleContent: ReactNode;
  /** 展开后显示的内容 */
  children: ReactNode;
  /** 额外的 className */
  className?: string;
  /** header 的额外 className */
  headerClassName?: string;
}

/**
 * ToolBlockShell - 工具卡片的统一外壳。
 *
 * 统一处理：
 * - task-container 包装
 * - task-header 带 onClick 切换
 * - task-title-section
 * - tool-status-indicator 状态点
 * - 条件渲染展开内容
 */
export function ToolBlockShell({
  expanded,
  onToggle,
  isCompleted,
  isError,
  titleContent,
  children,
  className = '',
  headerClassName = '',
}: ToolBlockShellProps) {
  return (
    <div className={`task-container ${className}`}>
      <div
        className={`task-header ${headerClassName} ${expanded ? 'expanded' : ''}`}
        onClick={onToggle}
      >
        <div className="task-title-section">
          {titleContent}
        </div>

        <StatusIndicator isError={isError} isCompleted={isCompleted} />
      </div>

      {expanded && children}
    </div>
  );
}

export interface StatusIndicatorProps {
  /** 工具是否出错 */
  isError: boolean;
  /** 工具是否已完成 */
  isCompleted: boolean;
}

/**
 * StatusIndicator - 工具状态指示器。
 *
 * 统一状态点的 className 逻辑：
 * - error: 红色
 * - completed: 绿色
 * - pending: 黄色呼吸动画
 */
export function StatusIndicator({ isError, isCompleted }: StatusIndicatorProps) {
  const statusClass = isError ? 'error' : isCompleted ? 'completed' : 'pending';
  return <div className={`tool-status-indicator ${statusClass}`} />;
}
