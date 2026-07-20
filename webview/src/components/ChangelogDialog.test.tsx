import { fireEvent, render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import ChangelogDialog from './ChangelogDialog';
import type { ChangelogEntry } from '../version/changelog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, opts?: unknown) => {
      if (opts && typeof opts === 'object') return `${k}:${JSON.stringify(opts)}`;
      return k;
    },
  }),
}));

const makeEntry = (overrides: Partial<ChangelogEntry> = {}): ChangelogEntry => ({
  version: '1.2.3',
  date: '2026-07-01',
  content: {
    en: `✨ Features
- Feature A
- Feature B
🐛 Fixes
- Fix C`,
    zh: `✨ 新功能
- 新功能 A`,
  },
  ...overrides,
});

describe('ChangelogDialog (Hero 重做)', () => {
  it('isOpen=false → 不渲染', () => {
    const { container } = render(
      <ChangelogDialog isOpen={false} onClose={vi.fn()} entries={[makeEntry()]} />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('Hero 区显示版本号(v1.2.3)', () => {
    render(<ChangelogDialog isOpen onClose={vi.fn()} entries={[makeEntry()]} />);
    expect(screen.getByText(/1\.2\.3/)).toBeTruthy();
  });

  it('Hero stats 胶囊按 kind 渲染(en:feature=2, fix=1)', () => {
    render(<ChangelogDialog isOpen onClose={vi.fn()} entries={[makeEntry()]} />);
    // BaseDialog 经 portal 渲染到 document.body，不在 render container 内，须从 dialog 角色节点查询
    const dialog = screen.getByRole('dialog');
    const stats = dialog.querySelectorAll('.wn-b-stat');
    const kinds = Array.from(stats).map((s) => s.getAttribute('data-kind'));
    expect(kinds).toContain('feature');
    expect(kinds).toContain('fix');
    const featureStat = dialog.querySelector('.wn-b-stat[data-kind="feature"]');
    expect(featureStat?.textContent).toMatch(/2/);
  });

  it('分区列表渲染(.wn-b-grp 含 section items)', () => {
    render(<ChangelogDialog isOpen onClose={vi.fn()} entries={[makeEntry()]} />);
    const dialog = screen.getByRole('dialog');
    const groups = dialog.querySelectorAll('.wn-b-grp');
    expect(groups.length).toBeGreaterThanOrEqual(1);
    // item 经 formatInline 渲染(纯文本可见)
    expect(dialog.textContent).toContain('Feature A');
    expect(dialog.textContent).toContain('Fix C');
  });

  it('多语言:en + zh 都渲染(en 的 Feature A 与 zh 的 新功能 A 同时可见)', () => {
    render(<ChangelogDialog isOpen onClose={vi.fn()} entries={[makeEntry()]} />);
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('Feature A');
    expect(dialog.textContent).toContain('新功能 A');
  });

  it('Got it 按钮(t(changelog.close))→ 调用 onClose', () => {
    const onClose = vi.fn();
    render(<ChangelogDialog isOpen onClose={onClose} entries={[makeEntry()]} />);
    fireEvent.click(screen.getByText('changelog.close'));
    expect(onClose).toHaveBeenCalled();
  });

  it('翻页:多 entry → 点 Next 切换到第二个版本', () => {
    const entries = [makeEntry({ version: '1.0.0' }), makeEntry({ version: '2.0.0' })];
    render(<ChangelogDialog isOpen onClose={vi.fn()} entries={entries} />);
    expect(screen.getByText(/1\.0\.0/)).toBeTruthy();
    fireEvent.click(screen.getByLabelText('Next version'));
    expect(screen.getByText(/2\.0\.0/)).toBeTruthy();
  });

  it('空 entries → 不渲染(防护)', () => {
    const { container } = render(
      <ChangelogDialog isOpen onClose={vi.fn()} entries={[]} />,
    );
    expect(container.firstChild).toBeNull();
  });
});
