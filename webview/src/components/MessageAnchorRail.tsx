import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import type { ClaudeMessage } from '../types';
import type { LocalizeMessageFn } from '../utils/messageUtils';
import { getMessageKey, getMessageText } from '../utils/messageUtils';
import { formatTime } from '../utils/helpers';
import { copyToClipboard, extractMarkdownContent } from '../utils/copyUtils';
import { createLocalizeMessage } from '../utils/localizationUtils';
import type { MessageListRevealHandle } from './ConversationSearch/types';

interface UserMessageItem {
  id: string;
  turn: number;
  timeLabel: string;
  text: string;
  copyText: string;
  searchText: string;
}

interface MessageAnchorRailProps {
  messages: ClaudeMessage[];
  /** Number of messages hidden by the collapse feature. Kept for call-site compatibility. */
  collapsedCount?: number;
  containerRef: React.RefObject<HTMLDivElement | null>;
  messageNodeMap: React.RefObject<Map<string, HTMLDivElement>>;
  messageListRef?: React.RefObject<MessageListRevealHandle | null>;
  addToast?: (message: string, type?: 'success' | 'error' | 'info' | 'warning') => void;
}

// eslint-disable-next-line no-control-regex -- strip non-printing characters before search and display
const CONTROL_CHARS_RE = /[\x00-\x08\x0B\x0C\x0E-\x1F\x7F\u200B-\u200D\uFEFF]/g;
const HIGHLIGHT_CLASS_NAME = 'is-user-panel-highlight';
const MAX_SCROLL_ATTEMPTS = 60;

export function sampleAnchorItems<T>(items: readonly T[], maxItems: number): T[] {
  if (maxItems <= 0 || items.length === 0) return [];
  if (items.length <= maxItems) return [...items];
  if (maxItems === 1) return [items[0]];

  const sampled: T[] = [];
  const lastIndex = items.length - 1;
  for (let index = 0; index < maxItems; index += 1) {
    const sourceIndex = Math.round((index * lastIndex) / (maxItems - 1));
    sampled.push(items[sourceIndex]);
  }
  return sampled;
}

function normalizeText(text: string): string {
  return text.replace(CONTROL_CHARS_RE, '').replace(/\s+/g, ' ').trim();
}

function getSearchText(item: UserMessageItem): string {
  return `${item.timeLabel} ${item.text}`.toLocaleLowerCase();
}

function buildUserMessageItems(
  messages: ClaudeMessage[],
  localizeMessage: LocalizeMessageFn,
  t: TFunction,
): UserMessageItem[] {
  const items: UserMessageItem[] = [];
  for (let index = 0; index < messages.length; index++) {
    const message = messages[index];
    if (message.type !== 'user') continue;
    const text = normalizeText(getMessageText(message, localizeMessage, t));
    if (!text) continue;
    const copyText = extractMarkdownContent(message).trim() || text;
    const timeLabel = formatTime(message.timestamp);
    const item: UserMessageItem = {
      id: getMessageKey(message, index),
      turn: items.length + 1,
      timeLabel,
      text,
      copyText,
      searchText: '',
    };
    item.searchText = getSearchText(item);
    items.push(item);
  }
  return items;
}

function renderHighlightedText(text: string, query: string): ReactNode {
  const trimmed = query.trim();
  if (!trimmed) return text;

  const source = text.toLocaleLowerCase();
  const needle = trimmed.toLocaleLowerCase();
  const parts: ReactNode[] = [];
  let cursor = 0;
  let matchIndex = source.indexOf(needle, cursor);

  while (matchIndex >= 0) {
    if (matchIndex > cursor) {
      parts.push(text.slice(cursor, matchIndex));
    }
    const end = matchIndex + needle.length;
    parts.push(<mark key={`${matchIndex}-${end}`}>{text.slice(matchIndex, end)}</mark>);
    cursor = end;
    matchIndex = source.indexOf(needle, cursor);
  }

  if (cursor < text.length) {
    parts.push(text.slice(cursor));
  }
  return parts.length > 0 ? parts : text;
}

export const MessageAnchorRail = memo(function MessageAnchorRail({
  messages,
  containerRef,
  messageNodeMap,
  messageListRef,
  addToast,
}: MessageAnchorRailProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeMessageId, setActiveMessageId] = useState<string | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const highlightTimerRef = useRef<number | null>(null);
  const scrollAttemptTokenRef = useRef(0);
  const localizeMessage = useMemo(() => createLocalizeMessage(t), [t]);

  const userMessages = useMemo(
    () => buildUserMessageItems(messages, localizeMessage, t),
    [localizeMessage, messages, t],
  );
  const filteredMessages = useMemo(() => {
    const keyword = query.trim().toLocaleLowerCase();
    if (!keyword) return userMessages;
    return userMessages.filter((item) => item.searchText.includes(keyword));
  }, [query, userMessages]);

  const clearHighlightTimer = useCallback(() => {
    if (highlightTimerRef.current !== null) {
      window.clearTimeout(highlightTimerRef.current);
      highlightTimerRef.current = null;
    }
  }, []);

  const notify = useCallback(
    (message: string, type: 'success' | 'error' | 'info' | 'warning' = 'info') => {
      addToast?.(message, type);
    },
    [addToast],
  );

  const closePanel = useCallback(() => {
    setOpen(false);
  }, []);

  const openPanel = useCallback(() => {
    setOpen(true);
    window.setTimeout(() => searchInputRef.current?.focus(), 120);
  }, []);

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closePanel();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, closePanel]);

  useEffect(() => {
    return () => {
      scrollAttemptTokenRef.current += 1;
      clearHighlightTimer();
    };
  }, [clearHighlightTimer]);

  const copyMessage = useCallback(
    async (item: UserMessageItem) => {
      const success = await copyToClipboard(item.copyText);
      notify(
        success ? t('chat.userPanel.copyOneSuccess') : t('chat.userPanel.copyFailed'),
        success ? 'success' : 'error',
      );
    },
    [notify, t],
  );

  const highlightNode = useCallback(
    (node: HTMLDivElement, messageId: string) => {
      clearHighlightTimer();
      document.querySelectorAll(`.${HIGHLIGHT_CLASS_NAME}`).forEach((el) => {
        el.classList.remove(HIGHLIGHT_CLASS_NAME);
      });
      node.classList.add(HIGHLIGHT_CLASS_NAME);
      setActiveMessageId(messageId);
      highlightTimerRef.current = window.setTimeout(() => {
        node.classList.remove(HIGHLIGHT_CLASS_NAME);
        setActiveMessageId((current) => (current === messageId ? null : current));
        highlightTimerRef.current = null;
      }, 1800);
    },
    [clearHighlightTimer],
  );

  const scrollToMessage = useCallback(
    (item: UserMessageItem) => {
      messageListRef?.current?.revealAll();
      const scrollAttemptToken = scrollAttemptTokenRef.current + 1;
      scrollAttemptTokenRef.current = scrollAttemptToken;

      let attempts = 0;
      const tryScroll = () => {
        if (scrollAttemptTokenRef.current !== scrollAttemptToken) return;
        const node = messageNodeMap.current?.get(item.id);
        const container = containerRef.current;
        if (!node || !container) {
          attempts += 1;
          if (attempts < MAX_SCROLL_ATTEMPTS) {
            window.requestAnimationFrame(tryScroll);
          } else if (scrollAttemptTokenRef.current === scrollAttemptToken) {
            notify(t('chat.userPanel.jumpFailed'), 'warning');
          }
          return;
        }

        const containerRect = container.getBoundingClientRect();
        const nodeRect = node.getBoundingClientRect();
        const targetTop =
          container.scrollTop + (nodeRect.top - containerRect.top) - container.clientHeight * 0.28;
        container.scrollTo({
          top: Math.max(0, targetTop),
          behavior: 'smooth',
        });
        highlightNode(node, item.id);
        notify(t('chat.userPanel.jumpSuccess'), 'info');
      };

      window.requestAnimationFrame(tryScroll);
    },
    [containerRef, highlightNode, messageListRef, messageNodeMap, notify, t],
  );

  if (userMessages.length === 0) return null;

  const panelTitleId = 'messages-user-panel-title';

  return (
    <>
      <button
        type="button"
        className={`messages-user-panel-handle${open ? ' is-open' : ''}`}
        onClick={open ? closePanel : openPanel}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls="messages-user-panel"
        aria-label={open ? t('chat.userPanel.close') : t('chat.userPanel.openLabel')}
      >
        <span className="messages-user-panel-handle-inner" aria-hidden="true">
          <span className="messages-user-panel-handle-line" />
          <span className="messages-user-panel-handle-text">{t('chat.userPanel.handle')}</span>
        </span>
        <span className="messages-user-panel-handle-badge">{userMessages.length}</span>
      </button>

      <div
        className={`messages-user-panel-scrim${open ? ' is-open' : ''}`}
        onClick={closePanel}
        aria-hidden="true"
      />

      <aside
        id="messages-user-panel"
        className={`messages-user-panel${open ? ' is-open' : ''}`}
        role="dialog"
        aria-modal="false"
        aria-labelledby={panelTitleId}
        aria-hidden={open ? undefined : true}
        inert={open ? undefined : true}
      >
        <div className="messages-user-panel-head">
          <div>
            <h2 id={panelTitleId}>{t('chat.userPanel.title')}</h2>
            <p>{t('chat.userPanel.subtitle', { count: userMessages.length })}</p>
          </div>
        </div>

        <div className="messages-user-panel-tools">
          <label className="messages-user-panel-search">
            <span className="codicon codicon-search" aria-hidden="true" />
            <input
              ref={searchInputRef}
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t('chat.userPanel.searchPlaceholder')}
              aria-label={t('chat.userPanel.searchPlaceholder')}
            />
          </label>
        </div>

        {filteredMessages.length === 0 ? (
          <div className="messages-user-panel-empty" role="status">
            {t('chat.userPanel.noResults')}
          </div>
        ) : (
          <div className="messages-user-panel-list" role="list">
            {filteredMessages.map((item) => (
              <article
                key={item.id}
                className={`messages-user-panel-card${activeMessageId === item.id ? ' is-active' : ''}`}
                role="listitem"
              >
                <div className="messages-user-panel-card-top">
                  <span>{item.timeLabel || t('chat.userPanel.noTime')}</span>
                  <span>{t('chat.userPanel.turn', { count: item.turn })}</span>
                </div>
                <p>{renderHighlightedText(item.text, query)}</p>
                <div className="messages-user-panel-actions">
                  <button type="button" onClick={() => copyMessage(item)}>
                    {t('chat.userPanel.copy')}
                  </button>
                  <button type="button" className="primary" onClick={() => scrollToMessage(item)}>
                    {t('chat.userPanel.jump')}
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}

        <div className="messages-user-panel-foot">
          <span>
            {t('chat.userPanel.count', {
              filtered: filteredMessages.length,
              total: userMessages.length,
            })}
          </span>
          <span>{t('chat.userPanel.escHint')}</span>
        </div>
      </aside>
    </>
  );
});
