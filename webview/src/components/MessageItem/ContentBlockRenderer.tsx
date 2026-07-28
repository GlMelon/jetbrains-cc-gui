import { useState, useCallback, memo } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeContentBlock, ToolResultBlock, CompactSummaryMetadata } from '../../types';

import MarkdownBlock from '../MarkdownBlock';
import CollapsibleTextBlock from '../CollapsibleTextBlock';
import {
  BashToolBlock,
  EditToolBlock,
  GenericToolBlock,
  McpToolBlock,
  SkillBlock,
  TaskExecutionBlock,
} from '../toolBlocks';
import { EDIT_TOOL_NAMES, BASH_TOOL_NAMES, TASK_MANAGE_TOOL_NAMES, AGENT_TOOL_NAMES, isToolName, isTransientInternalToolName, normalizeToolName, parseMcpToolName } from '../../utils/toolConstants';
import { TASK_STATUS_COLORS } from '../../utils/messageUtils';
import { codiconToIcon } from '../Icons';

const IMAGE_BLOCK_STYLE: React.CSSProperties = { cursor: 'pointer' };

function normalizeProviderErrorText(text: string | undefined): string {
  return (text ?? '').replace(/\s+/g, ' ').trim();
}

function shouldShowProviderErrorSummary(summary: string, details: string | undefined): boolean {
  const normalizedSummary = normalizeProviderErrorText(summary);
  const normalizedDetails = normalizeProviderErrorText(details);
  return Boolean(
    normalizedSummary &&
    (!normalizedDetails || !normalizedDetails.includes(normalizedSummary))
  );
}

function stringifyBlockValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function BlockDetails({ details, t }: { details?: string; t: TFunction }) {
  if (!details) return null;
  return (
    <details className="provider-event-details">
      <summary>{t('chat.providerError.details')}</summary>
      <pre>{details}</pre>
    </details>
  );
}

function FileChangeBlock({ block, t }: { block: Extract<ClaudeContentBlock, { type: 'file_change' }>; t: TFunction }) {
  const title = block.title || block.summary || block.path || block.type;
  return (
    <div className="provider-event-block file-change-block">
      <div className="provider-event-header">
        <span className="provider-event-icon" aria-hidden="true">●</span>
        <span className="provider-event-title">{title}</span>
      </div>
      <div className="provider-event-meta">
        {block.operation && <span>{block.operation}</span>}
        {block.path && <span>{block.path}</span>}
        {block.status && <span>{block.status}</span>}
      </div>
      {block.summary && block.summary !== title && <div className="provider-event-summary">{block.summary}</div>}
      <BlockDetails details={block.details} t={t} />
    </div>
  );
}

function McpToolCallBlock({ block, t }: { block: Extract<ClaudeContentBlock, { type: 'mcp_tool_call' }>; t: TFunction }) {
  const title = block.title || [block.server, block.tool].filter(Boolean).join('.') || block.summary || block.type;
  const input = stringifyBlockValue(block.input);
  const result = stringifyBlockValue(block.result);
  return (
    <div className="provider-event-block mcp-tool-call-block">
      <div className="provider-event-header">
        <span className="provider-event-icon" aria-hidden="true">◆</span>
        <span className="provider-event-title">{title}</span>
      </div>
      <div className="provider-event-meta">
        {block.server && <span>{block.server}</span>}
        {block.tool && <span>{block.tool}</span>}
        {block.status && <span>{block.status}</span>}
      </div>
      {block.summary && block.summary !== title && <div className="provider-event-summary">{block.summary}</div>}
      {input && <pre className="provider-event-json">{input}</pre>}
      {result && <pre className="provider-event-json">{result}</pre>}
      <BlockDetails details={block.details} t={t} />
    </div>
  );
}

function WebSearchBlock({ block, t }: { block: Extract<ClaudeContentBlock, { type: 'web_search' }>; t: TFunction }) {
  const title = block.title || block.query || block.url || block.summary || block.type;
  return (
    <div className="provider-event-block web-search-block">
      <div className="provider-event-header">
        <span className="provider-event-icon" aria-hidden="true">⌕</span>
        <span className="provider-event-title">{title}</span>
      </div>
      <div className="provider-event-meta">
        {block.query && <span>{block.query}</span>}
        {block.status && <span>{block.status}</span>}
        {block.url && <span>{block.url}</span>}
      </div>
      {block.summary && block.summary !== title && <div className="provider-event-summary">{block.summary}</div>}
      <BlockDetails details={block.details} t={t} />
    </div>
  );
}

function TodoListBlock({ block, t }: { block: Extract<ClaudeContentBlock, { type: 'todo_list' }>; t: TFunction }) {
  const title = block.title || block.summary || block.type;
  return (
    <div className="provider-event-block todo-list-block">
      <div className="provider-event-header">
        <span className="provider-event-icon" aria-hidden="true">☑</span>
        <span className="provider-event-title">{title}</span>
      </div>
      {block.status && <div className="provider-event-meta"><span>{block.status}</span></div>}
      {Array.isArray(block.items) && block.items.length > 0 && (
        <ul className="provider-event-list">
          {block.items.map((item, index) => (
            <li key={index}>
              {item.status && <span className="provider-event-list-status">{item.status}</span>}
              <span>{item.text || item.content || item.title || stringifyBlockValue(item)}</span>
            </li>
          ))}
        </ul>
      )}
      {block.summary && block.summary !== title && <div className="provider-event-summary">{block.summary}</div>}
      <BlockDetails details={block.details} t={t} />
    </div>
  );
}

function ProviderEventBlock({ block, t }: { block: Extract<ClaudeContentBlock, { type: 'provider_event' }>; t: TFunction }) {
  const title = block.title || block.summary || block.itemType || block.eventType || block.type;
  return (
    <div className="provider-event-block provider-diagnostic-block">
      <div className="provider-event-header">
        <span className="provider-event-icon" aria-hidden="true">?</span>
        <span className="provider-event-title">{title}</span>
      </div>
      <div className="provider-event-meta">
        {block.provider && <span>{block.provider}</span>}
        {block.eventType && <span>{block.eventType}</span>}
        {block.itemType && <span>{block.itemType}</span>}
      </div>
      <BlockDetails details={block.details || stringifyBlockValue(block.raw)} t={t} />
    </div>
  );
}

function getImageStyle(isUser: boolean): React.CSSProperties {
  return {
    maxWidth: isUser ? '200px' : '100%',
    maxHeight: isUser ? '150px' : 'auto',
    borderRadius: '8px',
    objectFit: 'contain',
  };
}

/**
 * Get file icon class (consistent with AttachmentList)
 */
function getFileIcon(mediaType?: string): string {
  if (!mediaType) return 'codicon-file';
  if (mediaType.startsWith('text/')) return 'codicon-file-text';
  if (mediaType.includes('json')) return 'codicon-json';
  if (mediaType.includes('javascript') || mediaType.includes('typescript')) return 'codicon-file-code';
  if (mediaType.includes('pdf')) return 'codicon-file-pdf';
  return 'codicon-file';
}

/**
 * Get file extension
 */
function getExtension(fileName?: string): string {
  if (!fileName) return '';
  const parts = fileName.split('.');
  return parts.length > 1 ? parts[parts.length - 1].toUpperCase() : '';
}

/** Format a token count for compact display (e.g., 524835 → "524.8K"). */
function formatCompactTokens(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}K`;
  return String(count);
}

/**
 * Build the compaction-stats subtitle from compact_boundary metadata:
 * "manual · 524.8K → 14.6K · 110s". Returns null when no stats are present.
 */
function formatCompactionStats(meta: CompactSummaryMetadata): string | null {
  const parts: string[] = [];
  if (meta.trigger) parts.push(meta.trigger);
  if (typeof meta.preTokens === 'number') {
    const tokens = typeof meta.postTokens === 'number'
      ? `${formatCompactTokens(meta.preTokens)} → ${formatCompactTokens(meta.postTokens)}`
      : formatCompactTokens(meta.preTokens);
    parts.push(tokens);
  }
  if (typeof meta.durationMs === 'number') parts.push(`${Math.round(meta.durationMs / 1000)}s`);
  return parts.length > 0 ? parts.join(' · ') : null;
}

interface CompactSummaryBlockProps {
  block: {
    type: 'compact_summary';
    title: string;
    content: string;
    metadata?: CompactSummaryMetadata;
  };
  t: TFunction;
}

/**
 * Compact summary block - collapsed by default, click/Enter/Space to expand.
 * Memoized to prevent state reset on parent re-renders during streaming.
 * `block.title` is an i18n key resolved via t() at render time.
 */
const CompactSummaryBlock = memo(function CompactSummaryBlock({ block, t }: CompactSummaryBlockProps) {
  const [expanded, setExpanded] = useState(false);
  const toggleExpanded = useCallback(() => setExpanded(e => !e), []);
  const onKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setExpanded(prev => !prev);
    }
  }, []);
  const meta = block.metadata;
  const hasCountMeta = meta && typeof meta.messagesSummarized === 'number';
  const compactionStats = meta ? formatCompactionStats(meta) : null;
  const hasMeta = hasCountMeta || compactionStats;
  const titleText = t(block.title);
  const toggleLabel = expanded ? t('chat.compactSummary.collapse') : t('chat.compactSummary.expand');

  return (
    <div className="compact-summary-block">
      <div
        className="compact-summary-title"
        role="button"
        tabIndex={0}
        aria-expanded={expanded}
        aria-label={`${titleText} — ${toggleLabel}`}
        onClick={toggleExpanded}
        onKeyDown={onKeyDown}
      >
        <span className="compact-summary-icon" aria-hidden="true">●</span>
        <span className="compact-summary-title-text">{titleText}</span>
        <span className="compact-summary-toggle" aria-hidden="true">{expanded ? '▼' : '▶'}</span>
      </div>
      {hasMeta && (
        <div className="compact-summary-metadata">
          {hasCountMeta && (
            <span className="compact-summary-meta-count">
              {t(
                meta.direction === 'from'
                  ? 'chat.compactSummary.messagesFrom'
                  : 'chat.compactSummary.messagesUpTo',
                { count: meta.messagesSummarized },
              )}
            </span>
          )}
          {compactionStats && (
            <span className="compact-summary-meta-count">{compactionStats}</span>
          )}
          {meta?.userContext && (
            <span className="compact-summary-meta-context">
              {t('chat.compactSummary.userContext', { context: meta.userContext })}
            </span>
          )}
        </div>
      )}
      {expanded && block.content && (
        <div className="compact-summary-content">
          <MarkdownBlock content={block.content} />
        </div>
      )}
    </div>
  );
});

export interface ContentBlockRendererProps {
  block: ClaudeContentBlock;
  blockIndex: number;
  messageIndex: number;
  messageType: string;
  isStreaming: boolean;
  isThinkingExpanded: boolean;
  isThinking: boolean;
  isLastMessage: boolean;
  isLastBlock?: boolean;
  t: TFunction;
  onToggleThinking: (blockIndex: number) => void;
  findToolResult: (toolId: string | undefined, messageIndex: number) => ToolResultBlock | null | undefined;
}

export const ContentBlockRenderer = memo(function ContentBlockRenderer({
  block,
  blockIndex,
  messageIndex,
  messageType,
  isStreaming,
  isThinkingExpanded,
  isThinking,
  isLastMessage,
  isLastBlock = false,
  t,
  onToggleThinking,
  findToolResult,
}: ContentBlockRendererProps): React.ReactElement | null {
  if (block.type === 'text') {
    return messageType === 'user' ? (
      <CollapsibleTextBlock content={block.text ?? ''} />
    ) : (
      <MarkdownBlock
        content={block.text ?? ''}
        isStreaming={isStreaming}
      />
    );
  }

  if (block.type === 'image' && block.src) {
    const handleImageError = (e: React.SyntheticEvent<HTMLImageElement>) => {
      const img = e.currentTarget;
      if (img.dataset.fallback) return;
      const src = block.src ?? '';
      // resource_url 失败时尝试降级到原始 src
      if (block.thumbnailSrc && img.src !== src && !src.startsWith('data:')) {
        img.dataset.fallback = 'true';
        img.src = src;
        return;
      }
      img.dataset.fallback = 'failed';
      img.alt = t('chat.imageLoadFailed');
      img.style.display = 'none';
      const placeholder = img.nextElementSibling;
      if (placeholder && placeholder.classList.contains('image-load-failed')) return;
      const span = document.createElement('span');
      span.className = 'image-load-failed';
      span.textContent = t('chat.imageLoadFailed');
      span.style.cssText = 'color:var(--text-secondary);font-size:12px;padding:8px;';
      img.parentElement?.appendChild(span);
    };

    const handleImagePreview = () => {
      const previewRoot = document.getElementById('image-preview-root');
      const previewSrc = block.previewSrc || block.src;
      if (!previewRoot || !previewSrc) return;

      // Clear previous content safely
      previewRoot.innerHTML = '';

      // Create overlay container
      const overlay = document.createElement('div');
      overlay.className = 'image-preview-overlay';
      overlay.onclick = () => overlay.remove();

      // Create image element safely (prevents XSS)
      const img = document.createElement('img');
      img.src = previewSrc;
      img.alt = t('chat.imagePreview');
      img.className = 'image-preview-content';
      img.onclick = (e) => e.stopPropagation();

      // Create close button
      const closeBtn = document.createElement('div');
      closeBtn.className = 'image-preview-close';
      closeBtn.textContent = '×';
      closeBtn.onclick = (e) => {
        e.stopPropagation();
        overlay.remove();
      };

      overlay.appendChild(img);
      overlay.appendChild(closeBtn);
      previewRoot.appendChild(overlay);
    };

    return (
      <div
        className={`message-image-block ${messageType === 'user' ? 'user-image' : ''}`}
        onClick={handleImagePreview}
        style={IMAGE_BLOCK_STYLE}
        title={t('chat.clickToPreview')}
      >
        <img
          src={block.thumbnailSrc || block.src}
          alt={t('chat.userUploadedImage')}
          style={getImageStyle(messageType === 'user')}
          onError={handleImageError}
        />
      </div>
    );
  }

  if (block.type === 'attachment') {
    const ext = getExtension(block.fileName);
    const displayName = block.fileName || t('chat.unknownFile');
    return (
      <div className="message-attachment-chip" title={displayName}>
        {codiconToIcon(getFileIcon(block.mediaType), 16, { className: 'message-attachment-chip-icon' })}
        {ext && <span className="message-attachment-chip-ext">{ext}</span>}
        <span className="message-attachment-chip-name">{displayName}</span>
      </div>
    );
  }

  if (block.type === 'provider_error') {
    const summary = block.summary || block.details || t('chat.providerError.fallbackSummary');
    const details = block.details || summary;
    const showSummary = shouldShowProviderErrorSummary(summary, block.details);
    const provider = block.provider || '';
    const metadata: string[] = [t('chat.providerError.provider', { provider })];
    if (block.exitCode !== undefined && block.exitCode !== null) {
      metadata.push(t('chat.providerError.exitCode', { exitCode: block.exitCode }));
    }

    return (
      <div className="provider-error-block">
        <div className="provider-error-header">
          <span className="provider-error-icon" aria-hidden="true">!</span>
          <div className="provider-error-heading">
            <span className="provider-error-title">{t('chat.providerError.title')}</span>
            {showSummary && <span className="provider-error-summary">{summary}</span>}
          </div>
        </div>
        <div className="provider-error-meta">
          {metadata.map((item, index) => (
            <span key={index}>{item}</span>
          ))}
        </div>
        {details && (
          <details className="provider-error-details">
            <summary>{t('chat.providerError.details')}</summary>
            <pre>{details}</pre>
          </details>
        )}
      </div>
    );
  }

  if (block.type === 'file_change') {
    return <FileChangeBlock block={block} t={t} />;
  }

  if (block.type === 'mcp_tool_call') {
    return <McpToolCallBlock block={block} t={t} />;
  }

  if (block.type === 'web_search') {
    return <WebSearchBlock block={block} t={t} />;
  }

  if (block.type === 'todo_list') {
    return <TodoListBlock block={block} t={t} />;
  }

  if (block.type === 'provider_event') {
    return <ProviderEventBlock block={block} t={t} />;
  }

  if (block.type === 'thinking') {
    return (
      <div className={`thinking-section${isThinkingExpanded ? ' expanded' : ''}`}>
        <div
          className="thinking-section-header"
          onClick={() => onToggleThinking(blockIndex)}
        >
          <svg className="thinking-section-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M9.5 2A2.5 2.5 0 0112 4.5v15a2.5 2.5 0 01-4.96.44 2.5 2.5 0 01-2.96-3.08 3 3 0 01-.34-5.58 2.5 2.5 0 011.32-4.24 2.5 2.5 0 011.98-3A2.5 2.5 0 019.5 2z" />
            <path d="M14.5 2A2.5 2.5 0 0012 4.5v15a2.5 2.5 0 004.96.44 2.5 2.5 0 002.96-3.08 3 3 0 00.34-5.58 2.5 2.5 0 00-1.32-4.24 2.5 2.5 0 00-1.98-3A2.5 2.5 0 0014.5 2z" />
          </svg>
          <span className="thinking-section-label">
            {isThinking && isLastMessage && isLastBlock
              ? t('common.thinkingProcess')
              : t('common.thinking')}
          </span>
          <span className="thinking-section-duration">
            {isThinking && isLastMessage && isLastBlock ? '...' : ''}
          </span>
          <svg className="thinking-section-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M6 9l6 6 6-6" />
          </svg>
        </div>
        <div className="thinking-section-content">
          {/* H2: content 为 grid 容器(0fr↔1fr 折叠动画)，inner 作为 grid item
              承载 padding/border 并 overflow:hidden，保证折叠时高度归零，
              天然支持未知高度 / 长 Markdown。reduced-motion 由 base.less 全局收口。 */}
          <div className="thinking-section-content-inner">
            <MarkdownBlock
              content={block.thinking ?? block.text ?? t('chat.noThinkingContent')}
              isStreaming={isStreaming}
            />
          </div>
        </div>
      </div>
    );
  }

  if (block.type === 'skill_use') {
    return <SkillBlock block={block} />;
  }

  if (block.type === 'tool_use') {
    if (parseMcpToolName(block.name)) {
      return (
        <McpToolBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
        />
      );
    }

    const toolName = normalizeToolName(block.name ?? '');

    if (toolName === 'todowrite' || toolName === 'update_plan' || TASK_MANAGE_TOOL_NAMES.has(toolName)) {
      return null;
    }

    if (!isStreaming && isTransientInternalToolName(block.name)) {
      return null;
    }

    if (AGENT_TOOL_NAMES.has(toolName)) {
      return (
        <TaskExecutionBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
          toolId={block.id}
          isStreaming={isStreaming}
        />
      );
    }

    if (isToolName(block.name, EDIT_TOOL_NAMES)) {
      return (
        <EditToolBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
          toolId={block.id}
        />
      );
    }

    if (isToolName(block.name, BASH_TOOL_NAMES)) {
      return (
        <BashToolBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
          toolId={block.id}
        />
      );
    }

    return (
      <GenericToolBlock
        name={block.name}
        input={block.input}
        result={findToolResult(block.id, messageIndex)}
        toolId={block.id}
      />
    );
  }

  // Compact notification block - renders as header + indented sub-items
  if (block.type === 'compact_notification') {
    return (
      <div className="compact-notification-block">
        <div className="compact-notification-header">
          {block.headerText}
        </div>
        {block.items.length > 0 && (
          <div className="compact-notification-items">
            {block.items.map((item, idx) => (
              <div key={idx} className="compact-notification-item">
                <span className="compact-notification-prefix">⎿</span>
                <span className="compact-notification-text">{item.text}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  // Compact summary block - collapsed by default, click to expand
  if (block.type === 'compact_summary') {
    return <CompactSummaryBlock block={block} t={t} />;
  }

  // Task notification block - renders as "● summary" with status color
  if (block.type === 'task_notification') {
    // TypeScript narrows block to { type: 'task_notification'; icon; summary; status; detail? }
    const statusColor = TASK_STATUS_COLORS[block.status] || 'text';
    const detail = block.detail;
    const truncatedDetail = detail && detail.length > 300 ? `${detail.slice(0, 300)}…` : detail;
    return (
      <div className={`task-notification-block task-notification-${statusColor}`}>
        <span className="task-notification-icon">{block.icon}</span>
        <span className="task-notification-summary">
          {block.summary}
          {truncatedDetail && (
            <span className="task-notification-detail" title={detail}>{truncatedDetail}</span>
          )}
        </span>
      </div>
    );
  }

  return null;
});
