import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { Unsubscribe } from '../bridge/types';

const subscribeEventMock = vi.fn();
const sendActionMock = vi.fn();

vi.mock('../bridge/typed', () => ({
  subscribeEvent: (...args: unknown[]) => subscribeEventMock(...args),
  sendAction: (...args: unknown[]) => sendActionMock(...args),
}));

// generated/protocol 不 mock,直接用真实常量(UPSTREAM.GET_INVOCATION_MODE 等),
// 验证 hook 传给 bridge 的事件名正确。
import { UPSTREAM, DOWNSTREAM } from '../generated/protocol';
import { useCurrentInvocationMode } from './useCurrentInvocationMode';

describe('useCurrentInvocationMode', () => {
  let listener: ((payload: string) => void) | undefined;
  let unsub: Unsubscribe;

  beforeEach(() => {
    listener = undefined;
    unsub = vi.fn();
    subscribeEventMock.mockReset();
    sendActionMock.mockReset();
    subscribeEventMock.mockImplementation((_evt: unknown, l: (p: string) => void) => {
      listener = l;
      return unsub;
    });
  });

  it('subscribes to CONFIG_INVOCATION_MODE and pulls current mode on mount', () => {
    renderHook(() => useCurrentInvocationMode());
    expect(subscribeEventMock).toHaveBeenCalledWith(
      DOWNSTREAM.CONFIG_INVOCATION_MODE,
      expect.any(Function),
    );
    expect(sendActionMock).toHaveBeenCalledWith(UPSTREAM.GET_INVOCATION_MODE);
  });

  it('returns undefined initially then updates when backend pushes invocation mode', () => {
    const { result } = renderHook(() => useCurrentInvocationMode());
    expect(result.current).toBeUndefined();
    act(() => {
      listener?.('{"invocationMode":"cli"}');
    });
    expect(result.current).toBe('cli');
    act(() => {
      listener?.('{"invocationMode":"sdk"}');
    });
    expect(result.current).toBe('sdk');
  });

  it('ignores invalid invocationMode payloads (keeps last known value)', () => {
    const { result } = renderHook(() => useCurrentInvocationMode());
    act(() => {
      listener?.('{"invocationMode":"cli"}');
    });
    expect(result.current).toBe('cli');
    act(() => {
      listener?.('{"invocationMode":"bogus"}');
    });
    expect(result.current).toBe('cli');
    act(() => {
      listener?.('not json');
    });
    expect(result.current).toBe('cli');
  });

  it('unsubscribes on unmount', () => {
    const { unmount } = renderHook(() => useCurrentInvocationMode());
    unmount();
    expect(unsub).toHaveBeenCalled();
  });
});
