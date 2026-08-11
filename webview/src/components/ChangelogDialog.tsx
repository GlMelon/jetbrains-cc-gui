import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ChangelogEntry } from '../version/changelog';
import { summarizeChangelog, escapeHtml, type ChangelogSummary } from '../version/changelogSummary';
import { BaseDialog } from './shared/BaseDialog';
import { CloseIcon, SearchIcon } from './Icons';
import { GradientText, ClickSpark } from './react-bits';

interface ChangelogDialogProps {
  isOpen: boolean;
  onClose: () => void;
  entries: ChangelogEntry[];
  initialPage?: number;
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
 * Extract searchable text from changelog content for filtering
 */
function extractSearchText(entry: ChangelogEntry): string {
  const { en, zh } = entry.content;
  return `${entry.version} ${entry.date} ${en || ''} ${zh || ''}`.toLowerCase();
}

const EMPTY_SUMMARY: ChangelogSummary = { sections: [], loose: [], stats: [], total: 0 };

const ChangelogDialog = ({ isOpen, onClose, entries, initialPage = 0 }: ChangelogDialogProps) => {
  const { t } = useTranslation();
  const [currentPage, setCurrentPage] = useState(initialPage);
  const [searchQuery, setSearchQuery] = useState('');
  const [pageInput, setPageInput] = useState('');

  // Filter entries based on search query
  const filteredEntries = useMemo(() => {
    if (!searchQuery.trim()) return entries;
    const query = searchQuery.toLowerCase();
    return entries.filter((entry) => {
      const searchText = extractSearchText(entry);
      return searchText.includes(query);
    });
  }, [entries, searchQuery]);

  // Reset page when dialog opens or search changes
  useEffect(() => {
    if (isOpen) {
      setCurrentPage(Math.min(initialPage, filteredEntries.length - 1));
      setSearchQuery('');
      setPageInput('');
    }
  }, [isOpen, initialPage]);

  useEffect(() => {
    // When search changes, reset to first matching entry
    if (searchQuery) {
      setCurrentPage(0);
    }
  }, [searchQuery]);

  // Custom keyboard navigation (arrows + escape)
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') {
        setCurrentPage(prev => Math.max(0, prev - 1));
      } else if (e.key === 'ArrowRight') {
        setCurrentPage(prev => Math.min(filteredEntries.length - 1, prev + 1));
      }
      // ESC is handled by BaseDialog
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, filteredEntries.length]);

  const handlePageJump = useCallback(() => {
    const page = parseInt(pageInput, 10);
    if (!isNaN(page) && page >= 1 && page <= filteredEntries.length) {
      setCurrentPage(page - 1);
      setPageInput('');
    }
  }, [pageInput, filteredEntries.length]);

  const handlePageInputChange = useCallback((value: string) => {
    // Only allow numbers
    if (value === '' || /^\d+$/.test(value)) {
      setPageInput(value);
    }
  }, []);

  const handlePageInputKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handlePageJump();
    }
  }, [handlePageJump]);

  if (!isOpen || entries.length === 0) return null;

  // Get the currently displayed entry (from filtered list)
  const safeCurrentPage = Math.min(currentPage, filteredEntries.length - 1);
  const entry = filteredEntries[safeCurrentPage];
  if (!entry) return null;

  const contentParts = resolveContent(entry);
  const totalPages = filteredEntries.length;
  // Hero 统计用第一语言 summary(版本级统计胶囊)
  const heroSummary = contentParts.length > 0 ? summarizeChangelog(contentParts[0]) : EMPTY_SUMMARY;

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} ariaLabel={t('changelog.title')} animation="pop">
      <div className="changelog-dialog-v2">
        {/* 左侧边栏 */}
        <div className="changelog-sidebar">
          <div className="changelog-sidebar-header">
            <div className="changelog-sidebar-title">{t('changelog.versionList')}</div>
            <div className="changelog-sidebar-search">
              <span className="changelog-sidebar-search-icon">
                <SearchIcon size={14} />
              </span>
              <input
                type="text"
                className="changelog-sidebar-search-input"
                placeholder={t('changelog.searchPlaceholder')}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
          </div>
          <div className="changelog-sidebar-list">
            {filteredEntries.map((e, idx) => {
              const isActive = e.version === entry.version;
              const isLatest = entries[0]?.version === e.version;
              return (
                <div
                  key={e.version}
                  className={`changelog-version-item ${isActive ? 'active' : ''}`}
                  onClick={() => setCurrentPage(idx)}
                >
                  <span className="changelog-version-item-dot" />
                  <div className="changelog-version-item-info">
                    <div className="changelog-version-item-number">v{e.version}</div>
                    <div className="changelog-version-item-date">{e.date}</div>
                  </div>
                  {isLatest && (
                    <span className="changelog-version-item-badge">
                      {t('settings.versionHistoryLatest')}
                    </span>
                  )}
                </div>
              );
            })}
            {filteredEntries.length === 0 && (
              <div className="changelog-sidebar-empty">
                {t('changelog.noResults')}
              </div>
            )}
          </div>
        </div>

        {/* 右侧内容区 */}
        <div className="changelog-main">
          {/* Hero 区域 */}
          <div className="wn-b-hero">
            <div className="wn-b-aura" aria-hidden="true" />
            <div className="wn-b-hero-main">
              <div className="wn-b-eyebrow">
                <span className="wn-b-gift" aria-hidden="true">🎁</span>
                <span>{t('changelog.title')}</span>
              </div>
              <GradientText
                className="wn-b-ver"
                colors={['var(--accent-primary, #4ea1ff)', 'var(--c-perf, #7c3aed)']}
                angle={135}
              >
                v{entry.version}
              </GradientText>
              <div className="wn-b-date">{entry.date}</div>
              {heroSummary.stats.length > 0 && (
                <div className="wn-b-stats">
                  {heroSummary.stats.map((st) => (
                    <span key={st.kind} className="wn-b-stat" data-kind={st.kind}>
                      <i className="wn-b-stat-dot" aria-hidden="true" />
                      <span className="wn-b-stat-num">{st.count}</span>
                      <span className="wn-b-stat-label">
                        {t(`changelog.statLabel.${st.kind}`, st.kind)}
                      </span>
                    </span>
                  ))}
                </div>
              )}
            </div>
            <button className="wn-b-close" onClick={onClose} aria-label={t('changelog.close')}>
              <CloseIcon size={16} />
            </button>
          </div>

          {/* Body:分语言块,每块渲染其 sections(彩色 chip + 标题 + 列表) */}
          <div className="wn-b-body">
            {contentParts.map((part, idx) => {
              const summary = summarizeChangelog(part);
              return (
                <div 
                  key={idx} 
                  className="wn-b-lang"
                  style={{ '--stagger-delay': `${idx * 100}ms` } as React.CSSProperties}
                >
                  {idx > 0 && <hr className="wn-b-divider" />}
                  {summary.loose.length > 0 && (
                    <div className="wn-b-loose">
                      {summary.loose.map((p, i) => (
                        <p key={i} dangerouslySetInnerHTML={{ __html: p }} />
                      ))}
                    </div>
                  )}
                  {summary.sections.map((sec, si) => (
                    <div key={si} className="wn-b-grp" data-kind={sec.kind}>
                      <div className="wn-b-grp-head">
                        <span className="wn-b-chip" aria-hidden="true" />
                        <span
                          className="wn-b-grp-title"
                          dangerouslySetInnerHTML={{ __html: escapeHtml(sec.head) }}
                        />
                      </div>
                      <ul className="wn-b-list">
                        {sec.items.map((it, ii) => (
                          <li key={ii} dangerouslySetInnerHTML={{ __html: it }} />
                        ))}
                      </ul>
                    </div>
                  ))}
                </div>
              );
            })}
          </div>

          {/* Footer:页码跳转 + Got it */}
          <div className="changelog-footer-v2">
            <div className="changelog-page-jump">
              <span className="changelog-page-jump-label">{t('changelog.jumpTo')}</span>
              <input
                type="text"
                className="changelog-page-input"
                value={pageInput}
                onChange={(e) => handlePageInputChange(e.target.value)}
                onKeyDown={handlePageInputKeyDown}
                placeholder={`${safeCurrentPage + 1}`}
              />
              <span className="changelog-page-total">/ {totalPages}</span>
              <button className="changelog-go-btn" onClick={handlePageJump}>
                {t('changelog.go')}
              </button>
            </div>
            <ClickSpark>
              <button className="changelog-got-it" onClick={onClose}>
                {t('changelog.close')}
              </button>
            </ClickSpark>
          </div>
        </div>
      </div>
    </BaseDialog>
  );
};

export default ChangelogDialog;
