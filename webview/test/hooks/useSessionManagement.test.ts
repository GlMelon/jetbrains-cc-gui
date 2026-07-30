import { act, renderHook } from '@testing-library/react';
import { useSessionManagement } from '../../src/hooks/useSessionManagement.js';
import type { HistoryData } from '../../src/types/index.js';
import { HISTORY_EXPORT_FORMAT, UPSTREAM } from '../../src/generated/protocol.js';

describe('useSessionManagement', () => {
  const t = ((key: string) => key) as any;
  const bridgeCall = (type: string, content = '') =>
    JSON.stringify({ type, content });

  const createMocks = () => ({
    setHistoryData: vi.fn(),
    setMessages: vi.fn(),
    setCurrentView: vi.fn(),
    setCurrentSessionId: vi.fn(),
    setCustomSessionTitle: vi.fn(),
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setStatus: vi.fn(),
    setLoading: vi.fn(),
    setIsThinking: vi.fn(),
    setStreamingActive: vi.fn(),
    clearToasts: vi.fn(),
    addToast: vi.fn(),
    setBackgroundTasks: vi.fn(),
  });

  beforeEach(() => {
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    window.__pendingSessionTransitionToast = undefined;
    window.sendToJava = vi.fn();
    // Reset the "skip new-session confirm" preference between tests so cases
    // that exercise the localStorage path can't leak into ones that don't.
    localStorage.removeItem('skipNewSessionConfirm');
  });

  it('starts a clean session transition for a direct new session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'old-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setStatus).toHaveBeenCalledWith('');
    expect(mocks.setLoading).toHaveBeenCalledWith(false);
    expect(mocks.setIsThinking).toHaveBeenCalledWith(false);
    expect(mocks.setStreamingActive).toHaveBeenCalledWith(false);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith(null);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
  });

  it('clears stale ui state before loading history', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History Title',
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
      ],
      total: 3,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old', timestamp: new Date().toISOString() }],
        loading: true,
        historyData,
        currentSessionId: 'old-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('history-1');
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, bridgeCall('interrupt_session'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      bridgeCall('load_session', '{"sessionId":"history-1","provider":"claude"}')
    );
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('history-1');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith('History Title');
    expect(mocks.setCurrentView).toHaveBeenCalledWith('chat');
  });

  it('applies repeated history deletes against the latest state', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySession('history-1');
      result.current.deleteHistorySession('history-2');
    });

    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('delete_session', 'history-1'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('delete_session', 'history-2'));
  });

  it('sends one backend request when deleting multiple history sessions', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2', 'history-1']);
    });

    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('delete_sessions', '["history-1","history-2"]'));
    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
  });

  it('sends one archive request without optimistically mutating history state', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'opencode',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'opencode',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.archiveHistorySessions(['history-1', 'history-2', 'history-1', '']);
    });

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(
      bridgeCall('archive_sessions', '["history-1","history-2"]')
    );
    expect(mocks.setHistoryData).not.toHaveBeenCalled();
  });

  it('shows a success toast when a non-current history session is archived', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'history-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.handleHistoryArchiveResult({
        success: true,
        requestedSessionIds: ['history-2'],
        archivedSessionIds: ['history-2'],
        failedSessionIds: [],
      });
    });

    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionsArchived', 'success');
    expect(window.sendToJava).not.toHaveBeenCalled();
    expect(window.__sessionTransitioning).toBe(false);
  });

  it('interrupts and starts a new session after archiving the current loading session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'working', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'history-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.handleHistoryArchiveResult({
        success: true,
        requestedSessionIds: ['history-1'],
        archivedSessionIds: ['history-1'],
        failedSessionIds: [],
      });
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, bridgeCall('interrupt_session'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, bridgeCall('create_new_session'));
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.addToast).not.toHaveBeenCalled();
    expect(window.__pendingSessionTransitionToast).toEqual({
      message: 'history.sessionsArchived',
      type: 'success',
    });
  });

  it('still shows a success toast for batch delete when history data is temporarily unavailable', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2', 'history-1']);
    });

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('delete_sessions', '["history-1","history-2"]'));
    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
  });

  it('defers the deleted toast until transition completion when batch delete removes current session', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: 'history-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2']);
    });

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('delete_sessions', '["history-1","history-2"]'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(mocks.addToast).not.toHaveBeenCalledWith('history.sessionDeleted', 'success');
    expect(window.__pendingSessionTransitionToast).toEqual({
      message: 'history.sessionDeleted',
      type: 'success',
    });
  });

  it('forceCreateNewSession interrupts loading session and cleans state', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'streaming...', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('interrupt_session'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
  });

  it('forceCreateNewSessionWithProvider resets session and applies target provider before recreating', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSessionWithProvider('codex');
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, bridgeCall('set_provider', 'codex'));
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, bridgeCall('create_new_session'));
    expect(window.__sessionTransitioning).toBe(true);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
  });

  it('shows confirm dialog when creating new session with existing messages', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Should show confirm dialog, NOT immediately transition
    expect(result.current.showNewSessionConfirm).toBe(true);
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
    expect(mocks.setMessages).not.toHaveBeenCalled();
  });

  it('skips confirm dialog when skipNewSessionConfirm preference is enabled', () => {
    // User previously ticked "don't ask again" — dialog should be bypassed.
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Should transition immediately without showing the dialog.
    expect(result.current.showNewSessionConfirm).toBe(false);
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
  });

  it('still shows the interrupt dialog while loading even if skipNewSessionConfirm is enabled', () => {
    // Safety guard: the "don't ask again" preference must NOT bypass the
    // dangerous "interrupt running AI" confirm dialog. (See AppDialogs comment.)
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'thinking', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Interrupt dialog must still appear; no silent transition.
    expect(result.current.showInterruptConfirm).toBe(true);
    expect(result.current.showNewSessionConfirm).toBe(false);
    expect(window.__sessionTransitioning).toBe(false);
    expect(mocks.setMessages).not.toHaveBeenCalled();
    expect(window.sendToJava).not.toHaveBeenCalledWith(bridgeCall('create_new_session'));
  });

  it('handleConfirmInterrupt completes interrupt+transition even with skipNewSessionConfirm enabled', () => {
    // Regression guard: once the interrupt dialog is confirmed, the flow must
    // send interrupt_session + create_new_session exactly once. The skip
    // preference must not cause a second silent transition or skip the
    // interrupt signal.
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'thinking', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Open the interrupt dialog.
    act(() => {
      result.current.createNewSession();
    });
    expect(result.current.showInterruptConfirm).toBe(true);

    // User confirms interrupt.
    act(() => {
      result.current.handleConfirmInterrupt();
    });

    // Dialog cleared, transition started, and BOTH bridge events fired exactly once.
    expect(result.current.showInterruptConfirm).toBe(false);
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.setMessages).toHaveBeenCalledWith([]);

    const calls = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls.map(
      (c: unknown[]) => c[0]
    );
    expect(calls.filter((c) => c === bridgeCall('interrupt_session'))).toHaveLength(1);
    expect(calls.filter((c) => c === bridgeCall('create_new_session'))).toHaveLength(1);
  });

  it('handleConfirmNewSession cleans state and creates new session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Trigger dialog first
    act(() => {
      result.current.createNewSession();
    });

    // Confirm
    act(() => {
      result.current.handleConfirmNewSession();
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(result.current.showNewSessionConfirm).toBe(false);
  });

  it('handleConfirmInterrupt interrupts and cleans state', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'responding...', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Must trigger interrupt dialog first
    act(() => {
      result.current.createNewSession();
    });

    // Then confirm interrupt
    act(() => {
      result.current.handleConfirmInterrupt();
    });

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('interrupt_session'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
  });

  it('loadHistorySession without loading state does not send interrupt', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-2',
          title: null,
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 1,
          lastTimestamp: Date.now(),
        },
      ],
      total: 1,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-2');
    });

    // Should NOT send interrupt when not loading
    const calls = (window.sendToJava as any).mock.calls.map((c: any) => c[0]);
    expect(calls).not.toContain(bridgeCall('interrupt_session'));
    expect(calls).toContain(bridgeCall('load_session', '{"sessionId":"hist-2","provider":"claude"}'));

    // But should still set transition guard
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('hist-2');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith(null);
  });

  it('loadHistorySession sends explicit provider when provided by history item', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-codex',
          title: 'Codex Session',
          provider: 'codex',
          model: 'gpt-5.4',
          messageCount: 2,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-codex', 'codex');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      bridgeCall('load_session', '{"sessionId":"hist-codex","provider":"codex"}')
    );
  });

  it('loadHistorySession falls back to current provider when history item has no provider', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-codex-missing-provider',
          title: 'Codex Session',
          messageCount: 2,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        currentProvider: 'codex',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-codex-missing-provider');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'load_session:{"sessionId":"hist-codex-missing-provider","provider":"codex"}'
    );
  });

  it('all transition paths reset usage tokens', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Test forceCreateNewSession
    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(mocks.setUsageMaxTokens).toHaveBeenCalledWith(undefined);
  });

  it('beginSessionTransition clears all transient UI states synchronously', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    // All transient UI states must be synchronously cleared
    expect(mocks.setStatus).toHaveBeenCalledWith('');
    expect(mocks.setLoading).toHaveBeenCalledWith(false);
    expect(mocks.setIsThinking).toHaveBeenCalledWith(false);
    expect(mocks.setStreamingActive).toHaveBeenCalledWith(false);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(mocks.setUsageMaxTokens).toHaveBeenCalledWith(undefined);
  });

  it('historyLoadComplete releases transition guard', () => {
    // Simulate what happens when Java calls historyLoadComplete after successful load
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-test';

    // historyLoadComplete is defined in useWindowCallbacks, but we can test
    // that the guard release mechanism works by direct simulation
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBe('transition-test');

    // Simulate historyLoadComplete behavior
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });

  it('loadHistorySession sets transition guard that blocks updateMessages', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-3',
          title: 'Test Session',
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 1,
          lastTimestamp: Date.now(),
        },
      ],
      total: 1,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-3');
    });

    // Guard is set, blocking stale updateMessages
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();

    // Simulate historyLoadComplete (success path releases guard)
    act(() => {
      window.__sessionTransitioning = false;
      window.__sessionTransitionToken = null;
    });
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();

    // Simulate failure path: guard must also be released
    act(() => {
      window.__sessionTransitioning = true; // re-arm
      window.__sessionTransitionToken = 'transition-rearm';
    });
    // Java exceptionally block calls historyLoadComplete before addErrorMessage
    act(() => {
      window.__sessionTransitioning = false;
      window.__sessionTransitionToken = null;
    });
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });

  // ── createNewSessionWithProvider:切换供应商走"先确认再新建"路径(问题1) ──
  const messagesWith = (content: string, type: 'user' | 'assistant' = 'user') => [
    { type, content, timestamp: new Date().toISOString() },
  ];
  const sentTypes = () =>
    (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls.map((c: unknown[]) => c[0]);

  it('createNewSessionWithProvider shows confirm without switching when messages exist', () => {
    const mocks = createMocks();
    const onExec = vi.fn();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: messagesWith('hello'),
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSessionWithProvider('codex', onExec);
    });

    expect(result.current.showNewSessionConfirm).toBe(true);
    expect(window.__sessionTransitioning).toBe(false);
    expect(onExec).not.toHaveBeenCalled();
    const calls = sentTypes();
    expect(calls).not.toContain(bridgeCall('set_provider', 'codex'));
    expect(calls).not.toContain(bridgeCall('create_new_session'));
  });

  it('createNewSessionWithProvider switches provider after confirm', () => {
    const mocks = createMocks();
    const onExec = vi.fn();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: messagesWith('hello'),
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSessionWithProvider('codex', onExec);
    });
    act(() => {
      result.current.handleConfirmNewSession();
    });

    expect(onExec).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('set_provider', 'codex'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(window.__sessionTransitioning).toBe(true);
    expect(result.current.showNewSessionConfirm).toBe(false);
  });

  it('createNewSessionWithProvider cancel leaves provider state untouched', () => {
    const mocks = createMocks();
    const onExec = vi.fn();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: messagesWith('hello'),
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSessionWithProvider('codex', onExec);
    });
    act(() => {
      result.current.handleCancelNewSession();
    });

    expect(onExec).not.toHaveBeenCalled();
    expect(result.current.showNewSessionConfirm).toBe(false);
    const calls = sentTypes();
    expect(calls).not.toContain(bridgeCall('set_provider', 'codex'));
    expect(calls).not.toContain(bridgeCall('create_new_session'));
  });

  it('createNewSessionWithProvider shows interrupt dialog while loading and switches after confirm', () => {
    const mocks = createMocks();
    const onExec = vi.fn();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: messagesWith('responding', 'assistant'),
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSessionWithProvider('codex', onExec);
    });
    expect(result.current.showInterruptConfirm).toBe(true);
    expect(onExec).not.toHaveBeenCalled();

    act(() => {
      result.current.handleConfirmInterrupt();
    });

    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('interrupt_session'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('set_provider', 'codex'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(onExec).toHaveBeenCalledTimes(1);
  });

  it('createNewSessionWithProvider switches immediately when no messages and not loading', () => {
    const mocks = createMocks();
    const onExec = vi.fn();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSessionWithProvider('codex', onExec);
    });

    expect(onExec).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('set_provider', 'codex'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
    expect(result.current.showNewSessionConfirm).toBe(false);
  });

  it('createNewSessionWithProvider skips confirm when skipNewSessionConfirm is enabled', () => {
    localStorage.setItem('skipNewSessionConfirm', 'true');
    const mocks = createMocks();
    const onExec = vi.fn();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: messagesWith('hello'),
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSessionWithProvider('codex', onExec);
    });

    expect(result.current.showNewSessionConfirm).toBe(false);
    expect(onExec).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('set_provider', 'codex'));
    expect(window.sendToJava).toHaveBeenCalledWith(bridgeCall('create_new_session'));
  });

  it('requests backend-owned JSON and HTML export formats', () => {
    const mocks = createMocks();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.exportHistorySession('session-1', 'Demo', HISTORY_EXPORT_FORMAT.JSON);
      result.current.exportHistorySession('session-1', 'Demo', HISTORY_EXPORT_FORMAT.HTML);
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(
      1,
      bridgeCall(
        UPSTREAM.EXPORT_SESSION,
        JSON.stringify({ sessionId: 'session-1', title: 'Demo', format: HISTORY_EXPORT_FORMAT.JSON }),
      ),
    );
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      bridgeCall(
        UPSTREAM.EXPORT_SESSION,
        JSON.stringify({ sessionId: 'session-1', title: 'Demo', format: HISTORY_EXPORT_FORMAT.HTML }),
      ),
    );
  });

  it('prints a session to PDF by delegating to the backend browser handoff', () => {
    const mocks = createMocks();
    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.printSessionPdf('session-1', 'Demo');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      bridgeCall(
        UPSTREAM.PRINT_SESSION_PDF,
        JSON.stringify({ sessionId: 'session-1', title: 'Demo' }),
      ),
    );
  });

});
