import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  cancelScopedPendingUpdate,
  clearStreamScopeState,
  consumeScopedPendingUpdate,
  getActiveStreamScopeKey,
  getOrCreateStreamScopeState,
  getStreamScopeKey,
  getStreamScopeState,
  queueScopedPendingUpdate,
  setActiveStreamScopeKey,
} from '../../../../src/hooks/windowCallbacks/streamScopeState';

describe('streamScopeState', () => {
  beforeEach(() => {
    window.__activeStreamScopeKey = null;
    clearStreamScopeState('claude:session-a:1');
    clearStreamScopeState('claude:session-b:1');
  });

  it('keeps concurrent stream buffers isolated by scope key', () => {
    const scopeA = getStreamScopeKey('claude', 'session-a', 1);
    const scopeB = getStreamScopeKey('claude', 'session-b', 1);

    getOrCreateStreamScopeState(scopeA).content = 'alpha';
    getOrCreateStreamScopeState(scopeB).content = 'bravo';

    expect(getStreamScopeState(scopeA)?.content).toBe('alpha');
    expect(getStreamScopeState(scopeB)?.content).toBe('bravo');
  });

  it('stores pending updateMessages per scope', () => {
    const scopeA = getStreamScopeKey('claude', 'session-a', 1);
    const scopeB = getStreamScopeKey('claude', 'session-b', 1);
    getOrCreateStreamScopeState(scopeA);
    getOrCreateStreamScopeState(scopeB);

    queueScopedPendingUpdate(scopeA, '[{"type":"assistant","content":"old"}]', 1);
    queueScopedPendingUpdate(scopeB, '[{"type":"assistant","content":"current"}]', 2);

    expect(consumeScopedPendingUpdate(scopeB)).toEqual({
      json: '[{"type":"assistant","content":"current"}]',
      sequence: 2,
    });
    expect(consumeScopedPendingUpdate(scopeA)).toEqual({
      json: '[{"type":"assistant","content":"old"}]',
      sequence: 1,
    });
  });

  it('clears only the requested stream scope and preserves the active current scope', () => {
    const scopeA = getStreamScopeKey('claude', 'session-a', 1);
    const scopeB = getStreamScopeKey('claude', 'session-b', 1);
    getOrCreateStreamScopeState(scopeA).content = 'old';
    getOrCreateStreamScopeState(scopeB).content = 'current';
    setActiveStreamScopeKey(scopeB);

    clearStreamScopeState(scopeA);

    expect(getStreamScopeState(scopeA)).toBeNull();
    expect(getStreamScopeState(scopeB)?.content).toBe('current');
    expect(getActiveStreamScopeKey()).toBe(scopeB);
  });

  it('cancels pending timer only for the target scope', () => {
    const clearTimeoutSpy = vi.spyOn(window, 'clearTimeout').mockImplementation(() => undefined);
    const scopeA = getStreamScopeKey('claude', 'session-a', 1);
    const scopeB = getStreamScopeKey('claude', 'session-b', 1);
    getOrCreateStreamScopeState(scopeA).pendingUpdateTimer = 101;
    getOrCreateStreamScopeState(scopeB).pendingUpdateTimer = 202;

    cancelScopedPendingUpdate(scopeA);

    expect(clearTimeoutSpy).toHaveBeenCalledWith(101);
    expect(getStreamScopeState(scopeA)?.pendingUpdateTimer).toBeNull();
    expect(getStreamScopeState(scopeB)?.pendingUpdateTimer).toBe(202);
  });
});
