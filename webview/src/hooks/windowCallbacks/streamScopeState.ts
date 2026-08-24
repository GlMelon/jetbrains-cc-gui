type StreamScopeState = {
  content: string;
  thinking: string;
  messageIndex: number;
  isStreaming: boolean;
  backendRendering: boolean;
  pendingUpdateJson: string | null;
  pendingUpdateSequence: number | null;
  pendingUpdateRaf: number | null;
  lastActivityAt: number;
  minAcceptedSequence: number;
};

const streamScopeStates = new Map<string, StreamScopeState>();

// 上限兜底:正常清理是事件驱动的(turn 结束走 onStreamEnd、会话切换走 clearAll),
// 若 STREAM_END 事件彻底丢失且 stall watchdog 又被竞态清掉,entry 连同整 turn 文本
// 会永久驻留。超过上限时逐出「非流式中且非 active」的最旧 entry(按 lastActivityAt);
// 流式中的绝不逐出——宁可暂时超上限也不打断活跃流。
const MAX_STREAM_SCOPE_STATES = 32;

const evictStaleStreamScopes = (): void => {
  while (streamScopeStates.size >= MAX_STREAM_SCOPE_STATES) {
    let oldestKey: string | null = null;
    let oldestActivityAt = Infinity;
    for (const [key, state] of streamScopeStates) {
      if (state.isStreaming || key === getActiveStreamScopeKey()) {
        continue;
      }
      if (state.lastActivityAt < oldestActivityAt) {
        oldestActivityAt = state.lastActivityAt;
        oldestKey = key;
      }
    }
    if (oldestKey == null) {
      return;
    }
    clearStreamScopeState(oldestKey);
  }
};

const normalizeScopeKey = (value: string | null | undefined): string | null => {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

const normalizePart = (value: string | null | undefined): string => {
  if (typeof value !== 'string') {
    return 'default';
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : 'default';
};

export const getStreamScopeKey = (
  provider: string,
  tabId: string | null | undefined,
  turnId: number,
): string => `${normalizePart(provider)}:${normalizePart(tabId)}:${turnId}`;

export const getActiveStreamScopeKey = (): string | null => normalizeScopeKey(window.__activeStreamScopeKey);

export const setActiveStreamScopeKey = (scopeKey: string | null | undefined): void => {
  window.__activeStreamScopeKey = normalizeScopeKey(scopeKey);
};

export const getOrCreateStreamScopeState = (scopeKey: string): StreamScopeState => {
  const existing = streamScopeStates.get(scopeKey);
  if (existing) {
    return existing;
  }
  evictStaleStreamScopes();
  const state: StreamScopeState = {
    content: '',
    thinking: '',
    messageIndex: -1,
    isStreaming: false,
    backendRendering: false,
    pendingUpdateJson: null,
    pendingUpdateSequence: null,
    pendingUpdateRaf: null,
    lastActivityAt: 0,
    minAcceptedSequence: 0,
  };
  streamScopeStates.set(scopeKey, state);
  return state;
};

export const getStreamScopeState = (scopeKey: string | null | undefined): StreamScopeState | null => {
  const normalizedScopeKey = normalizeScopeKey(scopeKey);
  if (!normalizedScopeKey) {
    return null;
  }
  return streamScopeStates.get(normalizedScopeKey) ?? null;
};

export const cancelScopedPendingUpdate = (scopeKey: string | null | undefined): void => {
  const state = getStreamScopeState(scopeKey);
  if (state?.pendingUpdateRaf != null) {
    cancelAnimationFrame(state.pendingUpdateRaf);
    state.pendingUpdateRaf = null;
  }
};

export const clearStreamScopeState = (scopeKey: string | null | undefined): void => {
  const normalizedScopeKey = normalizeScopeKey(scopeKey);
  if (!normalizedScopeKey) {
    return;
  }
  const state = streamScopeStates.get(normalizedScopeKey);
  if (state?.pendingUpdateRaf != null) {
    cancelAnimationFrame(state.pendingUpdateRaf);
  }
  streamScopeStates.delete(normalizedScopeKey);
  if (getActiveStreamScopeKey() === normalizedScopeKey) {
    window.__activeStreamScopeKey = null;
  }
};

/** Clear every stream scope and cancel all queued animation frames. */
export const clearAllStreamScopeStates = (): void => {
  for (const state of streamScopeStates.values()) {
    if (state.pendingUpdateRaf != null) {
      cancelAnimationFrame(state.pendingUpdateRaf);
      state.pendingUpdateRaf = null;
    }
  }
  streamScopeStates.clear();
  window.__activeStreamScopeKey = null;
};

export const queueScopedPendingUpdate = (
  scopeKey: string | null | undefined,
  json: string,
  sequence: number | null,
): void => {
  const state = getStreamScopeState(scopeKey);
  if (!state) {
    return;
  }
  state.pendingUpdateJson = json;
  state.pendingUpdateSequence = sequence;
  state.lastActivityAt = Date.now();
};

export const consumeScopedPendingUpdate = (
  scopeKey: string | null | undefined,
): { json: string | null; sequence: number | null } => {
  const state = getStreamScopeState(scopeKey);
  if (!state) {
    return { json: null, sequence: null };
  }
  const result = {
    json: state.pendingUpdateJson,
    sequence: state.pendingUpdateSequence,
  };
  state.pendingUpdateJson = null;
  state.pendingUpdateSequence = null;
  return result;
};

export const markScopeActivity = (scopeKey: string | null | undefined): void => {
  const state = getStreamScopeState(scopeKey);
  if (state) {
    state.lastActivityAt = Date.now();
  }
};

export const getScopeLastActivityAt = (scopeKey: string | null | undefined): number => {
  const state = getStreamScopeState(scopeKey);
  return state?.lastActivityAt ?? 0;
};
