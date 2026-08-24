import { fireEvent, render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { SessionCapabilitiesDrawer } from '../../src/components/SessionCapabilitiesDrawer';
import type { SessionCapabilities } from '../../src/hooks/useSessionCapabilities';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, options?: { defaultValue?: string; count?: number; time?: string }) => {
      let value = options?.defaultValue ?? _key;
      if (typeof options?.count === 'number')
        value = value.replace('{{count}}', String(options.count));
      if (options?.time) value = value.replace('{{time}}', options.time);
      return value;
    },
  }),
}));

const snapshot: SessionCapabilities = {
  sessionId: 'session-1',
  runtimeEpoch: 'epoch-1',
  provider: 'codex',
  observedAt: 1_756_000_000_000,
  mcpAvailable: true,
  mcpError: null,
  mcp: [
    {
      id: 'codex:filesystem',
      name: 'filesystem',
      provider: 'codex',
      state: 'ready',
      lastError: null,
      lastSuccessAt: null,
      failureCount: 0,
      observed: true,
    },
  ],
  skills: [
    {
      id: 'playwright',
      name: 'playwright',
      scope: 'workspace',
      state: 'loaded',
      observed: true,
      source: 'workspace',
    },
  ],
};

describe('SessionCapabilitiesDrawer', () => {
  const triggerRef = createRef<HTMLButtonElement>();

  it('renders MCP and skill capabilities from the backend snapshot', () => {
    render(
      <SessionCapabilitiesDrawer
        open
        data={snapshot}
        loading={false}
        error={false}
        triggerRef={triggerRef}
        onClose={() => undefined}
        onRefresh={() => undefined}
      />,
    );

    expect(screen.getByText('2 capabilities visible to this session')).toBeTruthy();
    expect(screen.getByText('filesystem')).toBeTruthy();
    expect(screen.getByText('playwright')).toBeTruthy();
    expect(screen.getByText('ready')).toBeTruthy();
    expect(screen.getByText('loaded')).toBeTruthy();
  });

  it('supports refresh, backdrop close, and Escape close', () => {
    const onClose = vi.fn();
    const onRefresh = vi.fn();
    render(
      <SessionCapabilitiesDrawer
        open
        data={snapshot}
        loading={false}
        error={false}
        triggerRef={triggerRef}
        onClose={onClose}
        onRefresh={onRefresh}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(onRefresh).toHaveBeenCalledOnce();

    fireEvent.click(screen.getByRole('button', { name: 'Close capabilities' }));
    expect(onClose).toHaveBeenCalledOnce();

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('does not render when closed', () => {
    const { container } = render(
      <SessionCapabilitiesDrawer
        open={false}
        data={snapshot}
        loading={false}
        error={false}
        triggerRef={triggerRef}
        onClose={() => undefined}
        onRefresh={() => undefined}
      />,
    );
    expect(container.innerHTML).toBe('');
  });
});
