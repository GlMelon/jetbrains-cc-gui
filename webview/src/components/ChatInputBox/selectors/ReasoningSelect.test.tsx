import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReasoningSelect } from './ReasoningSelect';
import { CLAUDE_ROLE_MODEL_IDS } from '../types';
import { __setModelRegistryForTests, resetModelRegistryForTests } from '../../../utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? _key,
  }),
}));

describe('ReasoningSelect', () => {
  // A2:可选级别来自后端权威下发的 supportedReasoningLevels(派生自 ClaudeRole.reasoningLevels)。
  // 测试需预设 registry 含 supportedReasoningLevels,模拟后端 serialize 下发。
  beforeEach(() => {
    resetModelRegistryForTests();
    __setModelRegistryForTests({
      items: [
        { id: 'claude-role-sonnet', provider: 'claude', role: 'sonnet', label: 'Sonnet', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
        { id: 'claude-role-opus', provider: 'claude', role: 'opus', label: 'Opus', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
        { id: 'claude-role-fable', provider: 'claude', role: 'fable', label: 'Fable', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high', 'xhigh', 'max'] },
        { id: 'claude-role-haiku', provider: 'claude', role: 'haiku', label: 'Haiku', contextWindow: 200_000, supports1MContext: false, readOnly: false, enabled: true, supportedReasoningLevels: ['low', 'medium', 'high'] },
      ],
    });
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

  // A2:未配置 role 的自定义 Claude 模型后端不下发 supportedReasoningLevels → 隐藏。
  it('hides for Claude custom models without reasoning capability', () => {
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
});
