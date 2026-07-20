import { useEffect, useRef } from 'react';

/**
 * useDialogFocus —— BaseDialog 的无障碍焦点管理（A11Y1）。
 *
 * 提供符合 WAI-ARIA Dialog 模式的焦点行为：
 * - 打开时记录触发元素（document.activeElement），并将焦点移入弹窗；
 * - Tab / Shift+Tab 在弹窗内可聚焦元素间循环（focus trap），不逸出到背景；
 * - 尊重子元素自带 [autofocus]（如 ConfirmDialog 的确认按钮）作为初始焦点；
 * - 关闭时把焦点归还触发元素（restore focus）；
 * - 支持嵌套弹窗：模块级栈保证只有栈顶弹窗接管 Tab 与初始焦点，
 *   栈顶之下所有弹窗与背景被 `inert` 屏蔽（不可聚焦 / 不可交互 / 屏幕阅读器跳过）。
 *
 * 纯手写、零运行时依赖（不引入 focus-trap 第三方库），与 H7 纯 CSS 哲学一致。
 * 与 reduced-motion 无关，焦点管理是结构行为不是动效，无需 H7 降级。
 *
 * open 与 ready 的分离：BaseDialog 用 shouldRender 实现延迟卸载（退出动画期间仍挂载）。
 * - open（=isOpen）：逻辑开关，驱动 render 阶段的触发元素捕获与关闭时归还；
 * - ready（=shouldRender）：DOM 是否已挂载。dialog 实际存在（open && ready）时
 *   才初始化焦点陷阱。二者共同作为 effect 依赖，确保 dialogRef.current 已就绪。
 */

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/** 当前打开中的弹窗 DOM 栈（后进先出，栈顶 = 当前活跃弹窗）。 */
const dialogStack: HTMLElement[] = [];

function isFocusableCandidate(el: Element): el is HTMLElement {
  if (!(el instanceof HTMLElement)) return false;
  if (el.hidden) return false;
  if ((el as HTMLButtonElement).disabled) return false;
  return true;
}

function collectFocusable(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    isFocusableCandidate,
  );
}

function isTopDialog(dialog: HTMLElement): boolean {
  return dialogStack[dialogStack.length - 1] === dialog;
}

/**
 * 栈变化时同步背景 inert：栈顶弹窗存在时，body 的直接子节点中
 * 不包含栈顶弹窗的节点一律 inert（含其下所有弹窗与背景）。
 * 栈空时全部解除 inert。
 *
 * 嵌套场景：detail 弹窗打开后成为新栈顶，主弹窗的 portal 容器不再包含栈顶
 * → 主弹窗随之被 inert，只有栈顶可交互，符合 WAI-ARIA 嵌套 dialog 语义。
 */
function syncBackgroundInert(): void {
  const top = dialogStack[dialogStack.length - 1];
  document.body.childNodes.forEach((node) => {
    if (!(node instanceof HTMLElement)) return;
    node.inert = !!top && !node.contains(top);
  });
}

export interface DialogFocusOptions {
  /** 弹窗逻辑打开状态（BaseDialog 的 isOpen）。 */
  open: boolean;
  /** 弹窗 DOM 是否已挂载（BaseDialog 的 shouldRender，延迟卸载期间为 true）。 */
  ready: boolean;
}

export interface DialogFocusController {
  /** 附到弹窗根元素（role=dialog 的容器）上的 ref。 */
  dialogRef: React.RefObject<HTMLDivElement | null>;
}

export function useDialogFocus({ open, ready }: DialogFocusOptions): DialogFocusController {
  const dialogRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLElement | null>(null);
  const prevOpenRef = useRef(false);

  // 在 render 阶段捕获打开者：open 由 false→true 的瞬间记录 document.activeElement。
  // 必须在 render 阶段（而非 useEffect）捕获——React 的 autoFocus 在 commit 阶段执行，
  // 会先把焦点移入弹窗；若在 passive effect 里再读 activeElement，得到的是弹窗内元素
  // 而非真正的触发元素，关闭时焦点就无法归还。
  // 这是幂等的只读副作用（读 activeElement），严格模式 double-invoke 下两次读到同值。
  if (open && !prevOpenRef.current) {
    triggerRef.current = (document.activeElement as HTMLElement) ?? null;
  }
  prevOpenRef.current = open;

  useEffect(() => {
    // 仅当逻辑打开且 DOM 已挂载时初始化焦点管理。ready 未就绪时 dialog 尚未渲染，
    // dialogRef.current 为 null；待 shouldRender 变 true 后依赖变化触发重跑。
    if (!open || !ready) return;
    const dialog = dialogRef.current;
    if (!dialog) return;
    dialogStack.push(dialog);
    syncBackgroundInert();

    // 若焦点已在弹窗内（React autoFocus 或外部已聚焦），尊重之不覆盖；
    // 否则聚焦首个可聚焦元素（无则弹窗容器自身，由 BaseDialog 设 tabIndex=-1）。
    // 注意：React 的 autoFocus prop 不产生 DOM [autofocus] 属性，故不能靠
    // querySelector('[autofocus]') 探测，改用「焦点是否已落入弹窗」判断。
    if (!dialog.contains(document.activeElement)) {
      const focusable = collectFocusable(dialog);
      const target = focusable[0] ?? dialog;
      target.focus({ preventScroll: true });
    }

    const handleKeydown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;
      if (!isTopDialog(dialog)) return;
      const focusable = collectFocusable(dialog);
      if (focusable.length === 0) {
        e.preventDefault();
        dialog.focus({ preventScroll: true });
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (e.shiftKey) {
        if (active === first || !dialog.contains(active)) {
          e.preventDefault();
          last.focus({ preventScroll: true });
        }
      } else if (active === last || !dialog.contains(active)) {
        e.preventDefault();
        first.focus({ preventScroll: true });
      }
    };

    // capture 阶段拦截，确保早于业务 keydown（如 useEscapeClose 的 window bubble 监听）
    document.addEventListener('keydown', handleKeydown, true);

    return () => {
      document.removeEventListener('keydown', handleKeydown, true);
      const idx = dialogStack.indexOf(dialog);
      if (idx >= 0) dialogStack.splice(idx, 1);
      syncBackgroundInert();
      // 关闭时把焦点归还打开者（仅当仍在 DOM 内）。真实浏览器会移动焦点；
      // happy-dom 测试环境对 activeElement 更新有限制，故测试用 spy 验证调用。
      const trigger = triggerRef.current;
      if (trigger && document.body.contains(trigger) && typeof trigger.focus === 'function') {
        trigger.focus({ preventScroll: true });
      }
    };
  }, [open, ready]);

  return { dialogRef };
}
