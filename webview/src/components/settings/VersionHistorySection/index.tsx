import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useUIState } from '../../../contexts/UIStateContext';
import { CHANGELOG_DATA } from '../../../version/changelog';
import { summarizeChangelog } from '../../../version/changelogSummary';
import { HoverLift, ClickSpark, GradientText } from '../../react-bits';
import { HistoryIcon, ChevronRightIcon } from '../../Icons';
import styles from './style.module.less';

const VersionHistorySection = () => {
  const { t } = useTranslation();
  const { openChangelogDialog } = useUIState();

  const handleViewAll = useCallback(() => {
    openChangelogDialog();
  }, [openChangelogDialog]);

  const handleCardClick = useCallback(() => {
    openChangelogDialog();
  }, [openChangelogDialog]);

  const versions = useMemo(() => {
    return CHANGELOG_DATA.slice(0, 5).map((entry, index) => {
      const content = entry.content.zh || entry.content.en || '';
      const summary = summarizeChangelog(content);
      return {
        version: entry.version,
        date: entry.date,
        summary,
        isLatest: index === 0,
      };
    });
  }, []);

  return (
    <div className={styles.configSection}>
      <div className={styles.header}>
        <h3 className={styles.sectionTitle}>{t('settings.versionHistory')}</h3>
        <p className={styles.sectionDesc}>{t('settings.versionHistoryDesc')}</p>
      </div>

      <div className={styles.timeline}>
        {versions.map((version) => (
          <HoverLift
            key={version.version}
            lift={3}
            shadowIntensity={0.8}
            duration={200}
            className={styles.versionCardWrapper}
          >
            <div
              className={`${styles.versionCard} ${version.isLatest ? styles.latest : ''}`}
              onClick={handleCardClick}
            >
              <div className={styles.cardHeader}>
                <GradientText
                  className={styles.versionNumber}
                  colors={['var(--accent-primary, #4ea1ff)', 'var(--c-perf, #7c3aed)']}
                  angle={135}
                >
                  v{version.version}
                </GradientText>
                {version.isLatest && (
                  <span className={styles.latestBadge}>{t('versionHistory.latest')}</span>
                )}
                <span className={styles.date}>{version.date}</span>
              </div>

              <div className={styles.stats}>
                {version.summary.stats.map((stat) => (
                  <span key={stat.kind} className={styles.statItem} data-kind={stat.kind}>
                    <span className={styles.statDot} />
                    <span className={styles.statCount}>{stat.count}</span>
                    <span className={styles.statLabel}>
                      {t(`changelog.statLabel.${stat.kind}`, stat.kind)}
                    </span>
                  </span>
                ))}
              </div>

              <div className={styles.preview}>
                {version.summary.sections.slice(0, 2).map((section, si) => (
                  <div key={si} className={styles.previewSection}>
                    <span className={styles.previewSectionHead}>{section.head}</span>
                    <ul className={styles.previewList}>
                      {section.items.slice(0, 2).map((item, ii) => (
                        <li key={ii} dangerouslySetInnerHTML={{ __html: item }} />
                      ))}
                      {section.items.length > 2 && (
                        <li className={styles.moreItems}>
                          +{section.items.length - 2} more
                        </li>
                      )}
                    </ul>
                  </div>
                ))}
              </div>

              <ClickSpark>
                <button className={styles.viewButton}>
                  <HistoryIcon size={14} />
                  {t('versionHistory.viewDetails')}
                  <ChevronRightIcon size={14} />
                </button>
              </ClickSpark>
            </div>
          </HoverLift>
        ))}
      </div>

      {CHANGELOG_DATA.length > 5 && (
        <ClickSpark>
          <button className={styles.viewAllBtn} onClick={handleViewAll}>
            {t('versionHistory.viewAll')}
            <ChevronRightIcon size={16} />
          </button>
        </ClickSpark>
      )}
    </div>
  );
};

export default VersionHistorySection;