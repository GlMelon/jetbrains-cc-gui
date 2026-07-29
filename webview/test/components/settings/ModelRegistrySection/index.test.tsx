import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ModelRegistrySection from '../../../../src/components/settings/ModelRegistrySection/index';
import { __setModelRegistryForTests } from '../../../../src/utils/modelRegistry';
import { sendAction } from '../../../../src/bridge/typed';
import { UPSTREAM } from '../../../../src/generated/protocol';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (_key: string, fallback?: string) => fallback ?? _key }),
}));

vi.mock('../../../bridge/typed', () => ({
  sendAction: vi.fn(),
  subscribeEvent: vi.fn(() => () => {}),
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
          supports1MContext: false,
        },
        {
          id: 'claude-role-opus',
          provider: 'claude',
          role: 'opus',
          label: 'Opus',
          contextWindow: 200000,
          readOnly: true,
          enabled: true,
          supports1MContext: false,
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
          supports1MContext: false,
        },
      ],
    });

    render(<ModelRegistrySection addToast={mockAddToast} />);

    // getByTitle 找不到会抛错;能取到即说明按钮存在
    expect(screen.getByTitle('Edit')).toBeTruthy();
    expect(screen.getByTitle('Delete')).toBeTruthy();
  });

  it('persist 时剥离只读项,仅发送用户自定义层', () => {
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
          supports1MContext: false,
        },
        {
          id: 'mimo',
          provider: 'claude',
          role: 'sonnet',
          label: 'Mimo',
          contextWindow: 200000,
          readOnly: false,
          enabled: true,
          supports1MContext: false,
        },
      ],
    });
    vi.mocked(sendAction).mockClear();

    render(<ModelRegistrySection addToast={mockAddToast} />);

    // 删除唯一的可编辑行(mimo)→ 触发 persistRegistry
    fireEvent.click(screen.getByTitle('Delete'));

    // 定位 set_model_registry 调用(跳过 useEffect 先发的 get_model_registry)
    const setCall = vi.mocked(sendAction).mock.calls.find(
      (call) => call[0] === UPSTREAM.SET_MODEL_REGISTRY,
    );
    expect(setCall).toBeDefined();
    // 只读项 claude-role-sonnet 必须被剥离;mimo 已被删除 → 用户层为空
    expect(setCall![1]).toEqual({ items: [] });
  });
});
