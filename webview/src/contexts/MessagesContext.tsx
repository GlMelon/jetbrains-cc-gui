import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import type { ClaudeMessage, SubagentHistoryResponse } from '../types';
import { trimMessagesToRetentionLimit } from '../utils/messageRetention';

export const DEFAULT_STATUS = 'ready';
export type QueueDisplayState = 'NONE' | 'QUEUED' | 'PROCESSING' | 'COMPLETED';

interface MessagesContextValue {
  messages: ClaudeMessage[];
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  subagentHistories: Record<string, SubagentHistoryResponse>;
  setSubagentHistories: React.Dispatch<React.SetStateAction<Record<string, SubagentHistoryResponse>>>;
  status: string;
  setStatus: React.Dispatch<React.SetStateAction<string>>;
  loading: boolean;
  setLoading: React.Dispatch<React.SetStateAction<boolean>>;
  loadingStartTime: number | null;
  setLoadingStartTime: React.Dispatch<React.SetStateAction<number | null>>;
  queueDisplayState: QueueDisplayState;
  setQueueDisplayState: React.Dispatch<React.SetStateAction<QueueDisplayState>>;
  queueAheadCount: number;
  setQueueAheadCount: React.Dispatch<React.SetStateAction<number>>;
  isThinking: boolean;
  setIsThinking: React.Dispatch<React.SetStateAction<boolean>>;
  streamingActive: boolean;
  setStreamingActive: React.Dispatch<React.SetStateAction<boolean>>;
}

const MessagesContext = createContext<MessagesContextValue | null>(null);

/**
 * Provides messages flow state (messages, subagent histories, loading, streaming).
 * Stage 1 of TASK-P1-01 (App.tsx God Component decomposition).
 *
 * task_events live in SubagentContext's TaskEventProvider, NOT here, so that
 * task_event updates do not invalidate this context's value and re-render
 * every consumer of it.
 *
 * Currently only App.tsx consumes this context. As subsequent stages migrate
 * downstream hooks (useWindowCallbacks, useMessageSender, useSessionManagement)
 * to read setters via useMessages() directly, prop drilling will collapse.
 */
export function MessagesProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<ClaudeMessage[]>([]);
  const [subagentHistories, setSubagentHistories] = useState<Record<string, SubagentHistoryResponse>>({});
  const [status, setStatus] = useState<string>(DEFAULT_STATUS);
  const [loading, setLoading] = useState<boolean>(false);
  const [loadingStartTime, setLoadingStartTime] = useState<number | null>(null);
  const [queueDisplayState, setQueueDisplayState] = useState<QueueDisplayState>('NONE');
  const [queueAheadCount, setQueueAheadCount] = useState<number>(0);
  const [isThinking, setIsThinking] = useState<boolean>(false);
  const [streamingActive, setStreamingActive] = useState<boolean>(false);

  // streamingActive 的同步镜像:setStreamingActive 是 React state,同批次内
  // setMessages updater 执行时读不到新值;streamingMessageIndexRef 等持有数组下标,
  // 流式期间 head-crop 会使其失配,因此裁剪以这个同步 ref 为闸门。
  const streamingActiveRef = useRef(false);
  const setStreamingActiveGuarded = useCallback<React.Dispatch<React.SetStateAction<boolean>>>(
    (value) => {
      streamingActiveRef.current =
        typeof value === 'function'
          ? (value as (prev: boolean) => boolean)(streamingActiveRef.current)
          : value;
      setStreamingActive(value);
    },
    [],
  );

  // 驻留上限裁剪(展示层资源管理):在唯一出口收口,所有 setMessages 写入路径
  // 统一经过。未超限 / 流式中时原样透传,引用与行为均不变。
  const setMessagesCapped = useCallback<React.Dispatch<React.SetStateAction<ClaudeMessage[]>>>(
    (value) => {
      setMessages((prev) => {
        const next =
          typeof value === 'function'
            ? (value as (p: ClaudeMessage[]) => ClaudeMessage[])(prev)
            : value;
        if (streamingActiveRef.current) return next;
        return trimMessagesToRetentionLimit(next);
      });
    },
    [],
  );

  const value = useMemo<MessagesContextValue>(
    () => ({
      messages,
      setMessages: setMessagesCapped,
      subagentHistories,
      setSubagentHistories,
      status,
      setStatus,
      loading,
      setLoading,
      loadingStartTime,
      setLoadingStartTime,
      queueDisplayState,
      setQueueDisplayState,
      queueAheadCount,
      setQueueAheadCount,
      isThinking,
      setIsThinking,
      streamingActive,
      setStreamingActive: setStreamingActiveGuarded,
    }),
    [messages, setMessagesCapped, subagentHistories, status, loading, loadingStartTime, queueDisplayState, queueAheadCount, isThinking, streamingActive, setStreamingActiveGuarded],
  );

  return <MessagesContext.Provider value={value}>{children}</MessagesContext.Provider>;
}

/**
 * Read messages flow state. Must be used within MessagesProvider.
 *
 * Note: this hook returns the full context value. Components that re-render
 * frequently and only need a subset (e.g. only `messages`) should consider
 * splitting into focused selector hooks if profiling shows pressure.
 */
export function useMessages(): MessagesContextValue {
  const ctx = useContext(MessagesContext);
  if (ctx === null) {
    throw new Error('useMessages must be used within a MessagesProvider');
  }
  return ctx;
}

export { MessagesContext };
