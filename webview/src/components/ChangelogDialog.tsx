import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ChangelogEntry } from '../version/changelog';
import { summarizeChangelog, escapeHtml, type ChangelogSummary } from '../version/changelogSummary';
import { BaseDialog } from './shared/BaseDialog';
import { ChevronLeftIcon, ChevronRightIcon, CloseIcon } from './Icons';
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

const EMPTY_SUMMARY: ChangelogSummary = { sections: [], loose: [], stats: [], total: 0 };

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
  // Hero 统计用第一语言 summary(版本级统计胶囊)
  const heroSummary = contentParts.length > 0 ? summarizeChangelog(contentParts[0]) : EMPTY_SUMMARY;

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} ariaLabel={t('changelog.title')} animation="pop">
      <div className="changelog-dialog wn-hero">
        {/* Hero 区:渐变 + 光晕 + 版本号 + 统计胶囊 */}
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

        {/* Footer:翻页 + Got it */}
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

          <ClickSpark>
            <button className="changelog-got-it" onClick={onClose}>
              {t('changelog.close')}
            </button>
          </ClickSpark>
        </div>
      </div>
    </BaseDialog>
  );
};

export default ChangelogDialog;
