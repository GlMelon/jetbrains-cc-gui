import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import ModelRegistrySection from './index';
import { __setModelRegistryForTests } from '../../../utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (_key: string, fallback?: string) => fallback ?? _key }),
}));

const mockAddToast = vi.fn();

describe('ModelRegistrySection', () => {
  beforeEach(() => {
    mockAddToast.mockClear();
  });

  it('只读行不渲染 Edit/Delete 按钮,改显锁标', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          contextWindow: 200000,
          readOnly: true,
          enabled: true,
        },
        {
          id: 'claude-role-opus',
          provider: 'claude',
          role: 'opus',
          label: 'Opus',
          contextWindow: 200000,
          readOnly: true,
          enabled: true,
        },
      ],
    });

    render(<ModelRegistrySection addToast={mockAddToast} />);

    // 只读行只有锁标,无 edit/trash
    expect(screen.queryAllByTitle('Edit')).toHaveLength(0);
    expect(screen.queryAllByTitle('Delete')).toHaveLength(0);
    expect(screen.getAllByTitle('Read-only').length).toBeGreaterThan(0);
  });

  it('可编辑行渲染 Edit/Delete 按钮', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo',
          provider: 'claude',
          role: 'sonnet',
          label: 'Mimo',
          contextWindow: 200000,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    render(<ModelRegistrySection addToast={mockAddToast} />);

    // getByTitle 找不到会抛错;能取到即说明按钮存在
    expect(screen.getByTitle('Edit')).toBeTruthy();
    expect(screen.getByTitle('Delete')).toBeTruthy();
  });
});
