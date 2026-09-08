import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReasoningSelect } from '../../../../src/components/ChatInputBox/selectors/ReasoningSelect';
import { CLAUDE_ROLE_MODEL_IDS } from '../../../../src/components/ChatInputBox/types';
import { __setModelRegistryForTests, resetModelRegistryForTests } from '../../../../src/utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? _key,
  }),
}));

describe('ReasoningSelect', () => {
  // A2:可选级别来自后端权威下发的 supportedReasoningLevels(派生自 ClaudeRole.reasoningLevels)。
  // 测试需预设 registry 含 supportedReasoningLevels,模拟后端 serialize 下发。
  function registryBaseItems() {
    return [
      { id: 'claude-role-sonnet', provider: 'claude' as const, role: 'sonnet' as const, label: 'Sonnet', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
      { id: 'claude-role-opus', provider: 'claude' as const, role: 'opus' as const, label: 'Opus', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
      { id: 'claude-role-fable', provider: 'claude' as const, role: 'fable' as const, label: 'Fable', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
      { id: 'claude-role-haiku', provider: 'claude' as const, role: 'haiku' as const, label: 'Haiku', contextWindow: 200_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high'] },
    ];
  }
  beforeEach(() => {
    resetModelRegistryForTests();
    __setModelRegistryForTests({ items: registryBaseItems() });
  });

  it('shows xhigh and max for Claude Opus role', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel={CLAUDE_ROLE_MODEL_IDS.opus}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('XHigh')).toBeTruthy();
    expect(screen.getByText('Max')).toBeTruthy();
  });

  // A2:后端 ClaudeRole.reasoningLevels 定义 sonnet 支持 5 档(含 xhigh),
  // 取代原前端硬编码"sonnet 无 xhigh"。
  it('shows xhigh and max for Claude Sonnet role (backend-authoritative 5 levels)', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel={CLAUDE_ROLE_MODEL_IDS.sonnet}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('XHigh')).toBeTruthy();
    expect(screen.getByText('Max')).toBeTruthy();
  });

  // A2:后端定义 haiku 支持 3 档(low/medium/high),原前端硬编码"haiku 隐藏"已废弃。
  it('shows limited levels (no xhigh/max) for Claude Haiku role', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel={CLAUDE_ROLE_MODEL_IDS.haiku}
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    // haiku 仅 low/medium/high:xhigh/max 不渲染。
    expect(screen.queryByText('XHigh')).toBeNull();
    expect(screen.queryByText('Max')).toBeNull();
  });

  it('resets unavailable effort when selected Claude model does not support it', () => {
    // haiku 仅支持 low/medium/high;value=xhigh → currentLevel 回落到 availableLevels[length-2](medium)。
    const onChange = vi.fn();

    render(
      <ReasoningSelect
        value="xhigh"
        onChange={onChange}
        currentProvider="claude"
        selectedModel={CLAUDE_ROLE_MODEL_IDS.haiku}
      />,
    );

    expect(onChange).toHaveBeenCalledWith('medium');
  });

  // A2→7 档改造:自定义 Claude 模型在 registry 中但未配 role(后端不下发
  // supportedReasoningLevels)→ 已知无 reasoning 能力,选择器隐藏。
  it('hides for registry custom Claude models without a role', () => {
    __setModelRegistryForTests({
      items: [
        { id: 'custom-no-role-model', provider: 'claude', label: 'Custom', contextWindow: 200_000, supports1MContext: false, readOnly: false, enabled: true },
        ...registryBaseItems(),
      ],
    });
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel="custom-no-role-model"
      />,
    );

    expect(screen.queryByRole('button')).toBeNull();
  });

  // 三态契约:模型不在 registry 且 provider 无默认档位 → 档位未知,未知不等于无权
  // (registry 未加载 / 后端未收录),展示全集且 guard 不改写持久化值。
  it('shows full levels for Claude models missing from the registry (unknown ≠ no right)', () => {
    render(
      <ReasoningSelect
        value="high"
        onChange={vi.fn()}
        currentProvider="claude"
        selectedModel="custom-no-role-model"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('XHigh')).toBeTruthy();
    expect(screen.getByText('Max')).toBeTruthy();
  });

  // 2026-09 思考强度回退回归:mount 时 registry 未下发(空),持久化恢复的 max 处于
  // 豁免窗口(isVisible=false);registry 到达后 isVisible 翻 true,guard 不得因 stale
  // 档位列表(缓存自 registry 为空的渲染)把合法 max 改写成兜底 medium。
  it('keeps valid max effort when model registry arrives after mount', () => {
    resetModelRegistryForTests();
    const onChange = vi.fn();
    const props = {
      value: 'max' as const,
      onChange,
      currentProvider: 'claude' as const,
      selectedModel: 'glm-5.3-flash',
    };
    const { rerender } = render(<ReasoningSelect {...props} />);
    // registry 为空:选择器隐藏,guard 豁免,不得改写。
    expect(onChange).not.toHaveBeenCalled();

    act(() => {
      __setModelRegistryForTests({
        items: [
          { id: 'glm-5.3-flash', provider: 'claude', role: 'sonnet', label: 'glm-5.3-flash', contextWindow: 1_000_000, supports1MContext: true, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
        ],
      });
    });
    rerender(<ReasoningSelect {...props} />);

    expect(onChange).not.toHaveBeenCalled();
  });

  // 2026-09 思考强度回退残留窗口:registry 未加载(levels===null → 兜底 3 档)且会话降级
  // (sessionThinkingAvailable=false)时,isVisible=false 豁免不成立,guard 不得拿兜底档位
  // 把合法 max 改写成 medium 并被立即持久化;registry 到达后档位权威,max 合法,仍不改写。
  it('keeps valid max effort when session is degraded and model registry has not loaded', () => {
    resetModelRegistryForTests();
    const onChange = vi.fn();
    const props = {
      value: 'max' as const,
      onChange,
      currentProvider: 'claude' as const,
      selectedModel: 'glm-5.3-flash',
      sessionThinkingAvailable: false,
    };
    const { rerender } = render(<ReasoningSelect {...props} />);
    // registry 为空(档位未知):guard 一律不改写。
    expect(onChange).not.toHaveBeenCalled();

    act(() => {
      __setModelRegistryForTests({
        items: [
          { id: 'glm-5.3-flash', provider: 'claude', role: 'sonnet', label: 'glm-5.3-flash', contextWindow: 1_000_000, supports1MContext: true, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
        ],
      });
    });
    rerender(<ReasoningSelect {...props} />);

    // registry 到达后 max 是合法档位,仍不得改写。
    expect(onChange).not.toHaveBeenCalled();
  });

  // 对照:registry 已加载且模型档位权威(haiku 真 3 档)时,降级会话下 value=max
  // 超出权威档位范围,有意的钳制仍须生效(钳到 availableLevels[length-2]=medium),
  // 确认「档位未知不改写」没有误伤权威档位的钳制逻辑。
  it('still clamps max to medium on a degraded session when registry says haiku has 3 levels', () => {
    const onChange = vi.fn();

    render(
      <ReasoningSelect
        value="max"
        onChange={onChange}
        currentProvider="claude"
        selectedModel={CLAUDE_ROLE_MODEL_IDS.haiku}
        sessionThinkingAvailable={false}
      />,
    );

    expect(onChange).toHaveBeenCalledWith('medium');
  });
});
