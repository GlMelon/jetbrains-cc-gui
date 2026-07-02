import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ConfigSelect } from './ConfigSelect';

vi.mock('../providers/agentProvider', () => ({
  CREATE_NEW_AGENT_ID: '__create__',
  EMPTY_STATE_ID: '__empty__',
  agentProvider: vi.fn(async () => []),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'common.thinking': 'Thinking',
        'common.thinkingDisabledHint': 'thinking-disabled-hint',
        'settings.basic.streaming.label': 'Streaming',
        'settings.configure': 'Configure',
      } as Record<string, string>)[key] ?? key,
  }),
}));

vi.mock('../../../hooks/useCurrentInvocationMode', () => ({
  useCurrentInvocationMode: vi.fn(() => undefined),
}));

import { useCurrentInvocationMode } from '../../../hooks/useCurrentInvocationMode';

const mockedUseInvocationMode = vi.mocked(useCurrentInvocationMode);

/** 打开 Configure 菜单,定位「思考」开关(shared/Switch 渲染为 button[role=switch])。 */
const openThinkingSwitch = async (): Promise<HTMLElement> => {
  fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
  const thinkingOption = screen.getByText('Thinking').closest('.selector-option');
  if (!thinkingOption) throw new Error('thinking option not found');
  return within(thinkingOption).getByRole('switch');
};

describe('ConfigSelect thinking switch — OpenCode CLI grey-out', () => {
  beforeEach(() => {
    mockedUseInvocationMode.mockReset();
    mockedUseInvocationMode.mockReturnValue(undefined);
    window.sendToJava = vi.fn();
  });

  it('disables thinking switch when provider=opencode and invocationMode=cli', async () => {
    mockedUseInvocationMode.mockReturnValue('cli');
    render(<ConfigSelect currentProvider="opencode" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(true);
    // 灰显时 option 容器带 title 提示
    const option = thinkingSwitch.closest('.selector-option');
    expect(option?.getAttribute('title')).toBe('thinking-disabled-hint');
  });

  it('keeps thinking switch enabled when provider=opencode and invocationMode=sdk', async () => {
    mockedUseInvocationMode.mockReturnValue('sdk');
    render(<ConfigSelect currentProvider="opencode" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });

  it('keeps thinking switch enabled when provider=codex and invocationMode=cli (Codex CLI emits thinking)', async () => {
    mockedUseInvocationMode.mockReturnValue('cli');
    render(<ConfigSelect currentProvider="codex" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });

  it('keeps thinking switch enabled when provider=claude and invocationMode=cli (Claude CLI emits thinking)', async () => {
    mockedUseInvocationMode.mockReturnValue('cli');
    render(<ConfigSelect currentProvider="claude" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });

  it('keeps thinking switch enabled when invocation mode is still unknown', async () => {
    mockedUseInvocationMode.mockReturnValue(undefined);
    render(<ConfigSelect currentProvider="opencode" />);
    const thinkingSwitch = await openThinkingSwitch();
    expect(thinkingSwitch.hasAttribute('disabled')).toBe(false);
  });
});
