// @vitest-environment happy-dom

import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ConfigSelect } from '../../../../src/components/ChatInputBox/selectors/ConfigSelect';

vi.mock('../../../../src/components/ChatInputBox/providers/agentProvider', () => ({
  CREATE_NEW_AGENT_ID: '__create__',
  EMPTY_STATE_ID: '__empty__',
  agentProvider: vi.fn(async () => []),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'common.thinking': 'Thinking',
        'settings.basic.streaming.label': 'Streaming',
        'settings.configure': 'Configure',
      } as Record<string, string>)[key] ?? key,
  }),
}));

/** 打开 Configure 菜单,定位「思考」开关(shared/Switch 渲染为 button[role=switch])。 */
const openThinkingSwitch = async (): Promise<HTMLElement> => {
  fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
  const thinkingOption = screen.getByText('Thinking').closest('.selector-option') as HTMLElement | null;
  if (!thinkingOption) throw new Error('thinking option not found');
  return within(thinkingOption).getByRole('switch');
};

describe('ConfigSelect thinking switch — enabled for all CLI providers (incl. kimi ACP channel)', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  // OpenCode CLI 现带 --thinking flag(opencode run --format json 输出 type:"reasoning" 文本事件,
  // parser EVENT_REASONING 分支消费),思考区开关对多数 provider 可用。早期 opencode+cli 灰显已移除。
  it('keeps thinking switch enabled for provider=opencode (CLI now emits thinking via --thinking)', async () => {
    render(<ConfigSelect currentProvider="opencode" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });

  it('keeps thinking switch enabled for provider=codex', async () => {
    render(<ConfigSelect currentProvider="codex" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });

  it('keeps thinking switch enabled for provider=claude', async () => {
    render(<ConfigSelect currentProvider="claude" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });

  // Kimi 经 ACP 通道(kimi acp)透出 agent_thought_chunk 一等公民(2026-08-27 实测 0.38.0 确认),
  // 思考开关放开;门禁不满足(版本/未登录)时自动回退 legacy stream-json(无思考区,但开关不再硬禁用)。
  it('keeps thinking switch enabled for provider=kimi (ACP channel emits agent_thought_chunk)', async () => {
    render(<ConfigSelect currentProvider="kimi" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });
});
