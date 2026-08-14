import { Fragment, useState, useCallback, useMemo, memo, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, ClaudeContentBlock, ToolResultBlock } from '../../types';

import MarkdownBlock from '../MarkdownBlock';
import {
  ProviderNotConfiguredCard,
  isProviderNotConfiguredError,
} from './ProviderNotConfiguredCard';
import { ErrorDiagnosticCard } from './ErrorDiagnosticCard';
import { matchErrorPattern } from '../../utils/errorMatcher';
import {
  EditToolBlock,
  EditToolGroupBlock,
  ReadToolBlock,
  ReadToolGroupBlock,
  BashToolBlock,
  BashToolGroupBlock,
  SearchToolGroupBlock,
} from '../toolBlocks';
import { ContentBlockRenderer } from './ContentBlockRenderer';
import { formatTime } from '../../utils/helpers';
import { getProviderDisplayName } from '../../utils/providerLabel';
import { copyToClipboard } from '../../utils/copyUtils';
import { quoteToChatInput } from '../../utils/quoteUtils';
import {
  READ_TOOL_NAMES,
  EDIT_TOOL_NAMES,
  BASH_TOOL_NAMES,
  SEARCH_TOOL_NAMES,
  isToolName,
} from '../../utils/toolConstants';
import { MessageAvatar } from './MessageAvatar';
import { MessageUsageStats } from './MessageUsageStats';
import { extractMessageUsage } from '../../utils/messageUsage';
import { CopyIcon } from '../Icons';
import { AssistantResponseStatus } from './AssistantResponseStatus';
import { AssistantStreamingFooter } from './AssistantStreamingFooter';
import { hasAssistantTextOutput } from './assistantTextOutput';
import type { AvatarConfig } from '../../types/avatar';

type GroupedBlock =
  | { type: 'single'; block: ClaudeContentBlock; originalIndex: number }
  | { type: 'read_group'; blocks: ClaudeContentBlock[]; startIndex: number }
  | { type: 'edit_group'; blocks: ClaudeContentBlock[]; startIndex: number }
  | { type: 'bash_group'; blocks: ClaudeContentBlock[]; startIndex: number }
  | { type: 'search_group'; blocks: ClaudeContentBlock[]; startIndex: number };

type AssistantMessageSectionKind = 'thinking' | 'tools' | 'output';

interface AssistantMessageSection {
  kind: AssistantMessageSectionKind;
  startIndex: number;
  items: GroupedBlock[];
}

function getGroupedBlockStartIndex(grouped: GroupedBlock): number {
  return grouped.type === 'single' ? grouped.originalIndex : grouped.startIndex;
}

function getAssistantMessageSectionKind(grouped: GroupedBlock): AssistantMessageSectionKind {
  if (grouped.type !== 'single') {
    return 'tools';
  }

  if (grouped.block.type === 'thinking') {
    return 'thinking';
  }

  if (grouped.block.type === 'tool_use' || grouped.block.type === 'skill_use') {
    return 'tools';
  }

  return 'output';
}

function groupAssistantMessageSections(groupedBlocks: GroupedBlock[]): AssistantMessageSection[] {
  return groupedBlocks.reduce<AssistantMessageSection[]>((sections, grouped) => {
    const kind = getAssistantMessageSectionKind(grouped);
    const lastSection = sections[sections.length - 1];

    if (lastSection?.kind === kind) {
      lastSection.items.push(grouped);
      return sections;
    }

    sections.push({
      kind,
      startIndex: getGroupedBlockStartIndex(grouped),
      items: [grouped],
    });
    return sections;
  }, []);
}

interface CopyButtonProps {
  className?: string;
  isCopied: boolean;
  onClick: () => void;
  copyLabel: string;
  copySuccessText: string;
}

export const CopyButton = memo(function CopyButton({
  className,
  isCopied,
  onClick,
  copyLabel,
  copySuccessText,
}: CopyButtonProps) {
  return (
    <button
      type="button"
      className={`message-copy-btn${className ? ` ${className}` : ''} ${isCopied ? 'copied' : ''}`}
      onClick={onClick}
      title={copyLabel}
      aria-label={copyLabel}
    >
      <span className="copy-icon">
        <CopyIcon />
      </span>
      <span className="copy-tooltip">{copySuccessText}</span>
    </button>
  );
});

/** Quote icon (chat bubble with a right-arrow) used by the message quote button */
const QuoteIcon = () => (
  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M2 3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H6l-3 3v-3H3a1 1 0 0 1-1-1z" fill="currentColor" fillOpacity="0.6"/>
    <path d="M7.5 4.5l2.5 2.5-2.5 2.5M5 7h5" stroke="var(--bg-secondary)" strokeWidth="1" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

interface QuoteButtonProps {
  className?: string;
  isQuoted: boolean;
  onClick: () => void;
  quoteLabel: string;
  quoteSuccessText: string;
}

export const QuoteButton = memo(function QuoteButton({
  className,
  isQuoted,
  onClick,
  quoteLabel,
  quoteSuccessText,
}: QuoteButtonProps) {
  return (
    <button
      type="button"
      className={`message-copy-btn message-quote-btn${className ? ` ${className}` : ''} ${isQuoted ? 'copied' : ''}`}
      onClick={onClick}
      title={quoteLabel}
      aria-label={quoteLabel}
    >
      <span className="copy-icon">
        <QuoteIcon />
      </span>
      <span className="copy-tooltip">{quoteSuccessText}</span>
    </button>
  );
});

function isToolBlockOfType(block: ClaudeContentBlock, toolNames: Set<string>): boolean {
  return block.type === 'tool_use' && isToolName(block.name, toolNames);
}

export function groupBlocks(blocks: ClaudeContentBlock[]): GroupedBlock[] {
  const groupedBlocks: GroupedBlock[] = [];
  let currentReadGroup: ClaudeContentBlock[] = [];
  let readGroupStartIndex = -1;
  let currentEditGroup: ClaudeContentBlock[] = [];
  let editGroupStartIndex = -1;
  let currentBashGroup: ClaudeContentBlock[] = [];
  let bashGroupStartIndex = -1;
  let currentSearchGroup: ClaudeContentBlock[] = [];
  let searchGroupStartIndex = -1;

  const flushReadGroup = () => {
    if (currentReadGroup.length > 0) {
      groupedBlocks.push({
        type: 'read_group',
        blocks: [...currentReadGroup],
        startIndex: readGroupStartIndex,
      });
      currentReadGroup = [];
      readGroupStartIndex = -1;
    }
  };

  const flushEditGroup = () => {
    if (currentEditGroup.length > 0) {
      groupedBlocks.push({
        type: 'edit_group',
        blocks: [...currentEditGroup],
        startIndex: editGroupStartIndex,
      });
      currentEditGroup = [];
      editGroupStartIndex = -1;
    }
  };

  const flushBashGroup = () => {
    if (currentBashGroup.length > 0) {
      groupedBlocks.push({
        type: 'bash_group',
        blocks: [...currentBashGroup],
        startIndex: bashGroupStartIndex,
      });
      currentBashGroup = [];
      bashGroupStartIndex = -1;
    }
  };

  const flushSearchGroup = () => {
    if (currentSearchGroup.length > 0) {
      groupedBlocks.push({
        type: 'search_group',
        blocks: [...currentSearchGroup],
        startIndex: searchGroupStartIndex,
      });
      currentSearchGroup = [];
      searchGroupStartIndex = -1;
    }
  };

  blocks.forEach((block, idx) => {
    if (isToolBlockOfType(block, READ_TOOL_NAMES)) {
      flushEditGroup();
      flushBashGroup();
      flushSearchGroup();
      if (currentReadGroup.length === 0) {
        readGroupStartIndex = idx;
      }
      currentReadGroup.push(block);
    } else if (isToolBlockOfType(block, EDIT_TOOL_NAMES)) {
      flushReadGroup();
      flushBashGroup();
      flushSearchGroup();
      if (currentEditGroup.length === 0) {
        editGroupStartIndex = idx;
      }
      currentEditGroup.push(block);
    } else if (isToolBlockOfType(block, BASH_TOOL_NAMES)) {
      flushReadGroup();
      flushEditGroup();
      flushSearchGroup();
      if (currentBashGroup.length === 0) {
        bashGroupStartIndex = idx;
      }
      currentBashGroup.push(block);
    } else if (isToolBlockOfType(block, SEARCH_TOOL_NAMES)) {
      flushReadGroup();
      flushEditGroup();
      flushBashGroup();
      if (currentSearchGroup.length === 0) {
        searchGroupStartIndex = idx;
      }
      currentSearchGroup.push(block);
    } else {
      flushReadGroup();
      flushEditGroup();
      flushBashGroup();
      flushSearchGroup();
      groupedBlocks.push({ type: 'single', block, originalIndex: idx });
    }
  });

  flushReadGroup();
  flushEditGroup();
  flushBashGroup();
  flushSearchGroup();

  return groupedBlocks;
}

/** Stable no-op for ContentBlockRenderer instances that never toggle thinking. */
const noopThinkingToggle = (_blockIndex: number) => {};

export const MessageItem = memo(function MessageItem({
  message,
  messageIndex,
  messageKey,
  isLast,
  streamingActive,
  isThinking,
  t,
  getMessageText,
  getContentBlocks,
  findToolResult,
  extractMarkdownContent,
  onNodeRef,
  onNavigateToProviderSettings,
  onNavigateToDependencySettings,
  toolResultSignature: _toolResultSignature,
  currentProvider,
  detailedOutputEnabled = false,
  avatarConfig,
  loadingStartTime,
  withinResponseGroup = false,
  renderMode = 'full',
  suppressAssistantResponseStatus = false,
  shouldAnimateIn = false,
  shouldAnimateOut = false,
}: {
  message: ClaudeMessage;
  messageIndex: number;
  messageKey: string;
  isLast: boolean;
  streamingActive: boolean;
  isThinking: boolean;
  t: TFunction;
  getMessageText: (message: ClaudeMessage) => string;
  getContentBlocks: (message: ClaudeMessage) => ClaudeContentBlock[];
  findToolResult: (
    toolId: string | undefined,
    messageIndex: number,
  ) => ToolResultBlock | null | undefined;
  extractMarkdownContent: (message: ClaudeMessage) => string;
  onNodeRef?: (id: string, node: HTMLDivElement | null) => void;
  onNavigateToProviderSettings?: () => void;
  onNavigateToDependencySettings?: () => void;
  toolResultSignature?: string;
  /** Current active provider id. */
  currentProvider?: string;
  detailedOutputEnabled?: boolean;
  avatarConfig?: AvatarConfig | null;
  /** Timestamp when the current assistant generation/loading cycle started. */
  loadingStartTime?: number | null;
  /** Rendered inside a grouped assistant response container. */
  withinResponseGroup?: boolean;
  /** Render only message blocks, without avatar, bubble, copy button, or usage stats. */
  renderMode?: 'full' | 'response-segment';
  /** Hide a stale response-phase placeholder after grouped answer text starts streaming. */
  suppressAssistantResponseStatus?: boolean;
  /** Play the messageFadeIn entry animation on this card. Set only on the card's
   *  first logical appearance so React remounts never replay the animation. */
  shouldAnimateIn?: boolean;
  /** Play the messageFadeOut exit animation on this card (H3). Applied when a
   *  message is being removed from the list and kept in DOM briefly to animate out. */
  shouldAnimateOut?: boolean;
}): React.ReactElement {
  const [copiedMessageIndex, setCopiedMessageIndex] = useState<number | null>(null);
  const [quotedMessageIndex, setQuotedMessageIndex] = useState<number | null>(null);

  // Track timeout to properly cleanup on unmount
  const copyTimeoutRef = useRef<number | null>(null);
  const quoteTimeoutRef = useRef<number | null>(null);

  // Manage thinking expansion state locally to avoid prop drilling and unnecessary re-renders
  const [expandedThinking, setExpandedThinking] = useState<Record<number, boolean>>({});
  // Track which thinking blocks were manually expanded by the user
  const [manuallyExpandedThinking, setManuallyExpandedThinking] = useState<Record<number, boolean>>(
    {},
  );

  const toggleThinking = useCallback((blockIndex: number) => {
    setExpandedThinking((prev) => {
      const newExpanded = !prev[blockIndex];
      // Mark this block as manually controlled so automatic default expansion
      // never reopens/collapses it on later renders.
      setManuallyExpandedThinking((manualPrev) => ({
        ...manualPrev,
        [blockIndex]: true,
      }));
      return {
        ...prev,
        [blockIndex]: newExpanded,
      };
    });
  }, []);

  const isThinkingExpanded = useCallback(
    (blockIndex: number) => Boolean(expandedThinking[blockIndex]),
    [expandedThinking],
  );

  const isLastAssistantMessage = message.type === 'assistant' && isLast;
  const isMessageStreaming = streamingActive && isLastAssistantMessage;
  const durationLabelKey =
    message.streamEndSource === 'watchdog' || message.streamEndReason === 'stalled'
      ? 'chat.waitingTimedOutDuration'
      : 'chat.usageStats.duration';

  // Cache per-message token usage extraction
  const messageUsage = useMemo(() => extractMessageUsage(message), [message]);

  // Cache markdown content extraction for better performance
  const markdownContent = useMemo(() => {
    // Only extract for user and assistant messages that need copy functionality
    if (message.type === 'user' || message.type === 'assistant') {
      return extractMarkdownContent(message);
    }
    return '';
  }, [message, extractMarkdownContent]);
  const hasCopyableText = markdownContent.trim().length > 0;

  const handleCopyMessage = useCallback(async () => {
    // Prevent copying if message is empty or already in "copied" state
    if (!hasCopyableText || copiedMessageIndex === messageIndex) return;

    const success = await copyToClipboard(markdownContent);
    if (success) {
      setCopiedMessageIndex(messageIndex);

      // Clear any existing timeout before setting new one
      if (copyTimeoutRef.current !== null) {
        window.clearTimeout(copyTimeoutRef.current);
      }

      // Set new timeout and store ID for cleanup
      copyTimeoutRef.current = window.setTimeout(() => {
        setCopiedMessageIndex(null);
        copyTimeoutRef.current = null;
      }, 1500);
    }
  }, [hasCopyableText, markdownContent, messageIndex, copiedMessageIndex]);

  const handleQuoteMessage = useCallback(() => {
    if (!hasCopyableText) return;
    if (!quoteToChatInput(markdownContent)) return;
    setQuotedMessageIndex(messageIndex);
    if (quoteTimeoutRef.current !== null) {
      window.clearTimeout(quoteTimeoutRef.current);
    }
    quoteTimeoutRef.current = window.setTimeout(() => {
      setQuotedMessageIndex(null);
      quoteTimeoutRef.current = null;
    }, 1500);
  }, [hasCopyableText, markdownContent, messageIndex]);

  // Cleanup timeout on unmount to prevent memory leaks
  useEffect(() => {
    return () => {
      if (copyTimeoutRef.current !== null) {
        window.clearTimeout(copyTimeoutRef.current);
        copyTimeoutRef.current = null;
      }
      if (quoteTimeoutRef.current !== null) {
        window.clearTimeout(quoteTimeoutRef.current);
        quoteTimeoutRef.current = null;
      }
    };
  }, []);

  // Memoize blocks and grouped blocks to avoid recalculation on every render
  const blocks = useMemo(() => getContentBlocks(message), [message, getContentBlocks]);
  const shouldSuppressStreamingConnectHint = message.__suppressStreamingConnectHint === true;
  const isEmptyStreamingPlaceholder =
    message.type === 'assistant' &&
    isMessageStreaming &&
    !shouldSuppressStreamingConnectHint &&
    blocks.length === 0 &&
    !(message.content && message.content.trim().length > 0);
  const hasTextOutput = hasAssistantTextOutput(message, blocks);
  const shouldShowStreamingFooter =
    message.type === 'assistant' && isMessageStreaming && hasTextOutput;

  // Ref to track the last auto-expanded thinking block index to avoid overriding user interaction
  const lastAutoExpandedIndexRef = useRef<number>(-1);

  // Default to the latest thinking block being open for both historical and
  // streaming assistant messages. User-toggled blocks are left untouched so a
  // manual collapse does not pop open again on the next render.
  useEffect(() => {
    if (message.type !== 'assistant') return;

    const thinkingIndices = blocks
      .map((block, index) => (block.type === 'thinking' ? index : -1))
      .filter((index) => index !== -1);

    if (thinkingIndices.length === 0) return;

    const lastThinkingIndex = thinkingIndices[thinkingIndices.length - 1];

    if (lastThinkingIndex === lastAutoExpandedIndexRef.current) return;

    setExpandedThinking((prev) => {
      let changed = false;
      const newState = { ...prev };

      thinkingIndices.forEach((idx) => {
        if (manuallyExpandedThinking[idx]) return;

        const shouldExpand = idx === lastThinkingIndex;
        if (newState[idx] !== shouldExpand) {
          newState[idx] = shouldExpand;
          changed = true;
        }
      });

      return changed ? newState : prev;
    });
    lastAutoExpandedIndexRef.current = lastThinkingIndex;
  }, [blocks, manuallyExpandedThinking, message.type]);

  const groupedBlocks = useMemo(() => groupBlocks(blocks), [blocks]);
  const assistantMessageSections = useMemo(
    () => groupAssistantMessageSections(groupedBlocks),
    [groupedBlocks],
  );

  // Register user message DOM node for anchor navigation
  // Must be called before any early returns to satisfy React hooks rules
  const anchorRefCallback = useCallback(
    (node: HTMLDivElement | null) => {
      if (message.type === 'user' && onNodeRef) {
        onNodeRef(messageKey, node);
      }
    },
    [message.type, messageKey, onNodeRef],
  );

  const isProviderNotConfigured =
    message.type === 'error' && isProviderNotConfiguredError(getMessageText(message));
  const errorDiagnosticPattern = useMemo(
    () =>
      message.type === 'error' && !isProviderNotConfigured
        ? matchErrorPattern(getMessageText(message))
        : null,
    [message, isProviderNotConfigured, getMessageText],
  );

  const renderGroupedBlocks = () => {
    if (message.type === 'error') {
      if (isProviderNotConfigured) {
        return (
          <ProviderNotConfiguredCard t={t} onNavigateToSettings={onNavigateToProviderSettings} />
        );
      }
      return (
        <>
          <MarkdownBlock content={getMessageText(message)} />
          {errorDiagnosticPattern && (
            <ErrorDiagnosticCard
              t={t}
              pattern={errorDiagnosticPattern}
              onNavigateToDependencySettings={onNavigateToDependencySettings}
            />
          )}
        </>
      );
    }

    if (isEmptyStreamingPlaceholder) {
      if (suppressAssistantResponseStatus) return null;

      const providerLabel = getProviderDisplayName(currentProvider, t);
      return (
        <AssistantResponseStatus
          payload={
            message.__assistantResponseStatus ?? {
              phase: '',
              providerLabel,
              title: t('chat.streamingConnected', { provider: providerLabel }),
              active: true,
            }
          }
        />
      );
    }

    return groupedBlocks.map(renderGroupedBlock);
  };

  const renderGroupedBlock = (grouped: GroupedBlock): ReactNode => {
    if (grouped.type === 'read_group') {
      const readItems = grouped.blocks.map((b) => {
        const block = b as {
          type: 'tool_use';
          id?: string;
          name?: string;
          input?: Record<string, unknown>;
        };
        return {
          name: block.name,
          input: block.input,
          result: findToolResult(block.id, messageIndex),
          toolId: block.id,
        };
      });

      if (readItems.length === 1) {
        return (
          <div key={`${messageIndex}-readgroup-${grouped.startIndex}`} className="content-block">
            <ReadToolBlock
              input={readItems[0].input}
              result={readItems[0].result}
              toolId={readItems[0].toolId}
            />
          </div>
        );
      }

      return (
        <div key={`${messageIndex}-readgroup-${grouped.startIndex}`} className="content-block">
          <ReadToolGroupBlock items={readItems} />
        </div>
      );
    }

    if (grouped.type === 'edit_group') {
      const editItems = grouped.blocks.map((b) => {
        const block = b as {
          type: 'tool_use';
          id?: string;
          name?: string;
          input?: Record<string, unknown>;
        };
        return {
          toolId: block.id,
          name: block.name,
          input: block.input,
          result: findToolResult(block.id, messageIndex),
        };
      });

      if (editItems.length === 1) {
        return (
          <div key={`${messageIndex}-editgroup-${grouped.startIndex}`} className="content-block">
            <EditToolBlock items={editItems} />
          </div>
        );
      }

      return (
        <div key={`${messageIndex}-editgroup-${grouped.startIndex}`} className="content-block">
          <EditToolGroupBlock items={editItems} />
        </div>
      );
    }

    if (grouped.type === 'bash_group') {
      const bashItems = grouped.blocks.map((b) => {
        const block = b as {
          type: 'tool_use';
          id?: string;
          name?: string;
          input?: Record<string, unknown>;
        };
        return {
          name: block.name,
          input: block.input,
          result: findToolResult(block.id, messageIndex),
          toolId: block.id,
        };
      });

      if (bashItems.length === 1) {
        return (
          <div key={`${messageIndex}-bashgroup-${grouped.startIndex}`} className="content-block">
            <BashToolBlock
              name={bashItems[0].name}
              input={bashItems[0].input}
              result={bashItems[0].result}
              toolId={bashItems[0].toolId}
            />
          </div>
        );
      }

      return (
        <div key={`${messageIndex}-bashgroup-${grouped.startIndex}`} className="content-block">
          <BashToolGroupBlock items={bashItems} deniedToolIds={window.__deniedToolIds} />
        </div>
      );
    }

    if (grouped.type === 'search_group') {
      const searchItems = grouped.blocks.map((b) => {
        const block = b as {
          type: 'tool_use';
          id?: string;
          name?: string;
          input?: Record<string, unknown>;
        };
        return {
          name: block.name,
          input: block.input,
          result: findToolResult(block.id, messageIndex),
        };
      });

      if (searchItems.length === 1) {
        return (
          <div key={`${messageIndex}-searchgroup-${grouped.startIndex}`} className="content-block">
            <ContentBlockRenderer
              block={grouped.blocks[0]}
              blockIndex={grouped.startIndex}
              messageIndex={messageIndex}
              messageType={message.type}
              isStreaming={isMessageStreaming}
              isThinkingExpanded={false}
              isThinking={isThinking}
              isLastMessage={isLast}
              isLastBlock={grouped.startIndex === blocks.length - 1}
              t={t}
              onToggleThinking={noopThinkingToggle}
              findToolResult={findToolResult}
            />
          </div>
        );
      }

      return (
        <div key={`${messageIndex}-searchgroup-${grouped.startIndex}`} className="content-block">
          <SearchToolGroupBlock items={searchItems} />
        </div>
      );
    }

    const { block, originalIndex: blockIndex } = grouped;

    return (
      <div key={`${messageIndex}-${blockIndex}`} className="content-block">
        <ContentBlockRenderer
          block={block}
          blockIndex={blockIndex}
          messageIndex={messageIndex}
          messageType={message.type}
          isStreaming={isMessageStreaming}
          isThinkingExpanded={isThinkingExpanded(blockIndex)}
          isThinking={isThinking}
          isLastMessage={isLast}
          isLastBlock={blockIndex === blocks.length - 1}
          t={t}
          onToggleThinking={toggleThinking}
          findToolResult={findToolResult}
        />
      </div>
    );
  };

  const shouldUseAssistantSectionedLayout =
    message.type === 'assistant' && !isEmptyStreamingPlaceholder && groupedBlocks.length > 0;

  const getAssistantSectionLabel = (kind: AssistantMessageSectionKind): string => {
    if (kind === 'thinking') return t('chat.messageSections.thinking');
    if (kind === 'tools') return t('chat.messageSections.tools');
    return t('chat.messageSections.output');
  };

  const renderAssistantSectionedBlocks = (): ReactNode =>
    assistantMessageSections.map((section, sectionIndex) => {
      const sectionKey = `${messageIndex}-${section.kind}-${section.startIndex}`;

      if (section.kind !== 'output') {
        return <Fragment key={sectionKey}>{section.items.map(renderGroupedBlock)}</Fragment>;
      }

      return (
        <section
          key={sectionKey}
          className={`assistant-message-answer-section${sectionIndex > 0 ? ' has-leading-divider' : ''}`}
          aria-label={getAssistantSectionLabel(section.kind)}
        >
          {section.items.map(renderGroupedBlock)}
        </section>
      );
    });

  const renderMessageContent = (): ReactNode => (
    <>
      {shouldUseAssistantSectionedLayout ? renderAssistantSectionedBlocks() : renderGroupedBlocks()}
      {shouldShowStreamingFooter && (
        <AssistantStreamingFooter
          elapsedMs={message.__assistantResponseStatus?.elapsedMs}
          startedAt={loadingStartTime}
          t={t}
        />
      )}
    </>
  );

  const messageContentClassName = `message-content${shouldUseAssistantSectionedLayout ? ' assistant-sectioned-message-content' : ''}`;

  if (renderMode === 'response-segment') {
    const responseSegmentContentClassName = `message-response-segment-content ${message.type}${
      shouldUseAssistantSectionedLayout ? ' assistant-sectioned-message-content' : ''
    }`;
    return (
      <div className={responseSegmentContentClassName}>
        {shouldUseAssistantSectionedLayout
          ? renderAssistantSectionedBlocks()
          : renderGroupedBlocks()}
      </div>
    );
  }

  // 判断是否为用户或助手消息（需要显示头像）
  const showAvatar = message.type === 'user' || message.type === 'assistant';

  return (
    <div
      className={`message ${message.type}${isLast ? ' is-last-message' : ''}${isProviderNotConfigured ? ' provider-not-configured' : ''}${withinResponseGroup ? ' in-response-group' : ''}${shouldAnimateIn ? ' animate-in' : ''}${shouldAnimateOut ? ' animate-out' : ''}`}
      ref={anchorRefCallback}
      data-message-anchor-id={message.type === 'user' ? messageKey : undefined}
    >
      {/* Avatar row - only for user and assistant messages */}
      {showAvatar ? (
        <div className="message-avatar-row">
          <MessageAvatar
            type={message.type}
            currentProvider={currentProvider}
            avatarConfig={avatarConfig}
            userLabel={t('chat.avatarUser')}
            assistantLabel={getProviderDisplayName(currentProvider, t)}
          />
          <div className="message-content-wrapper">
            {/* Timestamp and copy button for user messages */}
            {message.type === 'user' && message.timestamp && (
              <div className="message-header-row">
                <div className="message-timestamp-header">{formatTime(message.timestamp)}</div>
                {hasCopyableText && (
                  <>
                    <CopyButton
                      className="message-copy-btn-inline"
                      isCopied={copiedMessageIndex === messageIndex}
                      onClick={handleCopyMessage}
                      copyLabel={t('markdown.copyMessage')}
                      copySuccessText={t('markdown.copySuccess')}
                    />
                    <QuoteButton
                      className="message-copy-btn-inline"
                      isQuoted={quotedMessageIndex === messageIndex}
                      onClick={handleQuoteMessage}
                      quoteLabel={t('markdown.quoteMessage', 'Quote message')}
                      quoteSuccessText={t('markdown.quoteSuccess', 'Quoted!')}
                    />
                  </>
                )}
              </div>
            )}

            {/* Copy button for assistant messages only */}
            {message.type === 'assistant' && !isMessageStreaming && hasCopyableText && (
              <>
                <CopyButton
                  isCopied={copiedMessageIndex === messageIndex}
                  onClick={handleCopyMessage}
                  copyLabel={t('markdown.copyMessage')}
                  copySuccessText={t('markdown.copySuccess')}
                />
                <QuoteButton
                  isQuoted={quotedMessageIndex === messageIndex}
                  onClick={handleQuoteMessage}
                  quoteLabel={t('markdown.quoteMessage', 'Quote message')}
                  quoteSuccessText={t('markdown.quoteSuccess', 'Quoted!')}
                />
              </>
            )}

            <div className={messageContentClassName}>{renderMessageContent()}</div>

            {/* Usage stats bar after completed assistant message */}
            {message.type === 'assistant' && !isMessageStreaming && (
              <MessageUsageStats
                inputTokens={messageUsage?.inputTokens ?? null}
                outputTokens={messageUsage?.outputTokens ?? null}
                cacheCreationTokens={messageUsage?.cacheCreationTokens ?? null}
                cacheReadTokens={messageUsage?.cacheReadTokens ?? null}
                costUsd={messageUsage?.costUsd ?? null}
                detailedOutputEnabled={detailedOutputEnabled}
                durationMs={typeof message.durationMs === 'number' ? message.durationMs : null}
                durationLabelKey={durationLabelKey}
                t={t}
              />
            )}
          </div>
        </div>
      ) : (
        <>
          {/* Role label for non-user/assistant messages — hidden for notification types */}
          {message.type !== 'notification' && message.type !== 'task_notification' && (
            <div className="message-role-label">{message.type}</div>
          )}

          <div className={messageContentClassName}>{renderMessageContent()}</div>

          {/* Usage stats bar for non-avatar assistant message */}
          {message.type === 'assistant' && !isMessageStreaming && (
            <MessageUsageStats
              inputTokens={messageUsage?.inputTokens ?? null}
              outputTokens={messageUsage?.outputTokens ?? null}
              cacheCreationTokens={messageUsage?.cacheCreationTokens ?? null}
              cacheReadTokens={messageUsage?.cacheReadTokens ?? null}
              costUsd={messageUsage?.costUsd ?? null}
              detailedOutputEnabled={detailedOutputEnabled}
              durationMs={typeof message.durationMs === 'number' ? message.durationMs : null}
              durationLabelKey={durationLabelKey}
              t={t}
            />
          )}
        </>
      )}
    </div>
  );
});
