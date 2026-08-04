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

describe('ConfigSelect thinking switch — available across all providers', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  // OpenCode CLI 现带 --thinking flag(opencode run --format json 输出 type:"reasoning" 文本事件,
  // parser EVENT_REASONING 分支消费),思考区开关对所有 provider 均可用。早期 opencode+cli 灰显已移除。
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
});
