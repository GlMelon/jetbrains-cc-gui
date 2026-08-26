import {
  memo,
  useState,
  useEffect,
  useRef,
  useMemo,
  useCallback,
  forwardRef,
  useImperativeHandle,
} from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, ClaudeContentBlock, ToolResultBlock } from '../types';
import type { QueueDisplayState } from '../contexts/MessagesContext';
import { clearMessageKeyAliases, getMessageKey } from '../utils/messageUtils';
import { extractMessageUsage } from '../utils/messageUsage';
import { MessageItem, CopyButton } from './MessageItem';
import { FadeContent } from './react-bits';
import { MessageAvatar } from './MessageItem/MessageAvatar';
import { MessageUsageStats } from './MessageItem/MessageUsageStats';
import { AssistantStreamingFooter } from './MessageItem/AssistantStreamingFooter';
import { hasAssistantVisibleOutput } from './MessageItem/assistantTextOutput';
import { useStreamingAnnouncement } from './shared/useStreamingAnnouncement';
import WaitingIndicator from './WaitingIndicator';
import { ContextMenu } from './ContextMenu';
import { useContextMenu, copySelection } from '../hooks/useContextMenu.js';
import { copyToClipboard } from '../utils/copyUtils';
import { getProviderDisplayName } from '../utils/providerLabel';
import type { MessageListRevealHandle } from './ConversationSearch/types';
import type { AvatarConfig } from '../types/avatar';
import { sendAction, subscribeEvent } from '../bridge/typed';
import {
  CODEX_HISTORY_PAGE_SIZE,
  DOWNSTREAM,
  PROVIDER_TYPE,
  UPSTREAM,
  type CodexHistoryPageErrorPayloadWire,
  type CodexHistoryPageInfoPayloadWire,
  type CodexHistoryPageRequestPayloadWire,
} from '../generated/protocol';

/** Keep pagination aligned to complete user turns so assistant/tool chains are never split. */
const INITIAL_VISIBLE_TURNS = 5;
const REVEAL_TURN_PAGE_SIZE = 5;

function parseBridgePayload<T>(payload: unknown): T | null {
  if (typeof payload === 'string') {
    try {
      return JSON.parse(payload) as T;
    } catch {
      return null;
    }
  }
  if (payload && typeof payload === 'object') {
    return payload as T;
  }
  return null;
}

function isHumanUserMessage(message: ClaudeMessage): boolean {
  if (message.type !== 'user') return false;

  const raw = typeof message.raw === 'object' && message.raw !== null ? message.raw : null;
  const nestedMessage = raw?.message;
  const rawContent =
    raw?.content ??
    (typeof nestedMessage === 'object' && nestedMessage !== null
      ? nestedMessage.content
      : undefined);

  if (Array.isArray(rawContent)) {
    return rawContent.some(
      (block) =>
        block && typeof block === 'object' && (block.type === 'text' || block.type === 'image'),
    );
  }

  return message.content !== '[tool_result]';
}

function getFirstMessageBoundaryKey(message: ClaudeMessage | undefined): string | undefined {
  if (!message) return undefined;
  if (typeof message.id === 'string') return `id:${message.id}`;
  if (
    typeof message.raw === 'object' &&
    message.raw !== null &&
    typeof message.raw.uuid === 'string'
  ) {
    return `uuid:${message.raw.uuid}`;
  }
  if (message.timestamp) return `timestamp:${message.type}:${message.timestamp}`;
  return `content:${message.type}:${message.content ?? ''}`;
}

function getStreamingTurnBoundaryKey(
  messages: ClaudeMessage[],
  currentSessionId?: string | null,
): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (isHumanUserMessage(messages[index])) {
      return `${currentSessionId ?? 'no-session'}:${getFirstMessageBoundaryKey(messages[index])}`;
    }
  }
  return `${currentSessionId ?? 'no-session'}:empty`;
}

function getLatestAssistantResponseText(
  messages: ClaudeMessage[],
  getMessageText: (message: ClaudeMessage) => string,
): string {
  let turnStart = 0;
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    if (isHumanUserMessage(messages[index])) {
      turnStart = index + 1;
      break;
    }
  }

  return messages
    .slice(turnStart)
    .filter((message) => message.type === 'assistant')
    .map((message) => getMessageText(message).trim())
    .filter(Boolean)
    .join('\n')
    .trim();
}

/**
 * Tracks card keys (group responseId / single message key) that have already
 * played their entry (FadeContent) animation. A card animates ONLY on its first
 * logical appearance; subsequent re-renders — including any React remount from
 * streaming structural changes — do NOT replay the animation. This is what
 * removes the streaming "flicker" while keeping the full entry animation.
 * Cleared on session switch so a freshly loaded conversation animates in.
 */
const animatedEntryKeys = new Set<string>();

/**
 * H3: 消息出场动画专用退出延迟(ms),对齐 --dlg-out: 0.16s。
 * 消息从 visibleMessages 移除后,先保留在 DOM 中播放 animate-out 动画,
 * 结束后再真正卸载。避免消息瞬切消失。
 */
const MESSAGE_EXIT_MS = 160;

type VisibleMessageUnit =
  | { kind: 'message'; message: ClaudeMessage; messageIndex: number }
  | {
      kind: 'assistant_response_group';
      responseId: string;
      items: Array<{ message: ClaudeMessage; messageIndex: number }>;
    };

function extractToolResultPreview(result: ToolResultBlock | null | undefined): string {
  if (!result) return 'pending';

  let text = '';
  if (typeof result.content === 'string') {
    text = result.content;
  } else if (Array.isArray(result.content)) {
    text = result.content
      .map((item) => (item && typeof item.text === 'string' ? item.text : ''))
      .filter(Boolean)
      .join('\n');
  }

  const preview = text.length > 200 ? text.slice(0, 200) : text;
  return `${result.is_error === true ? 'error' : 'ok'}:${text.length}:${preview}`;
}

function getMessageToolResultSignature(
  message: ClaudeMessage,
  messageIndex: number,
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[],
  findToolResult: (
    toolId: string | undefined,
    messageIndex: number,
  ) => ToolResultBlock | null | undefined,
): string {
  const toolUses = getContentBlocks(message).filter(
    (block): block is Extract<ClaudeContentBlock, { type: 'tool_use' }> =>
      block.type === 'tool_use',
  );
  if (toolUses.length === 0) return '';

  return toolUses
    .map(
      (block) =>
        `${block.id ?? 'unknown'}:${extractToolResultPreview(findToolResult(block.id, messageIndex))}`,
    )
    .join('|');
}

function getGroupedAssistantUsage(items: Array<{ message: ClaudeMessage }>): {
  inputTokens: number | null;
  outputTokens: number | null;
  cacheCreationTokens: number | null;
  cacheReadTokens: number | null;
  costUsd: number | null;
  durationMs: number | null;
  durationLabelKey: string;
} {
  let inputTokens = 0;
  let outputTokens = 0;
  let cacheCreationTokens = 0;
  let cacheReadTokens = 0;
  let costUsd = 0;
  let hasTokens = false;
  let hasCost = false;
  let durationMs: number | null = null;
  let durationLabelKey = 'chat.usageStats.duration';

  for (const { message } of items) {
    const usage = extractMessageUsage(message);
    if (usage) {
      inputTokens += usage.inputTokens;
      outputTokens += usage.outputTokens;
      cacheCreationTokens += usage.cacheCreationTokens;
      cacheReadTokens += usage.cacheReadTokens;
      if (usage.costUsd !== undefined) {
        costUsd += usage.costUsd;
        hasCost = true;
      }
      hasTokens = true;
    }
    if (typeof message.durationMs === 'number' && message.durationMs > 0) {
      durationMs = message.durationMs;
      durationLabelKey =
        message.streamEndSource === 'watchdog' || message.streamEndReason === 'stalled'
          ? 'chat.waitingTimedOutDuration'
          : 'chat.usageStats.duration';
    }
  }

  return {
    inputTokens: hasTokens ? inputTokens : null,
    outputTokens: hasTokens ? outputTokens : null,
    cacheCreationTokens: hasTokens ? cacheCreationTokens : null,
    cacheReadTokens: hasTokens ? cacheReadTokens : null,
    costUsd: hasCost ? costUsd : null,
    durationMs,
    durationLabelKey,
  };
}

interface MessageListProps {
  messages: ClaudeMessage[];
  messageKeys: readonly string[];
  streamingActive: boolean;
  isThinking: boolean;
  loading: boolean;
  loadingStartTime: number | null;
  queueDisplayState: QueueDisplayState;
  queueAheadCount: number;
  t: TFunction;
  getMessageText: (message: ClaudeMessage) => string;
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[];
  findToolResult: (
    toolId: string | undefined,
    messageIndex: number,
  ) => ToolResultBlock | null | undefined;
  extractMarkdownContent: (message: ClaudeMessage) => string;
  messagesEndRef: React.RefObject<HTMLDivElement | null>;
  onMessageNodeRef?: (id: string, node: HTMLDivElement | null) => void;
  /** Notify parent when the number of collapsed (hidden) messages changes. */
  onCollapsedCountChange?: (count: number) => void;
  onNavigateToProviderSettings?: () => void;
  onNavigateToDependencySettings?: () => void;
  /** Current active provider id; forwarded to MessageItem for streaming-connect label. */
  currentProvider?: string;
  /** Current session id, used to request an earlier Codex history page. */
  currentSessionId?: string | null;
  /** Shared scroll-follow state used to suppress duplicate live announcements. */
  isUserAtBottomRef?: React.RefObject<boolean>;
  detailedOutputEnabled?: boolean;
  avatarConfig?: AvatarConfig | null;
}

export const MessageList = memo(
  forwardRef<MessageListRevealHandle, MessageListProps>(function MessageList(
    {
      messages,
      streamingActive,
      isThinking,
      loading,
      loadingStartTime,
      queueDisplayState,
      queueAheadCount,
      t,
      getMessageText,
      getContentBlocks,
      findToolResult,
      extractMarkdownContent,
      messagesEndRef,
      onMessageNodeRef,
      onCollapsedCountChange,
      onNavigateToProviderSettings,
      onNavigateToDependencySettings,
      currentProvider,
      currentSessionId,
      isUserAtBottomRef,
      detailedOutputEnabled = false,
      avatarConfig,
    },
    ref,
  ) {
    // Number of earlier complete user turns revealed beyond the initial window.
    const [revealedTurnCount, setRevealedTurnCount] = useState(0);
    const [historyPageInfo, setHistoryPageInfo] = useState<CodexHistoryPageInfoPayloadWire | null>(
      null,
    );
    const [loadingEarlierHistory, setLoadingEarlierHistory] = useState(false);
    const loadingEarlierHistoryRef = useRef(false);
    const latestAssistantResponseText = useMemo(
      () => getLatestAssistantResponseText(messages, getMessageText),
      [getMessageText, messages],
    );
    const streamingTurnBoundaryKey = useMemo(
      () => getStreamingTurnBoundaryKey(messages, currentSessionId),
      [currentSessionId, messages],
    );
    const streamingAnnouncement = useStreamingAnnouncement({
      latestText: latestAssistantResponseText,
      streamingActive,
      isUserAtBottomRef,
      resetKey: streamingTurnBoundaryKey,
    });

    // Whole-response copy state. The grouped card hosts one copy button per turn
    // (segments themselves carry no copy button), so this state lives at the list.
    const [copiedResponseId, setCopiedResponseId] = useState<string | null>(null);
    const copyResponseTimeoutRef = useRef<number | null>(null);

    const handleCopyResponse = useCallback(
      async (responseId: string, text: string) => {
        if (copiedResponseId === responseId) return;
        const success = await copyToClipboard(text);
        if (success) {
          setCopiedResponseId(responseId);
          if (copyResponseTimeoutRef.current !== null) {
            window.clearTimeout(copyResponseTimeoutRef.current);
          }
          copyResponseTimeoutRef.current = window.setTimeout(() => {
            setCopiedResponseId(null);
            copyResponseTimeoutRef.current = null;
          }, 1500);
        }
      },
      [copiedResponseId],
    );

    useEffect(() => {
      return () => {
        if (copyResponseTimeoutRef.current !== null) {
          window.clearTimeout(copyResponseTimeoutRef.current);
          copyResponseTimeoutRef.current = null;
        }
      };
    }, []);

    // Keep WaitingIndicator mounted during exit animation
    const [waitingVisible, setWaitingVisible] = useState(false);

    useEffect(() => {
      if (loading) {
        setWaitingVisible(true);
      }
    }, [loading]);

    const handleWaitingExitComplete = useCallback(() => {
      setWaitingVisible(false);
    }, []);

    const hasInlineAssistantActivity = useMemo(() => {
      const tail = messages[messages.length - 1];
      return Boolean(
        streamingActive &&
        tail?.type === 'assistant' &&
        tail.__suppressStreamingConnectHint !== true,
      );
    }, [messages, streamingActive]);

    const shouldShowWaitingIndicator =
      waitingVisible && queueDisplayState === 'QUEUED' && !hasInlineAssistantActivity;

    // Context menu for message list (copy only, when text selected)
    const ctxMenu = useContextMenu();
    const handleMessageContextMenu = useCallback(
      (e: React.MouseEvent) => {
        const sel = window.getSelection();
        if (sel && sel.toString().trim().length > 0) {
          ctxMenu.open(e);
        }
      },
      [ctxMenu.open],
    );

    // Reset reveal state when a new session starts. The boundary key also handles
    // history records without a stable `id` by falling back to uuid/timestamp/content.
    const firstMessageBoundaryRef = useRef(getFirstMessageBoundaryKey(messages[0]));
    const sessionBoundaryExitSkipsRef = useRef(0);

    useEffect(() => {
      const currentBoundary = getFirstMessageBoundaryKey(messages[0]);
      const isSessionStart = messages.length === 0;

      if (isSessionStart || currentBoundary !== firstMessageBoundaryRef.current) {
        sessionBoundaryExitSkipsRef.current = 2;
        setRevealedTurnCount(0);
        animatedEntryKeys.clear();
        // Key aliases are session-scoped (a `user-<timestamp>` key can legally
        // repeat across sessions) — clear them with the entry-key set.
        clearMessageKeyAliases();
      }
      firstMessageBoundaryRef.current = currentBoundary;
    }, [messages]);

    useEffect(() => {
      loadingEarlierHistoryRef.current = false;
      setLoadingEarlierHistory(false);
      setHistoryPageInfo(null);
    }, [currentProvider, currentSessionId]);

    useEffect(() => {
      const unsubscribeInfo = subscribeEvent<unknown>(
        DOWNSTREAM.HISTORY_CODEX_PAGE_INFO,
        (rawPayload) => {
          const payload = parseBridgePayload<CodexHistoryPageInfoPayloadWire>(rawPayload);
          if (!payload || payload.sessionId !== currentSessionId) return;
          loadingEarlierHistoryRef.current = false;
          setLoadingEarlierHistory(false);
          setHistoryPageInfo(payload);
        },
      );
      const unsubscribeError = subscribeEvent<unknown>(
        DOWNSTREAM.HISTORY_CODEX_PAGE_ERROR,
        (rawPayload) => {
          const payload = parseBridgePayload<CodexHistoryPageErrorPayloadWire>(rawPayload);
          if (payload?.sessionId && payload.sessionId !== currentSessionId) return;
          loadingEarlierHistoryRef.current = false;
          setLoadingEarlierHistory(false);
        },
      );
      return () => {
        unsubscribeInfo();
        unsubscribeError();
      };
    }, [currentSessionId]);

    const userTurnStartIndexes = useMemo(
      () =>
        messages.reduce<number[]>((indexes, message, index) => {
          if (isHumanUserMessage(message)) indexes.push(index);
          return indexes;
        }, []),
      [messages],
    );
    const visibleTurnCount = Math.min(
      userTurnStartIndexes.length,
      INITIAL_VISIBLE_TURNS + revealedTurnCount,
    );
    const hiddenTurnCount = userTurnStartIndexes.length - visibleTurnCount;
    const collapsedCount = hiddenTurnCount > 0 ? userTurnStartIndexes[hiddenTurnCount] : 0;
    const shouldCollapse = collapsedCount > 0;
    const nextTurnCount = Math.min(REVEAL_TURN_PAGE_SIZE, hiddenTurnCount);

    const canLoadEarlierFromDisk = Boolean(
      // 磁盘分页 provider 白名单:后端 LoadCodexHistoryPageActionHandler 按 currentProvider 路由。
      (currentProvider === PROVIDER_TYPE.CODEX || currentProvider === PROVIDER_TYPE.CLAUDE) &&
      historyPageInfo?.sessionId === currentSessionId &&
      historyPageInfo?.hasMore,
    );
    const handleRevealMore = useCallback(() => {
      if (hiddenTurnCount > 0) {
        setRevealedTurnCount((prev) => prev + REVEAL_TURN_PAGE_SIZE);
        return;
      }
      if (
        !canLoadEarlierFromDisk ||
        loadingEarlierHistoryRef.current ||
        !currentSessionId ||
        !historyPageInfo
      ) {
        return;
      }

      loadingEarlierHistoryRef.current = true;
      setLoadingEarlierHistory(true);
      const request: CodexHistoryPageRequestPayloadWire = {
        sessionId: currentSessionId,
        beforeTurn: historyPageInfo.fromTurn,
      };
      const sent = sendAction(UPSTREAM.LOAD_CODEX_HISTORY_PAGE, request);
      if (!sent) {
        loadingEarlierHistoryRef.current = false;
        setLoadingEarlierHistory(false);
      }
    }, [canLoadEarlierFromDisk, currentSessionId, hiddenTurnCount, historyPageInfo]);

    useImperativeHandle(
      ref,
      (): MessageListRevealHandle => ({
        revealAll: () => {
          const previouslyHidden = collapsedCount;
          if (previouslyHidden === 0) return 0;
          setRevealedTurnCount(userTurnStartIndexes.length);
          return previouslyHidden;
        },
      }),
      [collapsedCount, userTurnStartIndexes.length],
    );

    useEffect(() => {
      onCollapsedCountChange?.(collapsedCount);
    }, [collapsedCount, onCollapsedCountChange]);

    const visibleMessages = useMemo(
      () => (shouldCollapse ? messages.slice(collapsedCount) : messages),
      [messages, shouldCollapse, collapsedCount],
    );

    // Index of the most recent assistant message. While a turn is in flight a
    // tool_result user message may temporarily sit after the active streaming
    // group, so streaming-tail checks compare against this index instead of
    // the absolute last message — otherwise the responding indicator would
    // vanish for the whole gap between a tool call and its follow-up.
    const lastAssistantIndex = useMemo(() => {
      for (let index = messages.length - 1; index >= 0; index -= 1) {
        if (messages[index]?.type === 'assistant') return index;
      }
      return -1;
    }, [messages]);

    const visibleMessageUnits = useMemo((): VisibleMessageUnit[] => {
      const units: VisibleMessageUnit[] = [];
      let visibleIndex = 0;

      while (visibleIndex < visibleMessages.length) {
        const messageIndex = shouldCollapse ? visibleIndex + collapsedCount : visibleIndex;
        const message = visibleMessages[visibleIndex];
        const responseId =
          message.type === 'assistant' && typeof message.__responseId === 'string'
            ? message.__responseId
            : undefined;

        if (!responseId) {
          units.push({ kind: 'message', message, messageIndex });
          visibleIndex += 1;
          continue;
        }

        const items: Array<{ message: ClaudeMessage; messageIndex: number }> = [];
        let cursor = visibleIndex;
        while (cursor < visibleMessages.length) {
          const candidate = visibleMessages[cursor];
          if (candidate.type !== 'assistant' || candidate.__responseId !== responseId) {
            break;
          }
          items.push({
            message: candidate,
            messageIndex: shouldCollapse ? cursor + collapsedCount : cursor,
          });
          cursor += 1;
        }

        // Any assistant carrying a __responseId renders as a group container —
        // even a single segment. Keeping the structure independent of segment
        // count keeps the top-level React key stable as segments are added or
        // removed during streaming, so the card never remounts (a remount would
        // replay the entry animation = the flicker we are fixing).
        units.push({ kind: 'assistant_response_group', responseId, items });
        visibleIndex = cursor;
      }

      return units;
    }, [visibleMessages, shouldCollapse, collapsedCount]);

    /**
     * H3: 消息出场动画。
     * 缓存最近一次 visibleMessageUnits 对应的消息数据,当某条消息从列表中消失时,
     * 保留其数据在 exitingMessages 中,继续渲染 160ms 以播放 animate-out 动画。
     */
    const prevUnitMapRef = useRef<Map<string, { message: ClaudeMessage; messageIndex: number }>>(
      new Map(),
    );
    const [exitingMessages, setExitingMessages] = useState<
      Map<string, { message: ClaudeMessage; messageIndex: number }>
    >(new Map());
    const exitingTimeoutsRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

    useEffect(() => {
      // 构建当前可见消息的 key→data 映射
      const currentMap = new Map<string, { message: ClaudeMessage; messageIndex: number }>();
      for (const unit of visibleMessageUnits) {
        if (unit.kind === 'message') {
          const key = getMessageKey(unit.message, unit.messageIndex);
          currentMap.set(key, { message: unit.message, messageIndex: unit.messageIndex });
        } else {
          for (const { message, messageIndex } of unit.items) {
            const key = getMessageKey(message, messageIndex);
            currentMap.set(key, { message, messageIndex });
          }
        }
      }

      if (sessionBoundaryExitSkipsRef.current > 0) {
        sessionBoundaryExitSkipsRef.current -= 1;
        for (const timeoutId of exitingTimeoutsRef.current.values()) {
          clearTimeout(timeoutId);
        }
        exitingTimeoutsRef.current.clear();
        setExitingMessages(new Map());
        prevUnitMapRef.current = currentMap;
        return;
      }

      // 检测消失的消息
      const prevMap = prevUnitMapRef.current;
      const newExiting = new Map(exitingMessages);
      // 仅当退出集合内容实际变化时才 setState:流式 tick 每 ~33ms 触发本 effect,
      // 无条件 setExitingMessages(恒为新 Map 引用)会造成每 tick 双倍渲染。
      let exitingChanged = false;
      for (const [key, data] of prevMap) {
        if (!currentMap.has(key) && !newExiting.has(key) && !exitingTimeoutsRef.current.has(key)) {
          // 消息消失：加入 exiting 集合,播放退出动画
          newExiting.set(key, data);
          exitingChanged = true;
          const timeout = setTimeout(() => {
            setExitingMessages((prev) => {
              const next = new Map(prev);
              next.delete(key);
              return next;
            });
            exitingTimeoutsRef.current.delete(key);
          }, MESSAGE_EXIT_MS);
          exitingTimeoutsRef.current.set(key, timeout);
        }
      }
      // 取消已重新出现的消息的退出动画
      for (const [key, timeout] of exitingTimeoutsRef.current) {
        if (currentMap.has(key)) {
          clearTimeout(timeout);
          exitingTimeoutsRef.current.delete(key);
          exitingChanged = newExiting.delete(key) || exitingChanged;
        }
      }
      if (exitingChanged) {
        setExitingMessages(newExiting);
      }
      prevUnitMapRef.current = currentMap;
    }, [visibleMessageUnits]);

    return (
      <div className="message-list" onContextMenu={handleMessageContextMenu}>
        <div
          className="sr-only"
          role="status"
          aria-live="polite"
          aria-atomic="true"
          data-testid="stream-announcer"
        >
          {streamingAnnouncement}
        </div>
        {ctxMenu.visible && (
          <ContextMenu
            x={ctxMenu.x}
            y={ctxMenu.y}
            onClose={ctxMenu.close}
            items={[
              {
                label: t('contextMenu.copy', 'Copy'),
                action: () => copySelection(ctxMenu.savedRange, ctxMenu.selectedText),
              },
            ]}
          />
        )}
        {(shouldCollapse || canLoadEarlierFromDisk) && (
          <div className="collapsed-messages-indicator" onClick={handleRevealMore}>
            {loadingEarlierHistory
              ? t('chat.loadingEarlierTurns')
              : shouldCollapse
                ? t('chat.showEarlierTurns', {
                    count: nextTurnCount,
                    remaining: hiddenTurnCount,
                    total: historyPageInfo?.totalTurns,
                  })
                : t('chat.loadEarlierTurns', {
                    count: Math.min(CODEX_HISTORY_PAGE_SIZE, historyPageInfo?.fromTurn ?? 0),
                    remaining: historyPageInfo?.fromTurn ?? 0,
                    total: historyPageInfo?.totalTurns ?? 0,
                  })}
          </div>
        )}

        {visibleMessageUnits.map((unit) => {
          if (unit.kind === 'assistant_response_group') {
            const usage = getGroupedAssistantUsage(unit.items);
            const groupKey = `response-${unit.responseId}`;
            // Animate ONLY on the card's first appearance; never replay on remount.
            const groupFirstAppearance = !animatedEntryKeys.has(groupKey);
            if (groupFirstAppearance) {
              animatedEntryKeys.add(groupKey);
            }
            // A group is "streaming" while its last segment is the most recent
            // assistant message AND the turn is still active — a tool_result
            // user message may follow it mid-turn without ending the response.
            // Copy is hidden then to avoid copying partial text.
            const isStreamingGroup =
              streamingActive &&
              unit.items[unit.items.length - 1].messageIndex === lastAssistantIndex;
            const streamingTailMessage = isStreamingGroup
              ? unit.items[unit.items.length - 1]?.message
              : undefined;
            // The footer follows the first renderable block of the turn
            // (thinking / tool / MCP / text), not only text output.
            const streamingGroupHasVisibleOutput =
              isStreamingGroup &&
              unit.items.some(({ message }) =>
                hasAssistantVisibleOutput(message, getContentBlocks(message)),
              );
            const shouldShowStreamingFooter = streamingGroupHasVisibleOutput;
            const groupCopyableText = unit.items
              .map(({ message }) => extractMarkdownContent(message))
              .map((text) => text.trim())
              .filter((text) => text.length > 0)
              .join('\n\n');
            const groupHasCopyable = groupCopyableText.length > 0;
            return (
              <FadeContent key={groupKey} duration={280} offset={10} disabled={!groupFirstAppearance}>
              <div
                className="message assistant assistant-response-group"
                data-response-id={unit.responseId}
              >
                <div className="message-avatar-row">
                  <MessageAvatar
                    type="assistant"
                    currentProvider={currentProvider}
                    avatarConfig={avatarConfig}
                    assistantLabel={getProviderDisplayName(currentProvider, t)}
                  />
                  <div className="message-content-wrapper">
                    {!isStreamingGroup && groupHasCopyable && (
                      <CopyButton
                        isCopied={copiedResponseId === unit.responseId}
                        onClick={() => handleCopyResponse(unit.responseId, groupCopyableText)}
                        copyLabel={t('markdown.copyMessage')}
                        copySuccessText={t('markdown.copySuccess')}
                      />
                    )}
                    <div className="message-content assistant-response-content">
                      {unit.items.map(({ message, messageIndex }, itemIndex) => {
                        const messageKey = getMessageKey(message, messageIndex);
                        const toolResultSignature = getMessageToolResultSignature(
                          message,
                          messageIndex,
                          getContentBlocks,
                          findToolResult,
                        );
                        return (
                          <div
                            key={messageKey}
                            className="assistant-response-segment"
                            data-segment-index={itemIndex}
                          >
                            <MessageItem
                              message={message}
                              messageIndex={messageIndex}
                              messageKey={messageKey}
                              isLast={messageIndex === messages.length - 1}
                              streamingActive={streamingActive}
                              isThinking={isThinking}
                              t={t}
                              getMessageText={getMessageText}
                              getContentBlocks={getContentBlocks}
                              findToolResult={findToolResult}
                              extractMarkdownContent={extractMarkdownContent}
                              onNodeRef={onMessageNodeRef}
                              onNavigateToProviderSettings={onNavigateToProviderSettings}
                              onNavigateToDependencySettings={onNavigateToDependencySettings}
                              toolResultSignature={toolResultSignature}
                              currentProvider={currentProvider}
                              detailedOutputEnabled={detailedOutputEnabled}
                              avatarConfig={avatarConfig}
                              loadingStartTime={loadingStartTime}
                              withinResponseGroup={true}
                              renderMode="response-segment"
                              suppressAssistantResponseStatus={shouldShowStreamingFooter}
                            />
                          </div>
                        );
                      })}
                      {shouldShowStreamingFooter ? (
                        <AssistantStreamingFooter
                          elapsedMs={streamingTailMessage?.__assistantResponseStatus?.elapsedMs}
                          startedAt={loadingStartTime}
                          t={t}
                        />
                      ) : null}
                    </div>
                    {!isStreamingGroup && (
                      <MessageUsageStats
                        inputTokens={usage.inputTokens}
                        outputTokens={usage.outputTokens}
                        cacheReadTokens={usage.cacheReadTokens}
                        detailedOutputEnabled={detailedOutputEnabled}
                        durationMs={usage.durationMs}
                        durationLabelKey={usage.durationLabelKey}
                        t={t}
                      />
                    )}
                  </div>
                </div>
              </div>
              </FadeContent>
            );
          }

          const { message, messageIndex } = unit;
          const messageKey = getMessageKey(message, messageIndex);
          const toolResultSignature = getMessageToolResultSignature(
            message,
            messageIndex,
            getContentBlocks,
            findToolResult,
          );
          const singleFirstAppearance = !animatedEntryKeys.has(messageKey);
          if (singleFirstAppearance) {
            animatedEntryKeys.add(messageKey);
          }

          return (
            <MessageItem
              key={messageKey}
              message={message}
              messageIndex={messageIndex}
              messageKey={messageKey}
              shouldAnimateIn={singleFirstAppearance}
              isLast={messageIndex === messages.length - 1}
              streamingActive={streamingActive}
              isThinking={isThinking}
              t={t}
              getMessageText={getMessageText}
              getContentBlocks={getContentBlocks}
              findToolResult={findToolResult}
              extractMarkdownContent={extractMarkdownContent}
              onNodeRef={onMessageNodeRef}
              onNavigateToProviderSettings={onNavigateToProviderSettings}
              onNavigateToDependencySettings={onNavigateToDependencySettings}
              toolResultSignature={toolResultSignature}
              currentProvider={currentProvider}
              detailedOutputEnabled={detailedOutputEnabled}
              avatarConfig={avatarConfig}
              loadingStartTime={loadingStartTime}
            />
          );
        })}

        {/* H3: 消息出场动画 —— 消失的消息保留在 DOM 中 160ms 播放淡出动画 */}
        {Array.from(exitingMessages.entries()).map(([key, { message, messageIndex }]) => (
          <MessageItem
            key={`exiting-${key}`}
            message={message}
            messageIndex={messageIndex}
            messageKey={key}
            shouldAnimateOut
            isLast={false}
            streamingActive={false}
            isThinking={false}
            t={t}
            getMessageText={getMessageText}
            getContentBlocks={getContentBlocks}
            findToolResult={findToolResult}
            extractMarkdownContent={extractMarkdownContent}
            currentProvider={currentProvider}
            detailedOutputEnabled={detailedOutputEnabled}
            avatarConfig={avatarConfig}
            loadingStartTime={loadingStartTime}
          />
        ))}

        {/* Loading / queue indicator */}
        {shouldShowWaitingIndicator && (
          <WaitingIndicator
            queueAheadCount={queueAheadCount}
            loading={loading}
            onExitComplete={handleWaitingExitComplete}
          />
        )}
        <div ref={messagesEndRef} />
      </div>
    );
  }),
);
