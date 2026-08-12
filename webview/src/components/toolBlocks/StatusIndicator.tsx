import type { CSSProperties } from 'react';
import { SpinLoader } from '../react-bits';

export interface StatusIndicatorProps {
  /** 工具是否出错 */
  isError: boolean;
  /** 工具是否已完成 */
  isCompleted: boolean;
  /** 附加 className */
  className?: string;
  /** 透传给根元素的 inline style（如固定 margin 定位，覆盖 .tool-status-indicator 默认布局） */
  style?: CSSProperties;
}

/**
 * StatusIndicator - 工具状态指示器（pending / completed / error 三态统一）。
 *
 * 根元素始终带 `tool-status-indicator` + 状态修饰类，由 tools.less 统一控制
 * 尺寸/底色（completed=绿点、error=红点）；pending 态用 react-bits SpinLoader
 * 旋转环（warning 色）替代旧 CSS 旋转环，表示"执行中"。
 *
 * style 透传给根元素，供调用方微调定位（如 Edit/Read 的固定左间距、Search 的推右）。
 */
export function StatusIndicator({ isError, isCompleted, className = '', style }: StatusIndicatorProps) {
  if (!isError && !isCompleted) {
    return (
      <SpinLoader
        variant="ring"
        size={12}
        strokeWidth={2}
        duration={0.85}
        color="var(--color-warning)"
        className={`tool-status-indicator pending ${className}`}
        style={style}
      />
    );
  }
  const statusClass = isError ? 'error' : 'completed';
  return <div className={`tool-status-indicator ${statusClass} ${className}`} style={style} />;
}

export default StatusIndicator;
