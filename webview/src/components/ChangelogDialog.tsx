import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ChangelogEntry } from '../version/changelog';
import { BaseDialog } from './shared/BaseDialog';
import { ChevronLeftIcon, ChevronRightIcon, CloseIcon } from './Icons';

interface ChangelogDialogProps {
  isOpen: boolean;
  onClose: () => void;
  entries: ChangelogEntry[];
  initialPage?: number;
}

// emoji 前缀 → 分区语义,用于彩色着色(✨新功能 / 🐛修复 / 🔧改进 / ⚡性能)。
const SECTION_KIND: Record<string, string> = {
  '✨': 'feature',
  '🐛': 'fix',
  '🔧': 'improve',
  '⚡': 'perf',
  '⚡️': 'perf',
};
const SECTION_EMOJI = /^[✨🐛🔧🎉🚀💡⚡️🔥📦🛠️]/;

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** 行内格式化:先转义,再还原 **bold** 与 `code`。 */
function formatInline(text: string): string {
  return escapeHtml(text)
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');
}

/**
 * Resolve content to display. Shows both EN and ZH when both exist,
 * otherwise shows whichever is available.
 */
function resolveContent(entry: ChangelogEntry): string[] {
  const { en, zh } = entry.content;
  const parts: string[] = [];
  if (en) parts.push(en);
  if (zh) parts.push(zh);
  return parts;
}

/**
 * 把单语言 changelog 文本渲染为分区结构:emoji 前缀行(✨/🐛/🔧/⚡…)开启一个
 * .changelog-section,其后的 `- ` 列表项归入该分区并按语义着色。游离内容(无分区
 * 前缀的段落)收入 .changelog-loose。
 */
function renderChangelogMarkdown(text: string): string {
  if (!text) return '';

  const sections: { kind: string; head: string; items: string[] }[] = [];
  const loose: string[] = [];
  let current: { kind: string; head: string; items: string[] } | null = null;

  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;

    if (line.startsWith('- ')) {
      const item = `<li>${formatInline(line.substring(2))}</li>`;
      if (current) {
        current.items.push(item);
      } else {
        loose.push(`<p>${formatInline(line.substring(2))}</p>`);
      }
      continue;
    }

    const emojiMatch = line.match(SECTION_EMOJI);
    if (emojiMatch) {
      const emoji = emojiMatch[0] ?? '';
      current = {
        kind: SECTION_KIND[emoji] ?? 'other',
        head: escapeHtml(line),
        items: [],
      };
      sections.push(current);
      continue;
    }

    // 段落或 P\d 优先级标签:已有分区则并入列表,否则作为游离段落。
    if (current) {
      current.items.push(`<li>${formatInline(line)}</li>`);
    } else {
      loose.push(`<p>${formatInline(line)}</p>`);
    }
  }

  const sectionHtml = sections
    .map((s) => (
      `<div class="changelog-section" data-kind="${s.kind}">` +
      `<div class="changelog-section-head">` +
      `<span class="changelog-section-dot"></span>` +
      `<span class="changelog-section-title">${s.head}</span>` +
      `</div>` +
      `<ul class="changelog-changes">${s.items.join('')}</ul>` +
      `</div>`
    ))
    .join('');

  return loose.length
    ? `<div class="changelog-loose">${loose.join('')}</div>${sectionHtml}`
    : sectionHtml;
}

/** 统计单语言文本的更新项数(`- ` 列表项),用于副标题展示。 */
function countChanges(text: string): number {
  if (!text) return 0;
  let count = 0;
  for (const line of text.split('\n')) {
    if (line.trim().startsWith('- ')) {
      count++;
    }
  }
  return count;
}

const ChangelogDialog = ({ isOpen, onClose, entries, initialPage = 0 }: ChangelogDialogProps) => {
  const { t } = useTranslation();
  const [currentPage, setCurrentPage] = useState(initialPage);

  // Reset page when dialog opens
  useEffect(() => {
    if (isOpen) {
      setCurrentPage(initialPage);
    }
  }, [isOpen, initialPage]);

  // Custom keyboard navigation (arrows + escape)
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') {
        setCurrentPage(prev => Math.max(0, prev - 1));
      } else if (e.key === 'ArrowRight') {
        setCurrentPage(prev => Math.min(entries.length - 1, prev + 1));
      }
      // ESC is handled by BaseDialog
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, entries.length]);

  const handlePrev = useCallback(() => {
    setCurrentPage(prev => Math.max(0, prev - 1));
  }, []);

  const handleNext = useCallback(() => {
    setCurrentPage(prev => Math.min(entries.length - 1, prev + 1));
  }, [entries.length]);

  if (!isOpen || entries.length === 0) return null;

  const entry = entries[currentPage];
  const contentParts = resolveContent(entry);
  const totalPages = entries.length;
  const hasPrev = currentPage > 0;
  const hasNext = currentPage < totalPages - 1;
  const changeCount = contentParts.length > 0 ? countChanges(contentParts[0]) : 0;

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} ariaLabel={t('changelog.title')}>
      <div className="changelog-dialog">
        {/* Header */}
        <div className="changelog-header">
          <div className="changelog-header-icon" aria-hidden="true">🎁</div>
          <div className="changelog-header-text">
            <div className="changelog-header-title">
              <h2>{t('changelog.title')}</h2>
              <span className="changelog-version-badge">v{entry.version}</span>
            </div>
            <div className="changelog-header-sub">
              {changeCount > 0
                ? t('changelog.summary', { count: changeCount, date: entry.date })
                : entry.date}
            </div>
          </div>
          <button className="changelog-close-btn" onClick={onClose} aria-label={t('changelog.close')}>
            <CloseIcon size={16} />
          </button>
        </div>

        {/* Body */}
        <div className="changelog-body">
          {contentParts.map((part, idx) => (
            <div key={idx} className="changelog-body-part">
              {idx > 0 && <hr className="changelog-divider" />}
              <div
                className="changelog-content"
                dangerouslySetInnerHTML={{ __html: renderChangelogMarkdown(part) }}
              />
            </div>
          ))}
        </div>

        {/* Footer with pagination */}
        <div className="changelog-footer">
          <button
            className="changelog-nav-btn"
            onClick={handlePrev}
            disabled={!hasPrev}
            aria-label="Previous version"
          >
            <ChevronLeftIcon size={16} />
          </button>

          <div className="changelog-dots">
            {totalPages <= 10 ? (
              entries.map((_, idx) => (
                <button
                  key={idx}
                  className={`changelog-dot ${idx === currentPage ? 'active' : ''}`}
                  onClick={() => setCurrentPage(idx)}
                  aria-label={`Page ${idx + 1}`}
                />
              ))
            ) : (
              <span className="changelog-page-text">
                {t('changelog.page', { current: currentPage + 1, total: totalPages })}
              </span>
            )}
          </div>

          <button
            className="changelog-nav-btn"
            onClick={handleNext}
            disabled={!hasNext}
            aria-label="Next version"
          >
            <ChevronRightIcon size={16} />
          </button>

          <button className="changelog-got-it" onClick={onClose}>
            {t('changelog.close')}
          </button>
        </div>
      </div>
    </BaseDialog>
  );
};

export default ChangelogDialog;
