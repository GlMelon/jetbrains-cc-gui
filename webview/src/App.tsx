import { sendAction } from './bridge/typed';
import { UPSTREAM } from './generated/protocol';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryView from './components/history/HistoryView';
import SettingsView from './components/settings';
import { preloadSlashCommands, forceRefreshPrompts } from './components/ChatInputBox/providers';
import {
  useScrollBehavior,
  useSessionManagement,
  useStreamingMessages,
  useWindowCallbacks,
  useRewindHandlers,
  useHistoryLoader,
  useMessageQueue,
  useThemeInit,
  useContextActions,
  useMessageProcessing,
  useMessageSender,
  useModelProviderState,
  useChatComputations,
  useAvatarConfig,
} from './hooks';
import {
  NEW_SESSION_COMMANDS,
  RESUME_COMMANDS,
  PLAN_COMMANDS,
  CONTEXT_COMMANDS,
} from './hooks/useMessageSender';
import { applyDiffTheme, getStoredDiffTheme } from './utils/diffTheme';
import type { Attachment, ChatInputBoxHandle } from './components/ChatInputBox/types';
import { ChatHeader } from './components/ChatHeader';
import { ChatScreen } from './components/ChatScreen';
import type { MessageListRevealHandle } from './components/ConversationSearch/types';
import { ModelProviderProvider } from './contexts/ModelProviderContext';
import { useSubagentContextValues, useSetTaskEvents } from './contexts/SubagentContext';
import { useMessages } from './contexts/MessagesContext';
import { useSession } from './contexts/SessionContext';
import { useUIState } from './contexts/UIStateContext';
import { useDialogs } from './contexts/DialogContext';
import { AppDialogs } from './components/AppDialogs';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from './utils/permissionDialogTimeout';
import {
  getDetailedOutputEnabled,
  setDetailedOutputEnabled,
} from './utils/detailedOutputPreference';

const App = () => {
  const { t } = useTranslation();

  // ── Dialog management (extracted to DialogContext, stage 4 of TASK-P1-01) ──
  // Open* / set* are still needed by hooks (useWindowCallbacks, useRewindHandlers).
  // Display state (permissionDialogOpen / askUserQuestionDialogOpen / etc.) is
  // consumed directly inside <AppDialogs> via useDialogs().
  const {
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    openContextUsageDialog,
    updateContextUsageData,
    closeContextUsageDialog,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    isRewinding,
    setIsRewinding,
    setRewindSelectDialogOpen,
  } = useDialogs();

  // ── Messages flow state (extracted to MessagesContext, stage 1 of TASK-P1-01) ──
  // Display state (loadingStartTime / isThinking) is consumed inside <ChatScreen>.
  const {
    messages,
    setMessages,
    subagentHistories,
    setSubagentHistories,
    setStatus,
    loading,
    setLoading,
    setLoadingStartTime,
    setQueueDisplayState,
    setQueueAheadCount,
    setIsThinking,
    streamingActive,
    setStreamingActive,
  } = useMessages();

  // ── Session state (extracted to SessionContext, stage 2 of TASK-P1-01) ──
  const {
    currentSessionId,
    setCurrentSessionId,
    customSessionTitle,
    setCustomSessionTitle,
    historyData,
    setHistoryData,
    currentSessionIdRef,
    customSessionTitleRef,
  } = useSession();

  // ── UI state (extracted to UIStateContext, stage 3 of TASK-P1-01) ──
  // Dialog visibility (addModelDialog / changelog) is consumed inside AppDialogs.
  const {
    currentView,
    setCurrentView,
    settingsInitialTab,
    setSettingsInitialTab,
    addToast,
    clearToasts,
    setContextInfo,
    searchOpen,
    setSearchOpen,
  } = useUIState();

  // ── Permission dialog timeout (synced with backend config) ──
  // C5:默认值现由 generated 产出(literal 300 as const);state 显式 number,允许后续 setState 任意秒数。
  const [permissionDialogTimeoutSeconds, setPermissionDialogTimeoutSeconds] = useState<number>(
    DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  );
  const [detailedOutputEnabled, setDetailedOutputEnabledState] = useState<boolean>(() =>
    getDetailedOutputEnabled(),
  );
  const handleDetailedOutputEnabledChange = useCallback((enabled: boolean) => {
    setDetailedOutputEnabledState(enabled);
    setDetailedOutputEnabled(enabled);
  }, []);

  // ── Local refs (don't trigger re-render, kept in App.tsx) ──
  const isFirstMountRef = useRef(true);
  const chatInputRef = useRef<ChatInputBoxHandle>(null);

  // StatusPanel collapse state — kept in App.tsx because forceStatusUpdate is
  // intentionally local: a tiny re-render trigger paired with userCollapsedRef.
  const userCollapsedRef = useRef(false);
  const [, forceStatusUpdate] = useState(0);

  // Message anchor node registry for anchor rail navigation
  const messageNodeMapRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const [anchorCollapsedCount, setAnchorCollapsedCount] = useState(0);
  const handleMessageNodeRef = useCallback((id: string, node: HTMLDivElement | null) => {
    if (node) {
      messageNodeMapRef.current.set(id, node);
    } else {
      messageNodeMapRef.current.delete(id);
    }
  }, []);

  // Imperative handle for the in-page search panel to expand collapsed earlier messages.
  const messageListRef = useRef<MessageListRevealHandle | null>(null);

  // ── Theme & context actions ──
  useThemeInit();
  useContextActions();

  const {
    avatarConfig,
    setAssistantAvatarSelection,
    setUserAvatarSelection,
    uploadAssistantAvatar,
    uploadUserAvatar,
  } = useAvatarConfig();

  // Apply diff theme on app startup so diff styles work before opening Settings.
  useEffect(() => {
    const ideTheme = window.__INITIAL_IDE_THEME__ ?? null;
    applyDiffTheme(getStoredDiffTheme(), ideTheme);
  }, []);

  // ── Scroll behavior ──
  const {
    messagesContainerRef,
    messagesEndRef,
    inputAreaRef,
    isUserAtBottomRef,
    isAutoScrollingRef,
    userPausedRef,
  } = useScrollBehavior({ currentView, messages, loading, streamingActive });

  // ── Streaming messages ──
  const {
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    streamingMessageIndexRef,
    contentUpdateTimeoutRef,
    thinkingUpdateTimeoutRef,
    lastContentUpdateRef,
    lastThinkingUpdateRef,
    autoExpandedThinkingKeysRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    findLastAssistantIndex,
    extractRawBlocks,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
  } = useStreamingMessages();

  // (Toast helpers moved to UIStateContext)

  // ── Model/Provider state ──
  const {
    currentProvider,
    selectedModel,
    permissionMode,
    selectedAgent,
    sdkStatusLoaded,
    currentSdkInstalled,
    currentProviderRef,
    activeProviderConfig,
    reasoningEffort,
    streamingEnabledSetting,
    showThinkingEnabledSetting,
    sendShortcut,
    autoOpenFileEnabled,
    longContextEnabled,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
    tokenDetail,
    setCurrentProvider,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setStreamingEnabledSetting,
    setShowThinkingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setSdkStatus,
    setSdkStatusLoaded,
    setSelectedAgent,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setTokenDetail,
    syncActiveProviderModelMapping,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleReasoningChange,
    handleAgentSelect,
    handleStreamingEnabledChange,
    handleShowThinkingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleLongContextChange,
    claudeSdkMeetsMinimum,
  } = useModelProviderState({ addToast, t });

  // ── Global drag event interception ──
  useEffect(() => {
    const preventExternalDrop = (e: DragEvent) => {
      const types = Array.from(e.dataTransfer?.types ?? []);
      const isExternalDrop = types.includes('Files') || types.includes('text/uri-list');
      if (!isExternalDrop) return;
      e.preventDefault();
      e.stopPropagation();
    };
    document.addEventListener('dragover', preventExternalDrop);
    document.addEventListener('drop', preventExternalDrop);
    document.addEventListener('dragenter', preventExternalDrop);
    return () => {
      document.removeEventListener('dragover', preventExternalDrop);
      document.removeEventListener('drop', preventExternalDrop);
      document.removeEventListener('dragenter', preventExternalDrop);
    };
  }, []);

  // ── Close in-conversation search panel when navigating away from chat ──
  // Split from the hotkey effect below so that toggling `searchOpen` does
  // NOT rebind the global keydown listener every time the panel opens/closes.
  useEffect(() => {
    if (currentView !== 'chat' && searchOpen) {
      setSearchOpen(false);
    }
  }, [currentView, searchOpen, setSearchOpen]);

  // ── In-conversation search hotkey (Cmd+F on macOS, Ctrl+F elsewhere) ──
  // Only active in chat view. Settings / history use their own search
  // (HistoryFilters) or none at all — we deliberately let the platform
  // handle Cmd+F there.
  //
  // We deliberately listen for ONLY the platform-appropriate modifier:
  // macOS users use Ctrl+F as the Emacs-style "forward-char" cursor move,
  // so we MUST NOT capture Ctrl+F on macOS. This is a real regression
  // surfaced by code review.
  //
  // Platform detection prefers `navigator.userAgentData.platform` (modern,
  // non-deprecated) and falls back to `userAgent` string sniffing for
  // JCEF / older Chromium where userAgentData may be unavailable.
  // `navigator.platform` is intentionally NOT used — it is deprecated and
  // returns inconsistent values inside JCEF.
  useEffect(() => {
    if (currentView !== 'chat') return;
    const isMac = (() => {
      if (typeof navigator === 'undefined') return false;
      const uaData = (
        navigator as Navigator & {
          userAgentData?: { platform?: string };
        }
      ).userAgentData;
      const platform = uaData?.platform ?? navigator.userAgent ?? '';
      return /mac|iphone|ipad|ipod/i.test(platform);
    })();
    const handler = (e: KeyboardEvent) => {
      const key = e.key;
      if (key !== 'f' && key !== 'F') return;
      const isFind = isMac ? e.metaKey && !e.ctrlKey : e.ctrlKey && !e.metaKey;
      if (!isFind) return;
      // Don't fight IME composition.
      if (e.isComposing) return;
      e.preventDefault();
      e.stopPropagation();
      setSearchOpen(true);
    };
    document.addEventListener('keydown', handler, true);
    return () => document.removeEventListener('keydown', handler, true);
    // setSearchOpen is a stable useState setter; intentionally omitted from
    // deps so we don't rebind the global listener on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentView]);

  // ── Slash command preloading ──
  useEffect(() => {
    preloadSlashCommands();
    forceRefreshPrompts();
    const retryTimer = setTimeout(() => {
      forceRefreshPrompts();
    }, 1000);
    return () => clearTimeout(retryTimer);
  }, []);

  useEffect(() => {
    if (isFirstMountRef.current) {
      isFirstMountRef.current = false;
      return;
    }
    if (currentView === 'chat') {
      forceRefreshPrompts();
    }
  }, [currentView]);

  // ── Session management ──
  const {
    showNewSessionConfirm,
    showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession,
    forceCreateNewSession,
    createNewSessionWithProvider,
    handleConfirmNewSession,
    handleCancelNewSession,
    handleConfirmInterrupt,
    handleCancelInterrupt,
    loadHistorySession,
    deleteHistorySession,
    deleteHistorySessions,
    archiveHistorySessions,
    exportHistorySession,
    printSessionPdf,
    toggleFavoriteSession,
    updateHistoryTitle,
    applyHistoryTitleLocal,
    handleHistoryArchiveResult,
    convertToCliSession,
  } = useSessionManagement({
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
    setTokenDetail,
    setStatus,
    setLoading,
    setIsThinking,
    setStreamingActive,
    clearToasts,
    addToast,
    t,
  });

  useHistoryLoader({ currentView, currentProvider });

  // ── Window callbacks (bridge communication) ──
  const setTaskEvents = useSetTaskEvents();
  useWindowCallbacks({
    t,
    addToast,
    clearToasts,
    setMessages,
    setStatus,
    setLoading,
    setLoadingStartTime,
    setQueueDisplayState,
    setQueueAheadCount,
    setIsThinking,
    setStreamingActive,
    setHistoryData,
    setCurrentSessionId,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setTokenDetail,
    setCurrentProvider,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedOpenCodeModel,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setStreamingEnabledSetting,
    setShowThinkingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setSdkStatus,
    setSdkStatusLoaded, // These come from useUsageTracking
    setIsRewinding,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    setContextInfo,
    setSelectedAgent,
    setSubagentHistories,
    setTaskEvents,
    currentProviderRef,
    messagesContainerRef,
    isUserAtBottomRef,
    userPausedRef,
    suppressNextStatusToastRef,
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    lastContentUpdateRef,
    contentUpdateTimeoutRef,
    lastThinkingUpdateRef,
    thinkingUpdateTimeoutRef,
    findLastAssistantIndex,
    extractRawBlocks,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
    syncActiveProviderModelMapping,
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    openContextUsageDialog,
    updateContextUsageData,
    closeContextUsageDialog,
    customSessionTitleRef,
    currentSessionIdRef,
    updateHistoryTitle,
    applyHistoryTitleLocal,
    handleHistoryArchiveResult,
    setCustomSessionTitle,
    setPermissionDialogTimeoutSeconds,
  });

  // ── Message processing ──
  const { getMessageText, getContentBlocks, mergedMessages, sentAttachmentsRef } =
    useMessageProcessing({ messages, currentSessionId, t });

  // ── Message sender ──
  // Wrap handleProviderSelect to also clear messages and input (like creating a new session)
  const wrappedHandleProviderSelect = useCallback(
    (providerId: string) => {
      chatInputRef.current?.clear();
      // 走带确认的路径:已有对话/loading 时弹确认,避免误切供应商直接清空会话不可撤回。
      // handleProviderSelect(切前端 provider state + 下行 SET_SESSION_*)放进 onConfirmedExec,
      // 仅确认后(或无需确认的直接执行分支)才调用 → 取消时 provider state 完全不变。
      createNewSessionWithProvider(providerId, () => handleProviderSelect(providerId));
    },
    [createNewSessionWithProvider, handleProviderSelect],
  );

  const {
    handleSubmit: hookHandleSubmit,
    executeMessage,
    interruptSession,
  } = useMessageSender({
    t,
    addToast,
    currentProvider,
    selectedModel,
    permissionMode,
    selectedAgent,
    sdkStatusLoaded,
    sentAttachmentsRef,
    chatInputRef,
    messagesContainerRef,
    isUserAtBottomRef,
    userPausedRef,
    isStreamingRef,
    setMessages,
    setLoading,
    setLoadingStartTime,
    setStreamingActive,
    setCurrentView,
    forceCreateNewSession,
    handleModeSelect,
    longContextEnabled,
    openContextUsageDialog,
    closeContextUsageDialog,
  });

  // ── Message queue ──
  const {
    queue: messageQueue,
    enqueue: enqueueMessage,
    dequeue: dequeueMessage,
  } = useMessageQueue({ isLoading: loading, onExecute: executeMessage });

  // handleSubmit with queue support (new session and local commands bypass loading check)
  const handleSubmit = useCallback(
    (content: string, attachments?: Attachment[]) => {
      const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
      const hasAttachments = Array.isArray(attachments) && attachments.length > 0;
      if (!text && !hasAttachments) return;
      // Local commands work even while loading
      if (text.startsWith('/')) {
        const command = text.split(/\s+/)[0].toLowerCase();
        // New session commands
        if (NEW_SESSION_COMMANDS.has(command)) {
          forceCreateNewSession();
          return;
        }
        // /resume - open history view
        if (RESUME_COMMANDS.has(command)) {
          setCurrentView('history');
          return;
        }
        // /plan - switch to plan mode (Claude only; Codex sends as normal text)
        if (PLAN_COMMANDS.has(command) && currentProvider === 'claude') {
          handleModeSelect('plan');
          addToast(t('chat.planModeEnabled', { defaultValue: 'Plan mode enabled' }), 'info');
          return;
        }
        // /context - handled locally even while loading
        if (CONTEXT_COMMANDS.has(command)) {
          hookHandleSubmit(content, attachments);
          return;
        }
      }
      // If loading, add to queue
      if (loading) {
        enqueueMessage(content, attachments);
        return;
      }
      hookHandleSubmit(content, attachments);
    },
    [
      loading,
      enqueueMessage,
      hookHandleSubmit,
      forceCreateNewSession,
      currentProvider,
      handleModeSelect,
      setCurrentView,
      addToast,
      t,
    ],
  );

  // ── Chat-view computations (stage 5 of TASK-P1-01) ──
  const {
    findToolResult,
    getToolResultRaw,
    fileChangeMgmt,
    filteredFileChanges,
    subagents,
    globalTodos,
    rewindableMessages,
    sessionTitle,
  } = useChatComputations({
    t,
    messages,
    mergedMessages,
    customSessionTitle,
    streamingActive,
    currentProvider,
    currentSessionId,
    currentSessionIdRef,
    getMessageText,
    getContentBlocks,
  });

  const { handleUndoFile, handleDiscardAll: handleDiscardAllRaw, handleKeepAll } = fileChangeMgmt;
  const onDiscardAll = useCallback(() => {
    handleDiscardAllRaw(filteredFileChanges);
  }, [handleDiscardAllRaw, filteredFileChanges]);

  // Stabilize context value references for SubagentContext consumers.
  const { subagentHistoryCtxValue, sessionIdCtxValue } = useSubagentContextValues(
    subagentHistories,
    currentSessionId,
  );

  const handleNavigateToProviderSettings = useCallback(() => {
    setSettingsInitialTab('providers');
    setCurrentView('settings');
  }, [setSettingsInitialTab, setCurrentView]);

  const handleNavigateToSdkSettings = useCallback(() => {
    setSettingsInitialTab('dependencies');
    setCurrentView('settings');
  }, [setSettingsInitialTab, setCurrentView]);

  // Warn once when the installed Claude SDK is below the Fable minimum (0.3.182)
  // and the Fable tier is selected. Old CLIs don't recognize the 'fable' alias
  // and pass it through as a literal model name, which 401s on third-party relays
  // ("model fable" / "No available channel"). `claudeSdkMeetsMinimum` is `undefined`
  // until the backend reports status or when the SDK isn't installed — never warn
  // in those cases to avoid false positives.
  const fableSdkWarningShownRef = useRef(false);
  useEffect(() => {
    if (
      currentProvider === 'claude' &&
      currentSdkInstalled &&
      claudeSdkMeetsMinimum === false &&
      /fable/i.test(selectedModel ?? '') &&
      !fableSdkWarningShownRef.current
    ) {
      fableSdkWarningShownRef.current = true;
      addToast(t('chat.sdkTooLowForFable'), 'warning', {
        label: t('chat.updateSdk'),
        onClick: handleNavigateToSdkSettings,
      });
    }
  }, [currentProvider, currentSdkInstalled, claudeSdkMeetsMinimum, selectedModel, addToast, t, handleNavigateToSdkSettings]);

  // ── Rewind handlers ──
  const {
    handleRewindConfirm,
    handleRewindCancel,
    handleOpenRewindSelectDialog,
    handleRewindSelect,
    handleRewindSelectCancel,
  } = useRewindHandlers({
    t,
    addToast,
    currentSessionId,
    mergedMessages,
    getMessageText,
    setCurrentRewindRequest,
    setRewindDialogOpen,
    setRewindSelectDialogOpen,
    setIsRewinding,
    isRewinding,
  });

  const statusPanelExpanded = !userCollapsedRef.current;

  // ── Render ──
  return (
    <>
      <ChatHeader
        currentView={currentView}
        sessionTitle={sessionTitle}
        t={t}
        onBack={() => setCurrentView('chat')}
        onNewSession={createNewSession}
        onNewTab={() => sendAction(UPSTREAM.CREATE_NEW_TAB)}
        onHistory={() => setCurrentView('history')}
        onSettings={() => {
          setSettingsInitialTab(undefined);
          setCurrentView('settings');
        }}
        onOpenSearch={() => setSearchOpen(true)}
        titleEditable
        onTitleChange={(newTitle: string) => {
          setCustomSessionTitle(newTitle);
          if (currentSessionId) {
            updateHistoryTitle(currentSessionId, newTitle);
          }
        }}
      />

      {currentView === 'settings' ? (
        <SettingsView
          onClose={() => setCurrentView('chat')}
          initialTab={settingsInitialTab}
          currentProvider={currentProvider}
          streamingEnabled={streamingEnabledSetting}
          onStreamingEnabledChange={handleStreamingEnabledChange}
          showThinkingEnabled={showThinkingEnabledSetting}
          onShowThinkingEnabledChange={handleShowThinkingEnabledChange}
          sendShortcut={sendShortcut}
          onSendShortcutChange={handleSendShortcutChange}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
          detailedOutputEnabled={detailedOutputEnabled}
          onDetailedOutputEnabledChange={handleDetailedOutputEnabledChange}
          permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
          onPermissionDialogTimeoutChange={setPermissionDialogTimeoutSeconds}
          avatarConfig={avatarConfig}
          onAssistantAvatarChange={setAssistantAvatarSelection}
          onUserAvatarChange={setUserAvatarSelection}
          onUploadAssistantAvatar={uploadAssistantAvatar}
          onUploadUserAvatar={uploadUserAvatar}
        />
      ) : currentView === 'chat' ? (
        <ModelProviderProvider
          value={{
            currentProvider,
            selectedModel,
            permissionMode,
            selectedAgent,
            sdkStatusLoaded,
            currentSdkInstalled,
            activeProviderConfig,
            reasoningEffort,
            streamingEnabledSetting,
            showThinkingEnabledSetting,
            sendShortcut,
            autoOpenFileEnabled,
            longContextEnabled,
            usagePercentage,
            usageUsedTokens,
            usageMaxTokens,
            tokenDetail,
            handleModeSelect,
            handleModelSelect,
            handleAgentSelect,
            handleReasoningChange,
            handleStreamingEnabledChange,
            handleShowThinkingEnabledChange,
            handleAutoOpenFileEnabledChange,
            handleLongContextChange,
          }}
        >
          <ChatScreen
            mergedMessages={mergedMessages}
            getMessageText={getMessageText}
            getContentBlocks={getContentBlocks}
            findToolResult={findToolResult}
            getToolResultRaw={getToolResultRaw}
            subagents={subagents}
            globalTodos={globalTodos}
            filteredFileChanges={filteredFileChanges}
            subagentHistoryCtxValue={subagentHistoryCtxValue}
            sessionIdCtxValue={sessionIdCtxValue}
            chatInputRef={chatInputRef}
            messagesContainerRef={messagesContainerRef}
            messagesEndRef={messagesEndRef}
            inputAreaRef={inputAreaRef}
            messageNodeMapRef={messageNodeMapRef}
            userCollapsedRef={userCollapsedRef}
            messageListRef={messageListRef}
            isAutoScrollingRef={isAutoScrollingRef}
            isUserAtBottomRef={isUserAtBottomRef}
            anchorCollapsedCount={anchorCollapsedCount}
            setAnchorCollapsedCount={setAnchorCollapsedCount}
            onMessageNodeRef={handleMessageNodeRef}
            statusPanelExpanded={statusPanelExpanded}
            forceStatusUpdate={forceStatusUpdate}
            onUndoFile={handleUndoFile}
            onDiscardAll={onDiscardAll}
            onKeepAll={handleKeepAll}
            onSubmit={handleSubmit}
            onInterrupt={interruptSession}
            onRewind={handleOpenRewindSelectDialog}
            onNavigateToProviderSettings={handleNavigateToProviderSettings}
            onProviderSelect={wrappedHandleProviderSelect}
            detailedOutputEnabled={detailedOutputEnabled}
            avatarConfig={avatarConfig}
            messageQueue={messageQueue}
            onRemoveFromQueue={dequeueMessage}
          />
        </ModelProviderProvider>
      ) : (
        <HistoryView
          historyData={historyData}
          currentProvider={currentProvider}
          onLoadSession={loadHistorySession}
          onDeleteSession={deleteHistorySession}
          onDeleteSessions={deleteHistorySessions}
          onArchiveSessions={archiveHistorySessions}
          onExportSession={exportHistorySession}
          onPrintSessionPdf={printSessionPdf}
          onToggleFavorite={toggleFavoriteSession}
          onUpdateTitle={updateHistoryTitle}
          onConvertToCliSession={convertToCliSession}
        />
      )}

      <div id="image-preview-root" />

      <AppDialogs
        showNewSessionConfirm={showNewSessionConfirm}
        onConfirmNewSession={handleConfirmNewSession}
        onCancelNewSession={handleCancelNewSession}
        showInterruptConfirm={showInterruptConfirm}
        onConfirmInterrupt={handleConfirmInterrupt}
        onCancelInterrupt={handleCancelInterrupt}
        rewindableMessages={rewindableMessages}
        onRewindSelect={handleRewindSelect}
        onRewindSelectCancel={handleRewindSelectCancel}
        onRewindConfirm={handleRewindConfirm}
        onRewindCancel={handleRewindCancel}
        permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
      />
    </>
  );
};

export default App;
