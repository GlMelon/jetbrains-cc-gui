import { type ReactNode, useState, useEffect, useRef } from 'react';
import { StatusIndicator } from './StatusIndicator';

/**
 * Animation duration constants for tool block state transitions.
 * These must match the CSS animation durations in tools.less:
 * - tool-complete-pulse: 0.4s (400ms)
 * - tool-error-shake: 0.5s (500ms)
 */
const TOOL_COMPLETE_ANIMATION_MS = 400;
const TOOL_ERROR_ANIMATION_MS = 500;

interface ToolBlockShellProps {
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
  /** 启用增强动画效果（默认 true） */
  enableAnimations?: boolean;
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
 * - 增强动画：执行中脉冲、完成淡入、错误抖动
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
  enableAnimations = true,
}: ToolBlockShellProps) {
  const [animPhase, setAnimPhase] = useState<'idle' | 'completing' | 'erroring'>('idle');
  const prevCompletedRef = useRef(isCompleted);
  const prevErrorRef = useRef(isError);

  // 检测状态变化并触发动画
  useEffect(() => {
    if (!enableAnimations) return;

    // 完成动画：pending → completed
    if (!prevCompletedRef.current && isCompleted && !isError) {
      setAnimPhase('completing');
      const timer = setTimeout(() => setAnimPhase('idle'), TOOL_COMPLETE_ANIMATION_MS);
      prevCompletedRef.current = isCompleted;
      return () => clearTimeout(timer);
    }

    // 错误动画：任何 → error
    if (!prevErrorRef.current && isError) {
      setAnimPhase('erroring');
      const timer = setTimeout(() => setAnimPhase('idle'), TOOL_ERROR_ANIMATION_MS);
      prevErrorRef.current = isError;
      return () => clearTimeout(timer);
    }

    prevCompletedRef.current = isCompleted;
    prevErrorRef.current = isError;
  }, [isCompleted, isError, enableAnimations]);

  // 构建容器 className
  const containerClasses = [
    'task-container',
    className,
    animPhase === 'completing' ? 'tool-completing' : '',
    animPhase === 'erroring' ? 'tool-erroring' : '',
  ].filter(Boolean).join(' ');

  return (
    <div className={containerClasses}>
      <div
        className={`task-header ${headerClassName} ${expanded ? 'expanded' : ''}`}
        onClick={onToggle}
      >
        <div className="task-title-section">
          {titleContent}
        </div>

        <StatusIndicator isError={isError} isCompleted={isCompleted} />
      </div>

      <div className={`task-details-accordion ${expanded ? 'expanded' : ''}`}>
        <div className="task-details">
          {children}
        </div>
      </div>
    </div>
  );
}

// StatusIndicator 已提取为共享组件 ./StatusIndicator（pending 态用 react-bits SpinLoader）。
