import { flushSync } from 'react-dom';

/**
 * View Transitions API 封装（S3-6 布局过渡 PoC）。
 *
 * 浏览器原生 document.startViewTransition 用于 FLIP 式布局过渡（如拖拽排序重排、
 * 跨分组过滤重排），零依赖、优于引入第三方动画库。JCEF 的 Chromium 版本若不支持
 * 则特性降级，走调用方原有的同步更新路径，保证零回归。
 *
 * 与 base.less 的 prefers-reduced-motion 全局收口保持一致：reduce 时不启动 VT，
 * 避免 VT 动画与被归零的 CSS transition 产生动效撕裂。
 */

const hasStartViewTransition = (): boolean =>
  typeof document !== 'undefined' &&
  // lib.dom 在较新 TS 版本已收录 startViewTransition；运行时再由本守卫兜底。
  typeof (document as Document & { startViewTransition?: unknown }).startViewTransition === 'function';

const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * 在一次 View Transition 内执行同步 DOM 更新。
 *
 * React 的 setState 默认并发异步，而 startViewTransition 的回调必须在返回前完成
 * DOM 更新，否则 VT 捕获不到新快照。故用 flushSync 强制同步刷新。
 *
 * 不支持 VT 或 reduce-motion 时，退化为直接同步执行 update()，行为与未接入 VT 一致。
 *
 * @param update 触发重排的 React 状态更新（内部会被 flushSync 同步刷新）
 */
export const runWithViewTransition = (update: () => void): void => {
  if (!hasStartViewTransition() || prefersReducedMotion()) {
    update();
    return;
  }
  const doc = document as Document & {
    startViewTransition: (cb: () => void) => { finished: Promise<void> };
  };
  doc.startViewTransition(() => {
    flushSync(update);
  });
};
