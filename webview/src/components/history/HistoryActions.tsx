import { memo } from 'react';
import type { TFunction } from 'i18next';
import { TaskIcon, CheckAllIcon, ClearAllIcon, TrashIcon, CloseIcon, SyncIcon, SearchDeepIcon, InboxIcon } from '../Icons';

interface HistoryActionsProps {
  isSelectionMode: boolean;
  selectedCount: number;
  visibleCount: number;
  allVisibleSelected: boolean;
  isDeepSearching: boolean;
  canDelete: boolean;
  canArchive: boolean;
  t: TFunction;
  onEnterSelectionMode: () => void;
  onExitSelectionMode: () => void;
  onToggleSelectAllVisible: () => void;
  onStartDeleteSelected: () => void;
  onStartArchiveSelected: () => void;
  onDeepSearch: () => void;
}

export const HistoryActions = memo(({
  isSelectionMode,
  selectedCount,
  visibleCount,
  allVisibleSelected,
  isDeepSearching,
  canDelete,
  canArchive,
  t,
  onEnterSelectionMode,
  onExitSelectionMode,
  onToggleSelectAllVisible,
  onStartDeleteSelected,
  onStartArchiveSelected,
  onDeepSearch,
}: HistoryActionsProps) => {
  if (isSelectionMode) {
    return (
      <div className="history-header-actions">
        <button
          className="history-toolbar-btn"
          onClick={onToggleSelectAllVisible}
          disabled={visibleCount === 0}
          title={allVisibleSelected ? t('history.clearSelection') : t('history.selectAll')}
          aria-label={allVisibleSelected ? t('history.clearSelection') : t('history.selectAll')}
        >
          {allVisibleSelected ? <ClearAllIcon size={14} /> : <CheckAllIcon size={14} />}
          <span>{allVisibleSelected ? t('history.clearSelection') : t('history.selectAll')}</span>
        </button>
        {canArchive && (
          <button
            className="history-toolbar-btn"
            onClick={onStartArchiveSelected}
            disabled={selectedCount === 0}
            title={t('history.archiveSelected')}
            aria-label={t('history.archiveSelected')}
          >
            <InboxIcon size={14} />
            <span>{t('history.archiveSelected')}</span>
          </button>
        )}
        {canDelete && (
          <button
            className="history-toolbar-btn history-toolbar-danger"
            onClick={onStartDeleteSelected}
            disabled={selectedCount === 0}
            title={t('history.deleteSelected')}
            aria-label={t('history.deleteSelected')}
          >
            <TrashIcon size={14} />
            <span>{t('history.deleteSelected')}</span>
          </button>
        )}
        <button
          className="history-toolbar-btn"
          onClick={onExitSelectionMode}
          title={t('history.exitSelectMode')}
          aria-label={t('history.exitSelectMode')}
        >
          <CloseIcon size={14} />
        </button>
      </div>
    );
  }

  return (
    <div className="history-header-actions">
      {(canDelete || canArchive) && (
        <button
          className="history-toolbar-btn"
          onClick={onEnterSelectionMode}
          title={t('history.selectMode')}
          aria-label={t('history.selectMode')}
        >
          <TaskIcon size={14} />
          <span>{t('history.selectMode')}</span>
        </button>
      )}
      <button
        className={`history-deep-search-btn ${isDeepSearching ? 'searching' : ''}`}
        onClick={onDeepSearch}
        disabled={isDeepSearching}
        title={t('history.deepSearchTooltip')}
        aria-label={t('history.deepSearchTooltip')}
      >
        {isDeepSearching ? <SyncIcon size={14} spinning /> : <SearchDeepIcon size={14} />}
      </button>
    </div>
  );
});

HistoryActions.displayName = 'HistoryActions';
