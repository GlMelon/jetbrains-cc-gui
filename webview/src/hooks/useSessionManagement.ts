import { sendAction } from '../bridge/typed';
import { UPSTREAM } from '../generated/protocol';
import type { HistoryArchiveResultPayloadWire, HistoryExportFormat } from '../generated/protocol';
import { useCallback, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, HistoryData, ViewMode } from '../types';
import { getSkipNewSessionConfirm } from '../utils/skipNewSessionConfirm';
import { clearAllPersistedExpanded } from '../utils/expandedState';

type ToastType = 'info' | 'success' | 'warning' | 'error';

const createSessionTransitionToken = () =>
  `transition-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

interface UseSessionManagementOptions {
  messages: ClaudeMessage[];
  loading: boolean;
  historyData: HistoryData | null;
  currentSessionId: string | null;
  setHistoryData: React.Dispatch<React.SetStateAction<HistoryData | null>>;
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  setCurrentView: (view: ViewMode) => void;
  setCurrentSessionId: (id: string | null) => void;
  setCustomSessionTitle: (title: string | null) => void;
  setUsagePercentage: (percent: number) => void;
  setUsageUsedTokens: (tokens: number | undefined) => void;
  setUsageMaxTokens: (tokens: number | undefined) => void;
  setStatus: (status: string) => void;
  setLoading: (loading: boolean) => void;
  setIsThinking: (thinking: boolean) => void;
  setStreamingActive: (active: boolean) => void;
  clearToasts: () => void;
  addToast: (message: string, type?: ToastType) => void;
  t: TFunction;
}

interface UseSessionManagementReturn {
  showNewSessionConfirm: boolean;
  showInterruptConfirm: boolean;
  suppressNextStatusToastRef: React.MutableRefObject<boolean>;
  createNewSession: () => void;
  forceCreateNewSession: () => void;
  forceCreateNewSessionWithProvider: (providerId: string) => void;
  /**
   * 切换供应商 + 新建会话(带确认,对称 createNewSession 三分支门控)。
   * onConfirmedExec 由调用方提供(负责切前端 provider state),仅在确认后执行 →
   * 取消时 provider state 完全不变,避免"显示新 provider 却还是旧会话"的不一致。
   */
  createNewSessionWithProvider: (providerId: string, onConfirmedExec: () => void) => void;
  handleConfirmNewSession: () => void;
  handleCancelNewSession: () => void;
  handleConfirmInterrupt: () => void;
  handleCancelInterrupt: () => void;
  loadHistorySession: (sessionId: string, provider?: string) => void;
  deleteHistorySession: (sessionId: string) => void;
  deleteHistorySessions: (sessionIds: string[]) => void;
  archiveHistorySessions: (sessionIds: string[]) => void;
  handleHistoryArchiveResult: (payload: HistoryArchiveResultPayloadWire) => void;
  exportHistorySession: (sessionId: string, title: string, format: HistoryExportFormat) => void;
  printSessionPdf: (sessionId: string, title: string) => void;
  toggleFavoriteSession: (sessionId: string) => void;
  updateHistoryTitle: (sessionId: string, newTitle: string) => void;
  applyHistoryTitleLocal: (sessionId: string, newTitle: string) => void;
  convertToCliSession: (sessionId: string) => void;
}

/**
 * Hook for managing session operations (create, load, delete, export, etc.)
 */
export function useSessionManagement({
  messages,
  loading,
  historyData,
  currentSessionId,
  setHistoryData,
  setMessages,
  setCurrentView,
  setCurrentSessionId,
  setCustomSessionTitle,
  setUsagePercentage,
  setUsageUsedTokens,
  setUsageMaxTokens,
  setStatus,
  setLoading: setLoadingState,
  setIsThinking,
  setStreamingActive,
  clearToasts,
  addToast,
  t,
}: UseSessionManagementOptions): UseSessionManagementReturn {
  const [showNewSessionConfirm, setShowNewSessionConfirm] = useState(false);
  const [showInterruptConfirm, setShowInterruptConfirm] = useState(false);
  const pendingActionRef = useRef<'newSession' | null>(null);
  // 切换供应商走"先确认再新建"路径,与 createNewSession 共用 showNewSessionConfirm/
  // showInterruptConfirm 门控。pendingProviderExecRef 暂存确认后才执行的回调(含切前端
  // provider state);取消时丢弃 → provider state 完全不变,避免误操作不可撤回。
  const pendingProviderExecRef = useRef<(() => void) | null>(null);
  const suppressNextStatusToastRef = useRef(false);
  const transitionTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historyDataRef = useRef(historyData);
  historyDataRef.current = historyData;
  const currentSessionIdRef = useRef(currentSessionId);
  currentSessionIdRef.current = currentSessionId;
  const loadingRef = useRef(loading);
  loadingRef.current = loading;
  const showSessionDeletedToast = useCallback((afterSessionTransition = false) => {
    const toast = { message: t('history.sessionDeleted'), type: 'success' as const };
    if (afterSessionTransition) {
      window.__pendingSessionTransitionToast = toast;
      return;
    }
    addToast(toast.message, toast.type);
  }, [addToast, t]);

  const beginSessionTransition = useCallback((nextSessionId: string | null, nextTitle: string | null) => {
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = createSessionTransitionToken();
    // Clear expand/collapse cache on session switch to avoid unbounded growth
    clearAllPersistedExpanded();
    // Use the single cleanup entry point exposed by useWindowCallbacks.
    // This clears both React state AND internal streaming refs in one shot.
    if (typeof window.__resetTransientUiState === 'function') {
      window.__resetTransientUiState();
    } else {
      // Fallback if useWindowCallbacks hasn't mounted yet (e.g. during SSR/tests)
      clearToasts();
      setStatus('');
      setLoadingState(false);
      setIsThinking(false);
      setStreamingActive(false);
    }
    setMessages([]);
    setCurrentSessionId(nextSessionId);
    setCustomSessionTitle(nextTitle);
    setUsagePercentage(0);
    setUsageUsedTokens(undefined);
    setUsageMaxTokens(undefined);

    // FIX: Safety timeout to auto-release the session transition guard.
    // If the backend's historyLoadComplete signal is lost (e.g., JCEF IPC failure
    // during webview reload, or a backend error that prevents the callback),
    // __sessionTransitioning would remain true permanently, silently dropping ALL
    // message callbacks (updateMessages, onContentDelta, onStreamStart, etc.).
    // This makes the webview appear "dead" while the backend continues working.
    if (transitionTimeoutRef.current !== null) {
      clearTimeout(transitionTimeoutRef.current);
    }
    const token = window.__sessionTransitionToken;
    transitionTimeoutRef.current = setTimeout(() => {
      transitionTimeoutRef.current = null;
      if (window.__sessionTransitioning && window.__sessionTransitionToken === token) {
        console.warn('[SessionManagement] Transition guard timed out — auto-releasing');
        window.__sessionTransitioning = false;
        window.__sessionTransitionToken = null;
      }
    }, 15_000); // 15 seconds — generous enough for slow history loads
  }, [clearToasts, setStatus, setLoadingState, setIsThinking, setStreamingActive, setMessages, setCurrentSessionId, setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens]);

  // Create new session
  const createNewSession = useCallback(() => {
    // [FIX] Prioritize loading check - if AI is responding, must interrupt first
    // This prevents creating new session without stopping the current conversation
    if (loading) {
      // If loading (AI is responding), show interrupt confirmation.
      // NOTE: The "don't ask again" preference deliberately does NOT apply here —
      // interrupting an in-progress AI response is a more dangerous operation
      // and must always require explicit confirmation.
      pendingActionRef.current = 'newSession';
      setShowInterruptConfirm(true);
    } else if (messages.length > 0) {
      // If the user previously ticked "don't ask again", skip the dialog and
      // proceed directly. Preference is read fresh each call so the setting
      // page toggle takes effect immediately.
      if (getSkipNewSessionConfirm()) {
        beginSessionTransition(null, null);
        sendAction(UPSTREAM.CREATE_NEW_SESSION);
        return;
      }
      // If there are messages but not loading, show new session confirmation
      pendingActionRef.current = 'newSession';
      setShowNewSessionConfirm(true);
    } else {
      // If empty and not loading, directly create new session
      beginSessionTransition(null, null);
      sendAction(UPSTREAM.CREATE_NEW_SESSION);
    }
  }, [beginSessionTransition, messages.length, loading]);

  // Force create new session (no confirmation, used by /clear /new /reset commands)
  const forceCreateNewSession = useCallback(() => {
    if (loading) {
      sendAction(UPSTREAM.INTERRUPT_SESSION);
    }
    beginSessionTransition(null, null);
    sendAction(UPSTREAM.CREATE_NEW_SESSION);
  }, [beginSessionTransition, loading]);

  const forceCreateNewSessionWithProvider = useCallback((providerId: string) => {
    if (loading) {
      sendAction(UPSTREAM.INTERRUPT_SESSION);
    }
    beginSessionTransition(null, null);
    sendAction(UPSTREAM.SET_PROVIDER, providerId);
    sendAction(UPSTREAM.CREATE_NEW_SESSION);
  }, [beginSessionTransition, loading]);

  // 切换供应商 + 新建会话(带确认)。与 forceCreateNewSessionWithProvider 的区别:
  // force 版本无确认直接清会话(误操作不可撤回);此方法在已有对话/loading 时弹确认,
  // 复用 createNewSession 的三分支门控(含"不再提示")。onConfirmedExec 由调用方提供,
  // 负责切前端 provider state(如 handleProviderSelect)——仅在确认后(或直接执行分支)调用,
  // 取消则永不调用,provider state 保持不变。
  const createNewSessionWithProvider = useCallback((providerId: string, onConfirmedExec: () => void) => {
    const exec = () => {
      beginSessionTransition(null, null);
      onConfirmedExec();
      sendAction(UPSTREAM.SET_PROVIDER, providerId);
      sendAction(UPSTREAM.CREATE_NEW_SESSION);
    };
    if (loading) {
      pendingProviderExecRef.current = exec;
      pendingActionRef.current = 'newSession';
      setShowInterruptConfirm(true);
    } else if (messages.length > 0) {
      // 已"不再提示"则直接执行(对称 createNewSession)
      if (getSkipNewSessionConfirm()) {
        exec();
        return;
      }
      pendingProviderExecRef.current = exec;
      pendingActionRef.current = 'newSession';
      setShowNewSessionConfirm(true);
    } else {
      exec();
    }
  }, [beginSessionTransition, messages.length, loading]);

  // Confirm new session
  const handleConfirmNewSession = useCallback(() => {
    setShowNewSessionConfirm(false);
    // 切换供应商路径:执行暂存的 provider 切换+新建回调,然后清空 ref。
    const pendingProviderExec = pendingProviderExecRef.current;
    if (pendingProviderExec) {
      pendingProviderExecRef.current = null;
      pendingActionRef.current = null;
      if (loading) {
        sendAction(UPSTREAM.INTERRUPT_SESSION);
      }
      pendingProviderExec();
      return;
    }
    // [FIX] Safety check: if loading started while dialog was open, send interrupt first
    if (loading) {
      sendAction(UPSTREAM.INTERRUPT_SESSION);
    }
    beginSessionTransition(null, null);
    sendAction(UPSTREAM.CREATE_NEW_SESSION);
    pendingActionRef.current = null;
  }, [beginSessionTransition, loading]);

  // Cancel new session
  const handleCancelNewSession = useCallback(() => {
    setShowNewSessionConfirm(false);
    // 丢弃暂存的 provider 切换回调 → 取消零副作用,provider state 不变。
    pendingProviderExecRef.current = null;
    pendingActionRef.current = null;
  }, []);

  // Confirm interrupt
  const handleConfirmInterrupt = useCallback(() => {
    setShowInterruptConfirm(false);
    // 切换供应商路径:中断后执行暂存的 provider 切换+新建回调。
    const pendingProviderExec = pendingProviderExecRef.current;
    if (pendingProviderExec) {
      pendingProviderExecRef.current = null;
      pendingActionRef.current = null;
      sendAction(UPSTREAM.INTERRUPT_SESSION);
      pendingProviderExec();
      return;
    }
    // Send interrupt signal and create new session
    sendAction(UPSTREAM.INTERRUPT_SESSION);
    beginSessionTransition(null, null);
    sendAction(UPSTREAM.CREATE_NEW_SESSION);
    pendingActionRef.current = null;
  }, [beginSessionTransition]);

  // Cancel interrupt
  const handleCancelInterrupt = useCallback(() => {
    setShowInterruptConfirm(false);
    pendingProviderExecRef.current = null;
    pendingActionRef.current = null;
  }, []);

  // Load history session
  const loadHistorySession = useCallback((sessionId: string, provider?: string) => {
    // [FIX] Send interrupt signal if AI is responding
    if (loading) {
      sendAction(UPSTREAM.INTERRUPT_SESSION);
    }

    const session = historyDataRef.current?.sessions?.find(s => s.sessionId === sessionId);
    beginSessionTransition(sessionId, session?.title ?? null);
    sendAction(UPSTREAM.LOAD_SESSION, JSON.stringify({
      sessionId,
      provider: provider || session?.provider || 'claude',
    }));
    setCurrentView('chat');
  }, [beginSessionTransition, loading, setCurrentView]);

  // Delete history session
  const deleteHistorySession = useCallback((sessionId: string) => {
    // Send delete request to Java backend
    sendAction(UPSTREAM.DELETE_SESSION, sessionId);
    let startedSessionTransition = false;

    // Immediately update frontend state, remove session from history list
    if (historyData && historyData.sessions) {
      setHistoryData(prevHistoryData => {
        if (!prevHistoryData?.sessions) {
          return prevHistoryData;
        }

        const deletedSession = prevHistoryData.sessions.find(s => s.sessionId === sessionId);
        return {
          ...prevHistoryData,
          sessions: prevHistoryData.sessions.filter(s => s.sessionId !== sessionId),
          total: Math.max(0, (prevHistoryData.total || 0) - (deletedSession?.messageCount || 0))
        };
      });

      // If deleted session is current session, clear messages and reset state
      if (sessionId === currentSessionId) {
        // [FIX] Send interrupt signal if AI is responding
        if (loading) {
          sendAction(UPSTREAM.INTERRUPT_SESSION);
        }
        beginSessionTransition(null, null);
        startedSessionTransition = true;
        // Set flag to suppress next updateStatus toast
        suppressNextStatusToastRef.current = true;
        sendAction(UPSTREAM.CREATE_NEW_SESSION);
      }

    }
    showSessionDeletedToast(startedSessionTransition);
  }, [historyData, currentSessionId, loading, setHistoryData, setMessages, setCurrentSessionId, setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, showSessionDeletedToast]);

  // Batch delete history sessions
  const deleteHistorySessions = useCallback((sessionIds: string[]) => {
    const uniqueSessionIds = Array.from(new Set(sessionIds.filter(Boolean)));
    if (uniqueSessionIds.length === 0) {
      return;
    }

    sendAction(UPSTREAM.DELETE_SESSIONS, JSON.stringify(uniqueSessionIds));
    let startedSessionTransition = false;

    if (historyData && historyData.sessions) {
      const deletedSessionIds = new Set(uniqueSessionIds);
      setHistoryData(prevHistoryData => {
        if (!prevHistoryData?.sessions) {
          return prevHistoryData;
        }

        const deletedMessageCount = prevHistoryData.sessions.reduce((sum, session) => (
          deletedSessionIds.has(session.sessionId) ? sum + (session.messageCount || 0) : sum
        ), 0);

        return {
          ...prevHistoryData,
          sessions: prevHistoryData.sessions.filter(session => !deletedSessionIds.has(session.sessionId)),
          total: Math.max(0, (prevHistoryData.total || 0) - deletedMessageCount)
        };
      });

      if (currentSessionId && deletedSessionIds.has(currentSessionId)) {
        if (loading) {
          sendAction(UPSTREAM.INTERRUPT_SESSION);
        }
        beginSessionTransition(null, null);
        startedSessionTransition = true;
        suppressNextStatusToastRef.current = true;
        sendAction(UPSTREAM.CREATE_NEW_SESSION);
      }

    }
    showSessionDeletedToast(startedSessionTransition);
  }, [historyData, currentSessionId, loading, setHistoryData, beginSessionTransition, showSessionDeletedToast]);

  const archiveHistorySessions = useCallback((sessionIds: string[]) => {
    const uniqueSessionIds = Array.from(new Set(sessionIds.filter(Boolean)));
    if (uniqueSessionIds.length === 0) {
      return;
    }
    sendAction(UPSTREAM.ARCHIVE_SESSIONS, JSON.stringify(uniqueSessionIds));
  }, []);

  const handleHistoryArchiveResult = useCallback((payload: HistoryArchiveResultPayloadWire) => {
    const archivedSessionIds = Array.isArray(payload.archivedSessionIds)
      ? payload.archivedSessionIds
      : [];
    const activeSessionId = currentSessionIdRef.current;
    const archivedCurrentSession = activeSessionId !== null && archivedSessionIds.includes(activeSessionId);

    if (archivedCurrentSession) {
      if (loadingRef.current) {
        sendAction(UPSTREAM.INTERRUPT_SESSION);
      }
      beginSessionTransition(null, null);
      suppressNextStatusToastRef.current = true;
      sendAction(UPSTREAM.CREATE_NEW_SESSION);
    }

    if (payload.success) {
      const toast = { message: t('history.sessionsArchived'), type: 'success' as const };
      if (archivedCurrentSession) {
        window.__pendingSessionTransitionToast = toast;
      } else {
        addToast(toast.message, toast.type);
      }
      return;
    }

    addToast(t('history.archiveFailed'), 'error');
  }, [addToast, beginSessionTransition, t]);

  // Export history session
  const exportHistorySession = useCallback((sessionId: string, title: string, format: HistoryExportFormat) => {
    const exportData = JSON.stringify({ sessionId, title, format });
    sendAction(UPSTREAM.EXPORT_SESSION, exportData);
  }, []);

  // Print session to PDF: backend reuses the sanitized HTML renderer and opens it in the
  // system browser so the user can "Save as PDF" via the browser's native print engine.
  const printSessionPdf = useCallback((sessionId: string, title: string) => {
    const printData = JSON.stringify({ sessionId, title });
    sendAction(UPSTREAM.PRINT_SESSION_PDF, printData);
  }, []);

  // Toggle favorite status
  const toggleFavoriteSession = useCallback((sessionId: string) => {
    // Send favorite toggle request to backend
    sendAction(UPSTREAM.TOGGLE_FAVORITE, sessionId);

    // Immediately update frontend state
    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map(session => {
        if (session.sessionId === sessionId) {
          const isFavorited = !session.isFavorited;
          return {
            ...session,
            isFavorited,
            favoritedAt: isFavorited ? Date.now() : undefined
          };
        }
        return session;
      });

      setHistoryData({
        ...historyData,
        sessions: updatedSessions
      });

      // Show toast
      const session = historyData.sessions.find(s => s.sessionId === sessionId);
      if (session?.isFavorited) {
        addToast(t('history.unfavorited'), 'success');
      } else {
        addToast(t('history.favorited'), 'success');
      }
    }
  }, [historyData, setHistoryData, addToast, t]);

  // Update session title
  const updateHistoryTitle = useCallback((sessionId: string, newTitle: string) => {
    // Send update title request to backend
    const updateData = JSON.stringify({ sessionId, customTitle: newTitle });
    sendAction(UPSTREAM.UPDATE_TITLE, updateData);

    // Immediately update frontend state
    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map(session => {
        if (session.sessionId === sessionId) {
          return {
            ...session,
            title: newTitle
          };
        }
        return session;
      });

      setHistoryData({
        ...historyData,
        sessions: updatedSessions
      });

      // Show success toast
      addToast(t('history.titleUpdated'), 'success');
    }
  }, [historyData, setHistoryData, addToast, t]);

  // AI-generated titles are already persisted by saveAiTitle to the JSONL
  // session file. This skips the round-trip through the customTitle endpoint,
  // which would otherwise reject titles over its length limit.
  const applyHistoryTitleLocal = useCallback((sessionId: string, newTitle: string) => {
    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map(session =>
        session.sessionId === sessionId ? { ...session, title: newTitle } : session
      );
      setHistoryData({
        ...historyData,
        sessions: updatedSessions
      });
    }
  }, [historyData, setHistoryData]);

  // Convert SDK-created session to CLI-recognizable session.
  // The backend sends an onConversionResult callback with success/failure;
  // the registered handler in sessionCallbacks shows the appropriate toast
  // and triggers a deep-search reload on failure to restore the correct state.
  // We optimistically change the entrypoint from 'sdk-cli' to 'cli' so the badge disappears
  // immediately.  Functional update avoids depending on historyData directly,
  // keeping the callback reference stable across renders.
  const convertToCliSession = useCallback((sessionId: string) => {
    sendAction(UPSTREAM.CONVERT_TO_CLI_SESSION, sessionId);

    // Optimistically change entrypoint while the backend works.
    setHistoryData(prev => {
      if (!prev?.sessions) return prev;
      return {
        ...prev,
        sessions: prev.sessions.map(s =>
          s.sessionId === sessionId ? { ...s, entrypoint: 'cli' } : s
        ),
      };
    });
  }, [setHistoryData]);

  return {
    showNewSessionConfirm,
    showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession,
    forceCreateNewSession,
    forceCreateNewSessionWithProvider,
    createNewSessionWithProvider,
    handleConfirmNewSession,
    handleCancelNewSession,
    handleConfirmInterrupt,
    handleCancelInterrupt,
    loadHistorySession,
    deleteHistorySession,
    deleteHistorySessions,
    archiveHistorySessions,
    handleHistoryArchiveResult,
    exportHistorySession,
    printSessionPdf,
    toggleFavoriteSession,
    updateHistoryTitle,
    applyHistoryTitleLocal,
    convertToCliSession,
  };
}
