import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import BehaviorTab from './BehaviorTab';
import {
  MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS,
} from '../../../utils/permissionDialogTimeout';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

function renderBehaviorTab(overrides: Partial<ComponentProps<typeof BehaviorTab>> = {}) {
  const props = {
    streamingEnabled: true,
    onStreamingEnabledChange: vi.fn(),
    codexSandboxMode: 'workspace-write',
    onCodexSandboxModeChange: vi.fn(),
    sendShortcut: 'enter' as const,
    onSendShortcutChange: vi.fn(),
    autoOpenFileEnabled: false,
    onAutoOpenFileEnabledChange: vi.fn(),
    commitGenerationEnabled: true,
    onCommitGenerationEnabledChange: vi.fn(),
    aiTitleGenerationEnabled: true,
    onAiTitleGenerationEnabledChange: vi.fn(),
    taskCompletionNotificationEnabled: false,
    onTaskCompletionNotificationEnabledChange: vi.fn(),
    permissionDialogTimeoutSeconds: 300,
    onPermissionDialogTimeoutChange: vi.fn(),
    ...overrides,
  };

  render(<BehaviorTab {...props} />);
  return props;
}

describe('BehaviorTab permission dialog timeout', () => {
  it('exposes the timeout number input with an accessible label', () => {
    renderBehaviorTab();

    expect(
      screen.getByRole('spinbutton', { name: /settings.basic.permissionDialogTimeout.label/i }),
    ).toBeTruthy();
  });

  it('exposes native HTML5 min/max attributes that mirror the clamp constants', () => {
    // The native min/max attributes give browsers a chance to flag out-of-range values
    // before our onBlur/Enter clamp runs. They MUST stay in lockstep with the JS clamp,
    // otherwise the browser hint disagrees with what we accept on submission.
    renderBehaviorTab();

    const input = screen.getByRole('spinbutton', {
      name: /settings.basic.permissionDialogTimeout.label/i,
    });

    expect(input.getAttribute('min')).toBe(String(MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS));
    expect(input.getAttribute('max')).toBe(String(MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS));
  });

  it('clamps low values on blur', () => {
    const onPermissionDialogTimeoutChange = vi.fn();
    renderBehaviorTab({ onPermissionDialogTimeoutChange });

    const input = screen.getByRole('spinbutton', { name: /settings.basic.permissionDialogTimeout.label/i });
    fireEvent.change(input, { target: { value: '1' } });
    fireEvent.blur(input);

    expect(onPermissionDialogTimeoutChange).toHaveBeenCalledWith(30);
  });

  it('clamps high values on Enter', () => {
    const onPermissionDialogTimeoutChange = vi.fn();
    renderBehaviorTab({ onPermissionDialogTimeoutChange });

    const input = screen.getByRole('spinbutton', { name: /settings.basic.permissionDialogTimeout.label/i });
    fireEvent.change(input, { target: { value: '99999' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onPermissionDialogTimeoutChange).toHaveBeenCalledWith(3600);
  });
});

describe('BehaviorTab MCP gateway toggle', () => {
  it('renders checked by default and fires onMcpGatewayEnabledChange with the next value on click', () => {
    const onMcpGatewayEnabledChange = vi.fn();
    renderBehaviorTab({ onMcpGatewayEnabledChange });

    // fieldLabel span(fieldHeader 内)→ 上溯到 streamingSection(含 toggle 的 checkbox)。
    // toggle 的 label 包裹 input 但无显式 for/id 关联,getByLabelText 不可用,故走 DOM 遍历。
    const labelEl = screen.getByText('settings.basic.mcpGateway.label');
    const section = labelEl.parentElement?.parentElement;
    const checkbox = section?.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(checkbox).toBeTruthy();
    expect(checkbox.checked).toBe(true);

    fireEvent.click(checkbox);
    expect(onMcpGatewayEnabledChange).toHaveBeenCalledWith(false);
  });
});
