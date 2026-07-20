import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BaseDialog, DialogHeader } from './BaseDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallbackOrOptions?: unknown) =>
      typeof fallbackOrOptions === 'string' ? fallbackOrOptions : key,
    i18n: { language: 'en' },
  }),
}));

/**
 * 测试用弹窗：DialogHeader（含 close 按钮）+ 两个 action 按钮。
 * 可聚焦元素 DOM 顺序：close-btn（aria-label 关闭）→ first-action → last-action(autofocus)。
 */
function TestDialog({ open, onClose }: { open: boolean; onClose?: () => void }) {
  return (
    <BaseDialog isOpen={open} onClose={onClose ?? (() => {})} ariaLabel="Test dialog">
      <DialogHeader title="Title" onClose={onClose ?? (() => {})} />
      <button type="button">first-action</button>
      <button type="button" autoFocus>
        last-action
      </button>
    </BaseDialog>
  );
}

describe('BaseDialog A11Y (A11Y1)', () => {
  // 不自定义 afterEach 清 body：vitest afterEach 按 LIFO 执行，手动清空会先于
  // @testing-library 的 autoCleanup，导致 React 卸载 portal 时 removeChild 抛 DOMException。
  // 模块级 dialogStack 由 hook 的 unmount cleanup 自动 pop 清空，无需手动干预。

  it('places role=dialog/aria-modal on the dialog body, not the overlay', () => {
    render(<TestDialog open />);
    const dialog = screen.getByRole('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-label')).toBe('Test dialog');
    // 遮罩层不再承担 dialog 角色（role 已下沉到弹窗本体）
    expect(dialog.parentElement?.getAttribute('role')).toBeFalsy();
  });

  it('gives the close button an accessible name', () => {
    render(<TestDialog open />);
    const close = screen.getByRole('button', { name: '关闭' });
    expect(close.tagName).toBe('BUTTON');
  });

  it('moves focus into the dialog when opened, respecting [autofocus]', () => {
    render(<TestDialog open />);
    expect(document.activeElement?.textContent).toBe('last-action');
  });

  it('traps Tab focus: last focusable wraps to first', () => {
    render(<TestDialog open />);
    // 初始焦点在 last-action（autofocus），按 Tab 应回到首个可聚焦元素（close 按钮）
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Tab' });
    expect(document.activeElement?.getAttribute('aria-label')).toBe('关闭');
  });

  it('traps Shift+Tab focus: first focusable wraps to last', () => {
    render(<TestDialog open />);
    const close = screen.getByRole('button', { name: '关闭' });
    close.focus();
    fireEvent.keyDown(close, { key: 'Tab', shiftKey: true });
    expect(document.activeElement?.textContent).toBe('last-action');
  });

  it('restores focus to the trigger element when closed', () => {
    const { rerender } = render(
      <div>
        <button type="button" id="trigger">
          trigger
        </button>
        <TestDialog open={false} />
      </div>,
    );
    const trigger = document.getElementById('trigger') as HTMLButtonElement;

    // happy-dom 限制：createPortal 向 document.body 插入节点时会重置
    // document.activeElement 为 <body>，导致 useDialogFocus 在 render 阶段
    // 捕获不到真实的触发元素（真实浏览器不受此影响——focus() 可靠更新
    // activeElement，且 portal 的 DOM 操作不会重置焦点）。
    // 此处 mock getter 还原真实浏览器语义：dialog 打开瞬间 activeElement=trigger。
    const activeElementSpy = vi.spyOn(document, 'activeElement', 'get');
    activeElementSpy.mockReturnValue(trigger);

    const focusSpy = vi.spyOn(trigger, 'focus');
    focusSpy.mockClear();

    // 打开：render 阶段捕获 trigger 作为打开者
    rerender(
      <div>
        <button type="button" id="trigger">
          trigger
        </button>
        <TestDialog open />
      </div>,
    );

    // 关闭：归还焦点到触发元素（验证对 trigger 调用了 focus）
    rerender(
      <div>
        <button type="button" id="trigger">
          trigger
        </button>
        <TestDialog open={false} />
      </div>,
    );
    expect(focusSpy).toHaveBeenCalled();
    focusSpy.mockRestore();
    activeElementSpy.mockRestore();
  });

  it('marks the background inert while open and clears it on close', () => {
    const { container, rerender } = render(<TestDialog open />);
    expect(container.inert).toBe(true);

    rerender(<TestDialog open={false} />);
    expect(container.inert).toBe(false);
  });
});
