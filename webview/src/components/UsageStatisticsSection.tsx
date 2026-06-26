import { useTranslation } from 'react-i18next';
import { useUsageStatistics } from './UsageStatistics/useUsageStatistics.js';
import { UsageOverviewTab } from './UsageStatistics/UsageOverviewTab.js';
import { UsageModelsTab } from './UsageStatistics/UsageModelsTab.js';
import { UsageSessionsTab } from './UsageStatistics/UsageSessionsTab.js';
import { UsageTimelineTab } from './UsageStatistics/UsageTimelineTab.js';
import { CodeIcon, FolderIcon, FolderLibraryIcon, GaugeIcon, GraphIcon, GraphLineIcon, ListIcon, RefreshIcon, SyncIcon, AlertIcon } from './Icons';

const UsageStatisticsSection = ({ currentProvider }: { currentProvider?: string }) => {
  const { t } = useTranslation();
  const {
    statistics, loading, activeTab, projectScope, dateRange,
    sessionPage, sessionSortBy, tooltip, sessionsPerPage,
    filteredSessions, paginatedSessions, totalPages, filteredDailyUsage,
    setActiveTab, setDateRange, setSessionPage, setSessionSortBy, setTooltip,
    handleRefresh, handleScopeChange,
    formatNumber, formatCost, formatDate, formatChineseDate,
    formatRelativeTime, renderTrend, getTokenPercentage,
  } = useUsageStatistics(currentProvider);

  if (loading && !statistics) {
    return (
      <div className="usage-statistics-section">
        <div className="loading-container">
          <span className="codicon codicon-loading codicon-modifier-spin" />
          <p>{t('usage.loading')}</p>
        </div>
      </div>
    );
  }

  if (!statistics) {
    return (
      <div className="usage-statistics-section">
        <div className="empty-container">
          <GraphIcon size={16} />
          <p>{t('usage.noData')}</p>
          <button onClick={handleRefresh} className="btn-primary">
            <RefreshIcon size={16} />
            {t('usage.loadData')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="usage-statistics-section">
      {/* Estimate notice */}
      <div className="notice-box notice-box--warning">
        <AlertIcon size={16} />
        {t('usage.estimateNotice')}
      </div>

      {/* Controls bar */}
      <div className="usage-controls">
        <div className="controls-left">
          <div className="scope-selector">
            <button
              className={`scope-btn ${projectScope === 'current' ? 'active' : ''}`}
              onClick={() => handleScopeChange('current')}
            >
              <FolderIcon size={16} />
              {t('usage.currentProject')}
            </button>
            <button
              className={`scope-btn ${projectScope === 'all' ? 'active' : ''}`}
              onClick={() => handleScopeChange('all')}
            >
              <FolderLibraryIcon size={16} />
              {t('usage.allProjects')}
            </button>
          </div>

          <div className="date-range-selector">
            <button
              className={`range-btn ${dateRange === '7d' ? 'active' : ''}`}
              onClick={() => setDateRange('7d')}
            >
              {t('usage.last7Days')}
            </button>
            <button
              className={`range-btn ${dateRange === '30d' ? 'active' : ''}`}
              onClick={() => setDateRange('30d')}
            >
              {t('usage.last30Days')}
            </button>
            <button
              className={`range-btn ${dateRange === 'all' ? 'active' : ''}`}
              onClick={() => setDateRange('all')}
            >
              {t('usage.allTime')}
            </button>
          </div>
        </div>

        <button onClick={handleRefresh} className="refresh-btn icon-only" disabled={loading} title={t('usage.refreshData')}>
          <span className={`codicon codicon-refresh ${loading ? 'codicon-modifier-spin' : ''}`} />
        </button>
      </div>

      {/* Tab navigation */}
      <div className="usage-tabs">
        <button className={`tab-btn ${activeTab === 'overview' ? 'active' : ''}`} onClick={() => setActiveTab('overview')}>
          <GaugeIcon size={16} />
          {t('usage.overview')}
        </button>
        <button className={`tab-btn ${activeTab === 'models' ? 'active' : ''}`} onClick={() => setActiveTab('models')}>
          <CodeIcon size={16} />
          {t('usage.models')}
        </button>
        <button className={`tab-btn ${activeTab === 'sessions' ? 'active' : ''}`} onClick={() => setActiveTab('sessions')}>
          <ListIcon size={16} />
          {t('usage.sessions')}
        </button>
        <button className={`tab-btn ${activeTab === 'timeline' ? 'active' : ''}`} onClick={() => setActiveTab('timeline')}>
          <GraphLineIcon size={16} />
          {t('usage.timeline')}
        </button>
      </div>

      {/* Tab content */}
      <div className="usage-content">
        {activeTab === 'overview' && (
          <UsageOverviewTab
            statistics={statistics}
            formatCost={formatCost}
            formatNumber={formatNumber}
            renderTrend={renderTrend}
            getTokenPercentage={getTokenPercentage}
          />
        )}

        {activeTab === 'models' && (
          <UsageModelsTab
            models={statistics.byModel}
            formatCost={formatCost}
            formatNumber={formatNumber}
          />
        )}

        {activeTab === 'sessions' && (
          <UsageSessionsTab
            filteredSessions={filteredSessions}
            paginatedSessions={paginatedSessions}
            sessionPage={sessionPage}
            totalPages={totalPages}
            sessionsPerPage={sessionsPerPage}
            sessionSortBy={sessionSortBy}
            setSessionPage={setSessionPage}
            setSessionSortBy={setSessionSortBy}
            formatDate={formatDate}
            formatCost={formatCost}
            formatNumber={formatNumber}
          />
        )}

        {activeTab === 'timeline' && (
          <UsageTimelineTab
            filteredDailyUsage={filteredDailyUsage}
            tooltip={tooltip}
            setTooltip={setTooltip}
            formatCost={formatCost}
            formatChineseDate={formatChineseDate}
          />
        )}
      </div>

      {/* Last updated time */}
      {statistics.lastUpdated && (
        <div className="last-updated">
          <SyncIcon size={16} />
          <span>{t('usage.lastUpdated')}: {formatRelativeTime(statistics.lastUpdated)}</span>
        </div>
      )}
    </div>
  );
};

export default UsageStatisticsSection;
