import type { ReactNode } from 'react';

/**
 * SkeletonList —— 首次加载列表的骨架屏占位（H5）。
 *
 * 设计约束（来自 comprehensive-optimization-directions.md H5）：
 * - 仅用于「首次加载且有可感知延迟的列表或页面」；
 * - 调用方必须以真实后端请求/事件状态（loading）驱动显示/隐藏，**不得**在前端自行推导
 *   业务加载结论、不得用无限 skeleton 掩盖请求未返回、不得在本地瞬时数据上制造额外等待；
 * - 空/错误/重试状态由各自的真实状态分支处理，不由 skeleton 承担。
 *
 * 脉冲动画的 reduced-motion 降级由 base.less 的全局
 * `@media (prefers-reduced-motion: reduce)` 收口（animation-duration: 0.01ms + iteration-count: 1），
 * 本组件不重复声明。
 */
export interface SkeletonListProps {
  /** 无障碍标签：screen reader 据此宣读「加载中」语义（如 t('mcp.loading')）。 */
  label: string;
  /** 占位项数量，默认 3。 */
  count?: number;
  /** 单个占位项内容；缺省渲染「图标 + 标题条 + 副条」的标准卡片式占位。 */
  item?: ReactNode;
}

const DEFAULT_ITEM = (
  <>
    <div className="skeleton-bar skeleton-bar-icon" />
    <div className="skeleton-bar-group">
      <div className="skeleton-bar skeleton-bar-title" />
      <div className="skeleton-bar skeleton-bar-sub" />
    </div>
  </>
);

export function SkeletonList({ label, count = 3, item }: SkeletonListProps) {
  return (
    <div className="skeleton-list" role="status" aria-label={label}>
      {Array.from({ length: count }, (_, i) => (
        <div className="skeleton-item" key={i} aria-hidden="true">
          {item ?? DEFAULT_ITEM}
        </div>
      ))}
    </div>
  );
}
