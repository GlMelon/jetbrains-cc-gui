import type { MutableRefObject } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClaudeMessage } from '../../../types';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import {
  registerStreamingCallbacks,
  isOpencodeProvider,
  scheduleOpencodeHistoryRefresh,
  OPENCODE_HISTORY_REFRESH_DELAY_MS,
} from '../registerCallbacks/streamingCallbacks';

const ref = <T>(value: T): MutableRefObject<T> => ({ current: value });

describe('streamingCallbacks block reset', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__sessionTransitioning = false;
    window.__activeStreamScopeKey = null;
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb: FrameRequestCallback) => {
      const id = window.setTimeout(() => cb(performance.now()), 0);
      return id as unknown as number;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id: number) => {
      clearTimeout(id);
    });
  });

  it('starts a new assistant card after block reset instead of appending to the previous card', () => {
    let messages: ClaudeMessage[] = [];
    const streamingContentRef = ref('');
    const streamingThinkingRef = ref('');
    const isStreamingRef = ref(false);
    const streamingMessageIndexRef = ref(-1);
    const streamingTurnIdRef = ref(0);
    const turnIdCounterRef = ref(0);

    const setMessages = (updater: ClaudeMessage[] | ((prev: ClaudeMessage[]) => ClaudeMessage[])) => {
      messages = typeof updater === 'function' ? updater(messages) : updater;
    };

    const options = {
      setMessages,
      setStreamingActive: vi.fn(),
      setLoading: vi.fn(),
      setLoadingStartTime: vi.fn(),
      setIsThinking: vi.fn(),
      setExpandedThinking: vi.fn(),
      streamingContentRef,
      streamingThinkingRef,
      isStreamingRef,
      useBackendStreamingRenderRef: ref(false),
      autoExpandedThinkingKeysRef: ref(new Set<string>()),
      streamingMessageIndexRef,
      streamingTurnIdRef,
      turnIdCounterRef,
      lastContentUpdateRef: ref(0),
      contentUpdateTimeoutRef: ref<number | null>(null),
      lastThinkingUpdateRef: ref(0),
      thinkingUpdateTimeoutRef: ref<number | null>(null),
      getOrCreateStreamingAssistantIndex: (items: ClaudeMessage[]) => {
        if (streamingMessageIndexRef.current >= 0) {
          return streamingMessageIndexRef.current;
        }
        items.push({
          type: 'assistant',
          content: '',
          timestamp: new Date().toISOString(),
          isStreaming: true,
          __turnId: streamingTurnIdRef.current,
        });
        streamingMessageIndexRef.current = items.length - 1;
        return streamingMessageIndexRef.current;
      },
      patchAssistantForStreaming: (message: ClaudeMessage): ClaudeMessage => ({
        ...message,
        content: streamingContentRef.current || message.content,
        isStreaming: true,
        __turnId: streamingTurnIdRef.current,
      }),
      addToast: vi.fn(),
      t: (key: string) => key,
      currentProviderRef: ref('claude'),
      currentSessionIdRef: ref('session-a'),
    } as unknown as UseWindowCallbacksOptions;

    registerStreamingCallbacks(options);

    window.onStreamStart?.();
    window.onContentDelta?.('第一段');
    vi.runOnlyPendingTimers();
    window.onBlockReset?.();
    window.onContentDelta?.('第二段');
    vi.runOnlyPendingTimers();

    const assistantMessages = messages.filter((message) => message.type === 'assistant');
    expect(assistantMessages).toHaveLength(2);
    expect(assistantMessages[0].content).toBe('第一段');
    expect(assistantMessages[1].content).toBe('第二段');
    expect(assistantMessages[0].__turnId).not.toBe(assistantMessages[1].__turnId);
    expect(assistantMessages[0].__responseId).toBeTruthy();
    expect(assistantMessages[1].__responseId).toBe(assistantMessages[0].__responseId);
  });

  it('marks block-reset assistant placeholders to suppress repeated connection hints', () => {
    let messages: ClaudeMessage[] = [];
    const streamingContentRef = ref('');
    const streamingThinkingRef = ref('');
    const isStreamingRef = ref(false);
    const streamingMessageIndexRef = ref(-1);
    const streamingTurnIdRef = ref(0);
    const turnIdCounterRef = ref(0);

    const setMessages = (updater: ClaudeMessage[] | ((prev: ClaudeMessage[]) => ClaudeMessage[])) => {
      messages = typeof updater === 'function' ? updater(messages) : updater;
    };

    const options = {
      setMessages,
      setStreamingActive: vi.fn(),
      setLoading: vi.fn(),
      setLoadingStartTime: vi.fn(),
      setIsThinking: vi.fn(),
      setExpandedThinking: vi.fn(),
      streamingContentRef,
      streamingThinkingRef,
      isStreamingRef,
      useBackendStreamingRenderRef: ref(false),
      autoExpandedThinkingKeysRef: ref(new Set<string>()),
      streamingMessageIndexRef,
      streamingTurnIdRef,
      turnIdCounterRef,
      lastContentUpdateRef: ref(0),
      contentUpdateTimeoutRef: ref<number | null>(null),
      lastThinkingUpdateRef: ref(0),
      thinkingUpdateTimeoutRef: ref<number | null>(null),
      getOrCreateStreamingAssistantIndex: (items: ClaudeMessage[]) => {
        if (streamingMessageIndexRef.current >= 0) {
          return streamingMessageIndexRef.current;
        }
        items.push({
          type: 'assistant',
          content: '',
          timestamp: new Date().toISOString(),
          isStreaming: true,
          __turnId: streamingTurnIdRef.current,
        });
        streamingMessageIndexRef.current = items.length - 1;
        return streamingMessageIndexRef.current;
      },
      patchAssistantForStreaming: (message: ClaudeMessage): ClaudeMessage => ({
        ...message,
        content: streamingContentRef.current || message.content,
        isStreaming: true,
        __turnId: streamingTurnIdRef.current,
      }),
      addToast: vi.fn(),
      t: (key: string) => key,
      currentProviderRef: ref('claude'),
      currentSessionIdRef: ref('session-a'),
    } as unknown as UseWindowCallbacksOptions;

    registerStreamingCallbacks(options);

    window.onStreamStart?.();
    window.onBlockReset?.();

    expect(messages).toHaveLength(2);
    expect(messages[0].__suppressStreamingConnectHint).toBeUndefined();
    expect(messages[1].__suppressStreamingConnectHint).toBe(true);
  });

  it('keeps tool-use snapshots on the new assistant card after block reset', () => {
    let messages: ClaudeMessage[] = [];
    const streamingContentRef = ref('');
    const streamingThinkingRef = ref('');
    const isStreamingRef = ref(false);
    const streamingMessageIndexRef = ref(-1);
    const streamingTurnIdRef = ref(0);
    const turnIdCounterRef = ref(0);

    const setMessages = (updater: ClaudeMessage[] | ((prev: ClaudeMessage[]) => ClaudeMessage[])) => {
      messages = typeof updater === 'function' ? updater(messages) : updater;
    };

    const options = {
      setMessages,
      setStreamingActive: vi.fn(),
      setLoading: vi.fn(),
      setLoadingStartTime: vi.fn(),
      setIsThinking: vi.fn(),
      setExpandedThinking: vi.fn(),
      streamingContentRef,
      streamingThinkingRef,
      isStreamingRef,
      useBackendStreamingRenderRef: ref(false),
      autoExpandedThinkingKeysRef: ref(new Set<string>()),
      streamingMessageIndexRef,
      streamingTurnIdRef,
      turnIdCounterRef,
      lastContentUpdateRef: ref(0),
      contentUpdateTimeoutRef: ref<number | null>(null),
      lastThinkingUpdateRef: ref(0),
      thinkingUpdateTimeoutRef: ref<number | null>(null),
      getOrCreateStreamingAssistantIndex: (items: ClaudeMessage[]) => {
        if (streamingMessageIndexRef.current >= 0) {
          return streamingMessageIndexRef.current;
        }
        items.push({
          type: 'assistant',
          content: '',
          timestamp: new Date().toISOString(),
          isStreaming: true,
          __turnId: streamingTurnIdRef.current,
        });
        streamingMessageIndexRef.current = items.length - 1;
        return streamingMessageIndexRef.current;
      },
      patchAssistantForStreaming: (message: ClaudeMessage): ClaudeMessage => ({
        ...message,
        content: streamingContentRef.current || message.content,
        isStreaming: true,
        __turnId: streamingTurnIdRef.current,
      }),
      addToast: vi.fn(),
      t: (key: string) => key,
      currentProviderRef: ref('claude'),
      currentSessionIdRef: ref('session-a'),
    } as unknown as UseWindowCallbacksOptions;

    registerStreamingCallbacks(options);

    window.onStreamStart?.();
    window.onContentDelta?.('第一段');
    vi.runOnlyPendingTimers();
    window.onBlockReset?.();

    const activeTurnId = streamingTurnIdRef.current;
    setMessages((prev) => {
      const next = [...prev];
      next[1] = {
        ...next[1],
        content: '',
        raw: {
          message: {
            content: [
              { type: 'tool_use', id: 'tool-1', name: 'Edit', input: { file_path: 'Plant.java' } },
            ],
          },
        },
        __turnId: activeTurnId,
      };
      return next;
    });

    const secondRaw = messages[1].raw as { message?: { content?: Array<{ type?: string; id?: string }> } };
    expect(messages[1].content).toBe('');
    expect(messages[1].__turnId).toBe(activeTurnId);
    expect(secondRaw.message?.content?.[0]).toMatchObject({ type: 'tool_use', id: 'tool-1' });
  });

  it('finalizes previous response segments when a later segment ends', () => {
    let messages: ClaudeMessage[] = [];
    const streamingContentRef = ref('');
    const streamingThinkingRef = ref('');
    const isStreamingRef = ref(false);
    const streamingMessageIndexRef = ref(-1);
    const streamingTurnIdRef = ref(0);
    const turnIdCounterRef = ref(0);

    const setMessages = (updater: ClaudeMessage[] | ((prev: ClaudeMessage[]) => ClaudeMessage[])) => {
      messages = typeof updater === 'function' ? updater(messages) : updater;
    };

    const options = {
      setMessages,
      setStreamingActive: vi.fn(),
      setLoading: vi.fn(),
      setLoadingStartTime: vi.fn(),
      setIsThinking: vi.fn(),
      setExpandedThinking: vi.fn(),
      streamingContentRef,
      streamingThinkingRef,
      isStreamingRef,
      useBackendStreamingRenderRef: ref(false),
      autoExpandedThinkingKeysRef: ref(new Set<string>()),
      streamingMessageIndexRef,
      streamingTurnIdRef,
      turnIdCounterRef,
      lastContentUpdateRef: ref(0),
      contentUpdateTimeoutRef: ref<number | null>(null),
      lastThinkingUpdateRef: ref(0),
      thinkingUpdateTimeoutRef: ref<number | null>(null),
      getOrCreateStreamingAssistantIndex: (items: ClaudeMessage[]) => {
        if (streamingMessageIndexRef.current >= 0) {
          return streamingMessageIndexRef.current;
        }
        items.push({
          type: 'assistant',
          content: '',
          timestamp: new Date().toISOString(),
          isStreaming: true,
          __turnId: streamingTurnIdRef.current,
        });
        streamingMessageIndexRef.current = items.length - 1;
        return streamingMessageIndexRef.current;
      },
      patchAssistantForStreaming: (message: ClaudeMessage): ClaudeMessage => ({
        ...message,
        content: streamingContentRef.current || message.content,
        isStreaming: message.isStreaming,
        __turnId: streamingTurnIdRef.current,
      }),
      addToast: vi.fn(),
      t: (key: string) => key,
      currentProviderRef: ref('claude'),
      currentSessionIdRef: ref('session-a'),
    } as unknown as UseWindowCallbacksOptions;

    registerStreamingCallbacks(options);

    window.onStreamStart?.();
    window.onContentDelta?.('第一段');
    vi.runOnlyPendingTimers();
    window.onBlockReset?.();
    window.onContentDelta?.('第二段');
    vi.runOnlyPendingTimers();
    window.onStreamEnd?.();

    const assistantMessages = messages.filter((message) => message.type === 'assistant');
    expect(assistantMessages).toHaveLength(2);
    expect(assistantMessages.map((message) => message.content)).toEqual(['第一段', '第二段']);
    expect(assistantMessages.map((message) => message.isStreaming)).toEqual([false, false]);
    expect(assistantMessages[1].__responseId).toBe(assistantMessages[0].__responseId);
  });
});

// 问题2c:OpenCode stream_end 后延迟二次刷新历史列表(兜底)。主修=ai-bridge getSessionList 换
// node:sqlite(WAL 感知);此处防 serve 写 SQLite(session row/time_updated)与 stream_end SSE
// 事件的竞态 + WAL checkpoint 时机不定。Claude/Codex 走缓存即时刷新,无需延迟兜底。
describe('opencode 延迟二次历史刷新 (问题2c)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  it('isOpencodeProvider 仅匹配 opencode provider', () => {
    expect(isOpencodeProvider('opencode')).toBe(true);
    expect(isOpencodeProvider('claude')).toBe(false);
    expect(isOpencodeProvider('codex')).toBe(false);
    expect(isOpencodeProvider(undefined)).toBe(false);
    expect(isOpencodeProvider(null)).toBe(false);
  });

  it('scheduleOpencodeHistoryRefresh 对 opencode 延迟调用刷新动作', () => {
    const refresh = vi.fn();
    scheduleOpencodeHistoryRefresh('opencode', refresh);
    expect(refresh).not.toHaveBeenCalled();
    // 未到延迟时间不触发
    vi.advanceTimersByTime(OPENCODE_HISTORY_REFRESH_DELAY_MS - 1);
    expect(refresh).not.toHaveBeenCalled();
    // 到达延迟时间触发一次
    vi.advanceTimersByTime(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('scheduleOpencodeHistoryRefresh 对非 opencode provider 不调度刷新', () => {
    const refresh = vi.fn();
    scheduleOpencodeHistoryRefresh('claude', refresh);
    vi.advanceTimersByTime(OPENCODE_HISTORY_REFRESH_DELAY_MS + 5000);
    expect(refresh).not.toHaveBeenCalled();
  });

  it('scheduleOpencodeHistoryRefresh 重入时清旧建新,避免累积多个 pending 刷新', () => {
    const refreshA = vi.fn();
    const refreshB = vi.fn();
    scheduleOpencodeHistoryRefresh('opencode', refreshA);
    // 第二次调度应取消第一次的 pending(模拟连续多 turn / 重复 stream_end)
    scheduleOpencodeHistoryRefresh('opencode', refreshB);
    vi.advanceTimersByTime(OPENCODE_HISTORY_REFRESH_DELAY_MS + 5000);
    expect(refreshA).not.toHaveBeenCalled();
    expect(refreshB).toHaveBeenCalledTimes(1);
  });
});
