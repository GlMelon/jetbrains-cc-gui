import { type RefObject, useCallback, useEffect, useMemo, useRef } from 'react';
import type { TFunction } from 'i18next';
import type {
  ClaudeMessage,
  ClaudeRawMessage,
  ToolResultBlock,
} from '../types';
import type { GetToolResultRawFn } from '../contexts/SubagentContext';
import type { RewindableMessage } from '../components/RewindSelectDialog';
import { formatTime } from '../utils/helpers';
import { extractTodosFromToolUse, extractAccumulatedTasks } from '../utils/todoToolNormalization';
import {
  finalizeSubagentsForSettledTurn,
  finalizeTodosForSettledTurn,
  sliceLatestConversationTurn,
} from '../utils/turnScope';
import { PROVIDER_TYPE } from '../generated/protocol';
import { useSubagents } from './useSubagents';
import { useFileChanges } from './useFileChanges';
import { useFileChangesManagement } from './useFileChangesManagement';
import type { useMessageProcessing } from './useMessageProcessing';

interface UseChatComputationsParams {
  t: TFunction;
  messages: ClaudeMessage[];
  mergedMessages: ClaudeMessage[];
  customSessionTitle: string | null;
  streamingActive: boolean;
  currentProvider: string;
  currentSessionId: string | null;
  currentSessionIdRef: RefObject<string | null>;
  getMessageText: ReturnType<typeof useMessageProcessing>['getMessageText'];
  getContentBlocks: ReturnType<typeof useMessageProcessing>['getContentBlocks'];
}

/**
 * A8 标注(架构债登记簿 §A8):本函数是「展示 fallback 候选筛选」——仅当无
 * customSessionTitle(用户自定义/持久化标题,优先级更高)时,才从用户消息中筛选合适
 * 文本作为会话标题兜底(见 sessionTitle useMemo 的优先级链)。过滤 [tool_result] /
 * 非 human origin / 纯 tool_result 块属 UI 展示过滤,非业务语义判定。按 §A8 取向
 * 保留并标注为展示过滤;标题 SSOT 以 customSessionTitle 为准。
 */
export function isSessionTitleUserCandidate(message: ClaudeMessage): boolean {
  if (message.type !== 'user') return false;
  if ((message.content ?? '').trim() === '[tool_result]') return false;
  if (isInternalContextOnlyText(message.content)) return false;

  const raw = message.raw;
  if (!raw || typeof raw === 'string') return true;
  if (raw.origin?.kind && raw.origin.kind !== 'human') return false;

  const content = raw.content ?? raw.message?.content;
  if (!Array.isArray(content) || content.length === 0) return true;

  if (content.every((block) => block?.type === 'text' && isInternalContextOnlyText(block.text))) return false;
  return !content.every((block) => block && block.type === 'tool_result');
}

function isInternalContextOnlyText(text: string | undefined): boolean {
  const trimmed = text?.trim();
  if (!trimmed) return false;
  return trimmed.startsWith('## Opened Files Context')
    || trimmed.startsWith("## User's Current IDE Context")
    || trimmed.startsWith('## IDE Context')
    || trimmed.startsWith('## Workspace Context')
    || trimmed.startsWith('## Project Modules')
    || trimmed.startsWith('### Multi-Project Workspace Structure')
    || trimmed.startsWith('### Project Module Structure');
}

/**
 * Bundles all chat-view derived computations: tool result lookup table,
 * subagent extraction, todos, rewindable messages, file change filtering,
 * and session title.
 *
 * Stage 5 of TASK-P1-01 — moves ~120 lines of computation out of App.tsx.
 */
export function useChatComputations({
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
}: UseChatComputationsParams) {
  // Ref-backed scan over messages for tool_result blocks, with a per-id cache.
  const messagesRef = useRef(messages);
  messagesRef.current = messages;
  const toolResultRawMapRef = useRef<Map<string, ClaudeRawMessage>>(new Map());
  // 流式期冻结缓存:rewindableMessages / sessionTitle 不依赖流式增量内容(只依赖历史
  // 用户消息结构 / 首条用户消息),流式期间返回上次 settled 快照的相同引用,跳过重算 +
  // 避免触发下游消费组件(标题栏 / Rewind 列表)每帧重渲染。
  const prevRewindableRef = useRef<RewindableMessage[]>([]);
  const prevSessionTitleRef = useRef<string>('');
  const previousSessionIdRef = useRef<string | null>(currentSessionId);

  useEffect(() => {
    if (previousSessionIdRef.current === currentSessionId) return;
    previousSessionIdRef.current = currentSessionId;
    toolResultRawMapRef.current.clear();
    prevRewindableRef.current = [];
    prevSessionTitleRef.current = '';
  }, [currentSessionId]);

  const findToolResult = useCallback((toolUseId?: string, messageIndex?: number): ToolResultBlock | null => {
    if (!toolUseId || typeof messageIndex !== 'number') return null;
    const currentMessages = messagesRef.current;
    const cachedRaw = toolResultRawMapRef.current.get(toolUseId);
    if (cachedRaw != null) {
      const content = cachedRaw.content ?? cachedRaw.message?.content;
      if (Array.isArray(content)) {
        const hit = content.find(
          (block): block is ToolResultBlock =>
            Boolean(block) && block.type === 'tool_result' && block.tool_use_id === toolUseId,
        );
        if (hit) return hit;
      }
    }
    for (let i = 0; i < currentMessages.length; i += 1) {
      const candidate = currentMessages[i];
      const raw = candidate.raw;
      if (!raw || typeof raw === 'string') continue;
      const content = raw.content ?? raw.message?.content;
      if (!Array.isArray(content)) continue;
      const resultBlock = content.find(
        (block): block is ToolResultBlock =>
          Boolean(block) && block.type === 'tool_result' && block.tool_use_id === toolUseId,
      );
      if (resultBlock) {
        toolResultRawMapRef.current.set(toolUseId, raw);
        return resultBlock;
      }
    }
    return null;
  }, []);

  const getToolResultRaw = useCallback<GetToolResultRawFn>(
    (toolUseId: string) => toolResultRawMapRef.current.get(toolUseId) ?? null,
    [],
  );

  // File changes (depend on findToolResult which is now stable above).
  const fileChangeMgmt = useFileChangesManagement({
    currentSessionId, currentSessionIdRef, messages,
    getContentBlocks, findToolResult,
  });
  const fileChanges = useFileChanges({
    messages, getContentBlocks, findToolResult,
    startFromIndex: fileChangeMgmt.baseMessageIndex,
  });

  const filteredFileChanges = useMemo(() => {
    if (fileChangeMgmt.processedFiles.length === 0) return fileChanges;
    return fileChanges.filter((fc) => !fileChangeMgmt.processedFiles.includes(fc.filePath));
  }, [fileChanges, fileChangeMgmt.processedFiles]);

  const latestTurnMessages = useMemo(() => sliceLatestConversationTurn(messages), [messages]);

  const latestTurnSubagents = useSubagents({
    messages: latestTurnMessages,
    getContentBlocks,
    findToolResult,
    getToolResultRaw,
  });

  const subagents = useMemo(
    () => finalizeSubagentsForSettledTurn(latestTurnSubagents, streamingActive),
    [latestTurnSubagents, streamingActive],
  );

  const globalTodos = useMemo(() => {
    let latestTodos: ReturnType<typeof extractTodosFromToolUse> = null;
    for (let i = latestTurnMessages.length - 1; i >= 0; i--) {
      const msg = latestTurnMessages[i];
      if (msg.type !== 'assistant') continue;
      const blocks = getContentBlocks(msg);
      for (let j = blocks.length - 1; j >= 0; j--) {
        const todos = extractTodosFromToolUse(blocks[j]);
        if (todos && todos.length > 0) {
          latestTodos = todos;
          break;
        }
      }
      if (latestTodos) break;
    }
    if (latestTodos) {
      return finalizeTodosForSettledTurn(latestTodos, streamingActive, currentProvider);
    }
    const accumulated = extractAccumulatedTasks(messages, getContentBlocks);
    if (accumulated.length > 0) {
      return accumulated;
    }
    return [];
  }, [latestTurnMessages, messages, getContentBlocks, streamingActive]);

  const rewindableMessages = useMemo((): RewindableMessage[] => {
    // 流式期冻结:mergedMessages 每帧追加流式增量,但 rewindable 集合只依赖已 settled 的历史
    // 用户消息结构。返回上次 settled 快照(相同引用),避免每帧重算 + 触发消费组件重渲染。
    if (streamingActive) return prevRewindableRef.current;
    const result: RewindableMessage[] = [];
    if (currentProvider !== PROVIDER_TYPE.CLAUDE) {
      prevRewindableRef.current = result;
      return result;
    }
    for (let i = 0; i < mergedMessages.length - 1; i++) {
      const message = mergedMessages[i];
      if (message.type !== 'user') continue;
      if (!message.raw || typeof message.raw === 'string') continue;
      if (message.raw.rewindable !== true) continue;
      const content = message.content || getMessageText(message);
      const timestamp = message.timestamp ? formatTime(message.timestamp) : undefined;
      const messagesAfterCount = mergedMessages.length - i - 1;
      result.push({ messageIndex: i, message, displayContent: content, timestamp, messagesAfterCount });
    }
    prevRewindableRef.current = result;
    return result;
  }, [mergedMessages, currentProvider, getMessageText, streamingActive]);

  const sessionTitle = useMemo(() => {
    // 流式期冻结:标题取决于 customSessionTitle / 首条用户消息,与流式增量无关。
    if (streamingActive) return prevSessionTitleRef.current;
    let title: string;
    if (customSessionTitle) {
      title = customSessionTitle;
    } else if (messages.length === 0) {
      title = t('common.newSession');
    } else {
      const firstUserMessage = messages.find(isSessionTitleUserCandidate);
      if (!firstUserMessage) {
        title = t('common.newSession');
      } else {
        const text = getMessageText(firstUserMessage);
        title = text.length > 15 ? `${text.substring(0, 15)}...` : text;
      }
    }
    prevSessionTitleRef.current = title;
    return title;
  }, [customSessionTitle, messages, t, getMessageText, streamingActive]);

  return {
    findToolResult,
    getToolResultRaw,
    fileChangeMgmt,
    filteredFileChanges,
    subagents,
    globalTodos,
    rewindableMessages,
    sessionTitle,
  };
}

