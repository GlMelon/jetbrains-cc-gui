import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DshProviderSection from '../../../../src/components/settings/DshProviderSection';
import { sendAction } from '../../../../src/bridge/typed';
import { UPSTREAM } from '../../../../src/generated/protocol';

vi.mock('../../../../src/bridge/typed', () => ({
  sendAction: vi.fn(() => true),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: string) => fallback ?? _key,
  }),
}));

describe('DshProviderSection operation lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(sendAction).mockClear();
  });

  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
    window.updateDshStatus = undefined;
  });

  it('correlates status responses and clears only the matching operation', () => {
    render(<DshProviderSection showHeader={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    const firstSaveCall = vi
      .mocked(sendAction)
      .mock.calls.find(([action]) => action === UPSTREAM.SAVE_DSH_SETTINGS);
    const firstOperationId = JSON.parse(String(firstSaveCall?.[1])).operationId as string;
    expect(firstOperationId).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);

    act(() => vi.advanceTimersByTime(35_000));
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(
      false,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    const saveCalls = vi
      .mocked(sendAction)
      .mock.calls.filter(([action]) => action === UPSTREAM.SAVE_DSH_SETTINGS);
    const secondOperationId = JSON.parse(String(saveCalls[1]?.[1])).operationId as string;
    expect(secondOperationId).not.toBe(firstOperationId);

    act(() => window.updateDshStatus?.({ installed: true, operationId: firstOperationId }));
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);

    act(() => window.updateDshStatus?.({ installed: true, operationId: secondOperationId }));
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(
      false,
    );
  });

  it('ends an operation as soon as its backend response arrives', () => {
    render(<DshProviderSection showHeader={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Start host' }));
    const startCall = vi
      .mocked(sendAction)
      .mock.calls.find(([action]) => action === UPSTREAM.START_DSH_HOST);
    const operationId = JSON.parse(String(startCall?.[1])).operationId as string;
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);

    act(() => window.updateDshStatus?.({ installed: true, hostRunning: true, operationId }));
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(
      false,
    );
    expect(vi.getTimerCount()).toBe(0);
  });

  it('cancels operation timers and ignores callbacks after unmount', () => {
    const { unmount } = render(<DshProviderSection showHeader={false} />);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(vi.getTimerCount()).toBe(1);

    const callback = window.updateDshStatus;
    unmount();
    expect(window.updateDshStatus).toBeUndefined();
    expect(vi.getTimerCount()).toBe(0);

    callback?.({ installed: true, operationId: '1' });
    act(() => vi.advanceTimersByTime(65_000));
  });
});
