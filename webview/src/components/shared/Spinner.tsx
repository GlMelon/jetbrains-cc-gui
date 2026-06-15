export type SpinnerSize = 'xs' | 'sm' | 'md' | 'lg';

export interface SpinnerProps {
  /** 尺寸 */
  size?: SpinnerSize;
  /** 额外的 className */
  className?: string;
}

const SIZE_MAP: Record<SpinnerSize, string> = {
  xs: 'codicon-modifier-spin',
  sm: 'codicon-modifier-spin',
  md: 'codicon-modifier-spin',
  lg: 'codicon-modifier-spin',
};

/**
 * Spinner - 加载指示器。
 *
 * 统一处理：
 * - 基于 codicon 的旋转加载图标
 * - 多种尺寸
 */
export function Spinner({ size = 'md', className = '' }: SpinnerProps) {
  return (
    <span
      className={`codicon codicon-loading ${SIZE_MAP[size]} ${className}`}
      role="status"
      aria-label="Loading"
    />
  );
}
