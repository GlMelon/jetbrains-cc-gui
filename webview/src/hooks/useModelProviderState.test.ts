import { act, cleanup, renderHook } from '@testing-library/react';
import type { TFunction } from 'i18next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelProviderState } from './useModelProviderState';
import { sendAction } from '../bridge/typed';
import { bridgeHub } from '../bridge';
import { UPSTREAM } from '../generated/protocol';
import { resetModelRegistryForTests } from '../utils/modelRegistry';

vi.mock('../bridge/typed', async () => {
  const actual = await vi.importActual<typeof import('../bridge/typed')>('../bridge/typed');
  return { ...actual, sendAction: vi.fn() };
});

const options = { addToast: vi.fn(), t: ((key: string) => key) as TFunction };

// Native auto approval ('auto' mode) availability is a static provider set
// (claude/codex/grok), mirroring the backend degrade rule in
// SessionSendService.resolveEffectivePermissionMode. There is no SDK-version
// signal in CLI mode — the frontend passes the selected mode through and the
// backend downgrades unsupported providers to default.
describe('native auto approval mode (static provider availability)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    bridgeHub.reset();
    bridgeHub.markReady();
    resetModelRegistryForTests();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it('passes auto through unchanged for codex', () => {
    const { result } = renderHook(() => useModelProviderState(options));
    act(() => result.current.handleProviderSelect('codex'));
    act(() => result.current.handleModeSelect('auto'));
    expect(result.current.permissionMode).toBe('auto');
    expect(result.current.codexPermissionMode).toBe('auto');
    expect(vi.mocked(sendAction)).toHaveBeenLastCalledWith(UPSTREAM.SET_SESSION_MODE, 'auto');
  });

  it('coerces auto to default for headless CLI providers without a native reviewer', () => {
    const { result } = renderHook(() => useModelProviderState(options));
    act(() => result.current.handleProviderSelect('kimi'));
    act(() => result.current.handleModeSelect('auto'));
    expect(result.current.permissionMode).toBe('default');
    expect(result.current.kimiPermissionMode).toBe('default');
    expect(vi.mocked(sendAction)).toHaveBeenLastCalledWith(UPSTREAM.SET_SESSION_MODE, 'default');
  });

  it('preserves auto for grok (native auto-approve alias)', () => {
    const { result } = renderHook(() => useModelProviderState(options));
    act(() => result.current.handleProviderSelect('grok'));
    act(() => result.current.handleModeSelect('auto'));
    expect(result.current.permissionMode).toBe('auto');
    expect(result.current.grokPermissionMode).toBe('auto');
    expect(vi.mocked(sendAction)).toHaveBeenLastCalledWith(UPSTREAM.SET_SESSION_MODE, 'auto');
  });

  it('restores a saved grok auto mode across provider switches', () => {
    const { result } = renderHook(() => useModelProviderState(options));
    act(() => result.current.handleProviderSelect('grok'));
    act(() => result.current.handleModeSelect('auto'));
    // Switch away and back: the saved grok mode must survive normalization.
    act(() => result.current.handleProviderSelect('kimi'));
    act(() => result.current.handleProviderSelect('grok'));
    expect(result.current.permissionMode).toBe('auto');
    // handleProviderSelect 还会跟进发送 set_session_model,mode 调用未必是最后一次。
    expect(vi.mocked(sendAction).mock.calls).toContainEqual([UPSTREAM.SET_SESSION_MODE, 'auto']);
  });
});
