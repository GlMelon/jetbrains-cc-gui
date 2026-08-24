/**
 * registerCallbacks.ts
 *
 * Single entry point that mounts all window bridge callbacks.  Called once
 * inside useWindowCallbacks' useEffect.  Receives the full options bag from
 * the hook rather than individual parameters to keep the call-site tidy.
 *
 * Pure functions have been extracted to messageSync.ts / sessionTransition.ts /
 * settingsBootstrap.ts; callback groups are further split into dedicated
 * sub-modules under registerCallbacks/ for easier navigation and maintenance.
 */

/** Declare the onTaskEvent callback mounted on window by the Java bridge. */
declare global {
  interface Window {
    onTaskEvent?: (eventJson: string) => void;
  }
}

import type { MutableRefObject } from 'react';
import type { UseWindowCallbacksOptions } from '../useWindowCallbacks';
import { parseTaskNotification } from '../../utils/taskEventParser';
import { deepEqual } from '../../utils/deepEqual';
import {
  setupSlashCommandsCallback,
  resetSlashCommandsState,
  resetFileReferenceState,
  setupDollarCommandsCallback,
  resetDollarCommandsState,
} from '../../components/ChatInputBox/providers';
import { buildResetTransientUiState } from './sessionTransition';
import { startActiveProviderRequest, startSessionRuntimeStateRequest } from './settingsBootstrap';
import { registerMessageCallbacks } from './registerCallbacks/messageCallbacks';
import { registerStreamingCallbacks } from './registerCallbacks/streamingCallbacks';
import { registerSessionAndSdkCallbacks } from './registerCallbacks/sessionCallbacks';
import { registerUsageModeCallbacks } from './registerCallbacks/usageModeCallbacks';
import { registerPermissionCallbacks } from './registerCallbacks/permissionCallbacks';
import { registerAgentAndSelectionCallbacks } from './registerCallbacks/agentCallbacks';
import { bridgeHub } from '../../bridge';
import { clearAllStreamScopeStates } from './streamScopeState';

function areSubagentMessagesEquivalent(
  previousMessages: unknown[] | undefined,
  nextMessages: unknown[] | undefined,
): boolean {
  if (previousMessages === nextMessages) return true;
  return deepEqual(previousMessages, nextMessages);
}

const pendingSubagentHistoryChunks = new Map<string, string[]>();
const MAX_PENDING_SUBAGENT_HISTORY_TRANSFERS = 16;
const MAX_PENDING_SUBAGENT_HISTORY_CHUNKS = 512;
const MAX_PENDING_SUBAGENT_HISTORY_CHARS = 4 * 1024 * 1024;
const PENDING_SUBAGENT_HISTORY_TIMEOUT_MS = 30_000;
const pendingSubagentHistoryTimers = new Map<string, ReturnType<typeof setTimeout>>();

function removePendingSubagentHistory(transferId: string): void {
  pendingSubagentHistoryChunks.delete(transferId);
  const timer = pendingSubagentHistoryTimers.get(transferId);
  if (timer != null) {
    clearTimeout(timer);
    pendingSubagentHistoryTimers.delete(transferId);
  }
}

function clearPendingSubagentHistoryChunks(): void {
  for (const timer of pendingSubagentHistoryTimers.values()) clearTimeout(timer);
  pendingSubagentHistoryTimers.clear();
  pendingSubagentHistoryChunks.clear();
}

function appendSubagentHistoryChunk(transferId: string, chunk: string, isFinal: string | boolean): void {
  if (!transferId) return;
  const chunks = pendingSubagentHistoryChunks.get(transferId) ?? [];
  if (chunks.length >= MAX_PENDING_SUBAGENT_HISTORY_CHUNKS
    || chunks.reduce((total, value) => total + value.length, 0) + chunk.length > MAX_PENDING_SUBAGENT_HISTORY_CHARS) {
    removePendingSubagentHistory(transferId);
    return;
  }
  chunks.push(chunk);
  if (isFinal === true || isFinal === 'true') {
    removePendingSubagentHistory(transferId);
    window.onSubagentHistoryLoaded?.(chunks.join(''));
    return;
  }
  if (pendingSubagentHistoryChunks.size >= MAX_PENDING_SUBAGENT_HISTORY_TRANSFERS) {
    const oldestTransferId = pendingSubagentHistoryChunks.keys().next().value;
    if (oldestTransferId) removePendingSubagentHistory(oldestTransferId);
  }
  pendingSubagentHistoryChunks.set(transferId, chunks);
  const existingTimer = pendingSubagentHistoryTimers.get(transferId);
  if (existingTimer != null) clearTimeout(existingTimer);
  pendingSubagentHistoryTimers.set(transferId, setTimeout(() => {
    removePendingSubagentHistory(transferId);
  }, PENDING_SUBAGENT_HISTORY_TIMEOUT_MS));
}

export function registerWindowCallbacks(
  options: UseWindowCallbacksOptions,
  tRef: MutableRefObject<UseWindowCallbacksOptions['t']>,
): () => void {
  const cleanupBridgeScope = bridgeHub.beginCleanupScope();
  // -------------------------------------------------------------------------
  // Session transition helpers
  // -------------------------------------------------------------------------

  const resetTransientUiState = buildResetTransientUiState({
    clearToasts: options.clearToasts,
    setStatus: options.setStatus,
    setLoading: options.setLoading,
    setLoadingStartTime: options.setLoadingStartTime,
    setIsThinking: options.setIsThinking,
    setStreamingActive: options.setStreamingActive,
    isStreamingRef: options.isStreamingRef,
    useBackendStreamingRenderRef: options.useBackendStreamingRenderRef,
    streamingMessageIndexRef: options.streamingMessageIndexRef,
    streamingContentRef: options.streamingContentRef,
    streamingThinkingRef: options.streamingThinkingRef,
    autoExpandedThinkingKeysRef: options.autoExpandedThinkingKeysRef,
    contentUpdateTimeoutRef: options.contentUpdateTimeoutRef,
    thinkingUpdateTimeoutRef: options.thinkingUpdateTimeoutRef,
    streamingTurnIdRef: options.streamingTurnIdRef,
    setSubagentHistories: options.setSubagentHistories,
    setTaskEvents: options.setTaskEvents,
    clearPendingSubagentHistoryChunks,
  });

  // Expose as single entry point for session transition cleanup.
  // beginSessionTransition (useSessionManagement) calls this to synchronously
  // clear both React state AND internal refs in one shot.
  window.__resetTransientUiState = resetTransientUiState;

  // =========================================================================
  // Register callback groups
  // =========================================================================

  registerMessageCallbacks(options, resetTransientUiState);
  registerStreamingCallbacks(options);
  registerSessionAndSdkCallbacks(options, tRef);
  registerUsageModeCallbacks(options);
  registerPermissionCallbacks(options);
  registerAgentAndSelectionCallbacks(options);

  window.onSubagentHistoryChunk = appendSubagentHistoryChunk;

  // 有界对象累积:超过 maxEntries 时按插入序丢弃最旧 key(对象 key 顺序即插入序)。
  // 轮询型注册表(subagentHistories/taskEvents)会话内只增不减,多后台 agent + 长
  // sidechain 时防无界增长;被逐出的条目下轮轮询回来时会被重新插入,无功能损失。
  const withBoundedEntries = <T extends Record<string, unknown>>(
    prev: T,
    key: string,
    value: T[keyof T],
    maxEntries: number,
  ): T => {
    const next = { ...prev, [key]: value };
    const keys = Object.keys(next);
    for (let i = 0; i < keys.length - maxEntries; i += 1) {
      delete next[keys[i]];
    }
    return next;
  };

  window.onSubagentHistoryLoaded = (json: string) => {
    try {
      if (!options.setSubagentHistories) return;
      const result = JSON.parse(json);
      const key = result.toolUseId || result.agentId;
      if (!key) return;
      options.setSubagentHistories((prev) => {
        const existing = prev[key];
        // Skip state update when the payload is structurally identical.
        // This prevents cascading re-renders and scroll jumps caused by
        // periodic subagent polling (every 2 s) returning unchanged data.
if (existing && existing.success === result.success
          && existing.completed === result.completed
          && existing.status === result.status
          && existing.error === result.error
          && existing.sessionId === result.sessionId
          && existing.provider === result.provider
          && existing.toolUseId === result.toolUseId
          && existing.agentId === result.agentId
          && existing.agentPath === result.agentPath
          && areSubagentMessagesEquivalent(existing.messages, result.messages)) {
          return prev;
        }
        // 条目含全量 messages,单条可达数百 KB;64 个后台 sidechain 已远超实际 UI 展示需求
        return withBoundedEntries(prev, key, result, 64);
      });
    } catch {
      // Ignore malformed callback payloads; the request can be retried by reopening the Agent row.
    }
  };

  // task_* SDK system events signal the lifecycle of a background Agent
  // (Agent/Task tool invoked with run_in_background:true). Only
  // task_notification carries a terminal status; task_started / task_progress
  // merely announce progress and must not flip the running state. Without
  // this, the StatusPanel cannot tell a launched async agent from a finished
  // one, and the completion summary never surfaces.
  //
  // Cross-session safety is enforced by three layers, so this handler does
  // not re-check sessionId (which is not part of the taskEvent payload):
  //   1. Java ClaudeChatWindow.titleEventListener drops events whose sessionId
  //      does not match the active session.
  //   2. beginSessionTransition (useSessionManagement) clears taskEvents on
  //      session switch, so stale entries from the prior session cannot linger.
  //   3. tool_use_ids are globally unique, so even a delayed event can only
  //      update the agent it was emitted for, never mislabel another.
  window.onTaskEvent = (eventJson: string) => {
    try {
      if (!options.setTaskEvents) return;
      const taskEvent = parseTaskNotification(JSON.parse(eventJson));
      if (!taskEvent) return;
      const { toolUseId } = taskEvent;
      options.setTaskEvents((prev) => {
        const existing = prev[toolUseId];
        // Dedup: skip the state update when no observable field changed. Include
        // agentId/outputFilePath so a follow-up event that adds the sidechain
        // transcript path still lands (task_notification is terminal, but a
        // late output_file attachment would otherwise be swallowed).
        if (
          existing &&
          existing.status === taskEvent.status &&
          existing.summary === taskEvent.summary &&
          existing.totalTokens === taskEvent.totalTokens &&
          existing.totalToolUseCount === taskEvent.totalToolUseCount &&
          existing.totalDurationMs === taskEvent.totalDurationMs &&
          existing.agentId === taskEvent.agentId &&
          existing.outputFilePath === taskEvent.outputFilePath
        ) {
          return prev;
        }
        // 条目小(纯状态摘要),上限放宽
        return withBoundedEntries(prev, toolUseId, taskEvent, 128);
      });
    } catch {
      // Ignore malformed task event payloads. A task_notification is terminal,
      // so a dropped event is not retried - but a later task_progress /
      // task_notification for the same tool_use_id will still land and update
      // the entry, so the subagent list is not permanently stuck.
    }
  };

  // =========================================================================
  // Slash Commands Setup
  // =========================================================================

  resetSlashCommandsState();
  resetDollarCommandsState();
  resetFileReferenceState();
  setupSlashCommandsCallback();
  setupDollarCommandsCallback();

  // =========================================================================
  // Request Initial States
  // =========================================================================

  startActiveProviderRequest();
  startSessionRuntimeStateRequest();

  return () => {
    cleanupBridgeScope();
    clearPendingSubagentHistoryChunks();
    window.__cancelPendingUpdateMessages?.();
    if (window.__stallWatchdogInterval != null) {
      clearInterval(window.__stallWatchdogInterval);
      window.__stallWatchdogInterval = null;
    }
    if (window.__streamingDeltaRenderingFrame != null) {
      cancelAnimationFrame(window.__streamingDeltaRenderingFrame);
      window.__streamingDeltaRenderingFrame = undefined;
    }
    window.__deniedToolIds?.clear();
    clearAllStreamScopeStates();
    window.__resetTransientUiState = undefined;
    window.onSubagentHistoryChunk = undefined;
    window.onSubagentHistoryLoaded = undefined;
    window.onTaskEvent = undefined;
  };
}
