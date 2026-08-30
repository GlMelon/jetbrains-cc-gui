import { renderHook } from '@testing-library/react';
import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import { useThemeInit } from './useThemeInit';
import { BOOTSTRAP_SCOPE, bootstrapLifecycle } from '../utils/bootstrapLifecycle';

vi.mock('../bridge/typed', () => ({
  sendAction: vi.fn(),
  subscribeEvent: vi.fn(() => vi.fn()),
}));

vi.mock('../bridge', () => ({
  registerLegacyAlias: vi.fn(),
}));

describe('useThemeInit bootstrap lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(sendAction).mockClear();
    localStorage.clear();
    delete window.sendToJava;
    bootstrapLifecycle.cancel(BOOTSTRAP_SCOPE.IDE_THEME);
  });

  afterEach(() => {
    bootstrapLifecycle.cancel(BOOTSTRAP_SCOPE.IDE_THEME);
    delete window.sendToJava;
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('cancels the pending theme retry when the hook unmounts', () => {
    const { unmount } = renderHook(() => useThemeInit());

    expect(bootstrapLifecycle.isActive(BOOTSTRAP_SCOPE.IDE_THEME)).toBe(true);
    unmount();
    window.sendToJava = vi.fn();
    vi.runOnlyPendingTimers();

    expect(bootstrapLifecycle.isActive(BOOTSTRAP_SCOPE.IDE_THEME)).toBe(false);
    expect(sendAction).not.toHaveBeenCalled();
  });

  it('finishes the theme retry after the bridge becomes available', () => {
    window.sendToJava = vi.fn();
    const { unmount } = renderHook(() => useThemeInit());

    vi.advanceTimersByTime(100);

    expect(sendAction).toHaveBeenCalledWith(UPSTREAM.GET_IDE_THEME);
    expect(bootstrapLifecycle.isActive(BOOTSTRAP_SCOPE.IDE_THEME)).toBe(false);
    unmount();
  });
});