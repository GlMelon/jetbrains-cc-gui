import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CodexProviderDialog from '../../src/components/CodexProviderDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.name ?? key,
  }),
}));

describe('CodexProviderDialog', () => {
  it('renders a single card layer without a nested .dialog wrapper inside .dialog-base', () => {
    // 回归守护:与 ProviderDialog 对称,内层曾套 <div className="dialog provider-dialog ...">
    // 与 BaseDialog 的 .dialog-base 叠成双层卡片(双重背景/边框/阴影/圆角)。
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        onClose={vi.fn()}
        onSave={vi.fn()}
        addToast={vi.fn()}
      />,
    );
    // BaseDialog 经 portal 渲染到 document.body，须从 body 查询 .dialog-base
    const base = document.body.querySelector('.dialog-base');
    expect(base).toBeTruthy();

    const nestedDialogCard = Array.from(base!.children).find((el) =>
      el.classList.contains('dialog'),
    );
    expect(nestedDialogCard).toBeUndefined();
  });
});
