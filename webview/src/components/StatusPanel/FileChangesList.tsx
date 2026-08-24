import { memo, useCallback } from 'react';
import type React from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import type { FileChangeSummary } from '../../types';
import { showEditableDiff, openFile } from '../../utils/bridge';
import FileIcon from './FileIcon';
import { TrashIcon, UndoIcon, DiffViewIcon, KeepAllIcon } from '../Icons';
import { UnifiedLoader } from '../UnifiedLoader';
import { AnimatedList } from '../react-bits';

interface FileChangesListProps {
  fileChanges: FileChangeSummary[];
  undoingFile: string | null;
  isDiscardingAll: boolean;
  onUndoClick: (fileChange: FileChangeSummary) => void;
  onDiscardAllClick: () => void;
  onKeepAllClick: () => void;
}

interface FileChangeRowProps {
  fileChange: FileChangeSummary;
  isUndoing: boolean;
  onOpen: (fileChange: FileChangeSummary) => void;
  onShowDiff: (fileChange: FileChangeSummary) => void;
  onUndo: (fileChange: FileChangeSummary) => void;
  t: TFunction;
  /** Forwarded by AnimatedList so the enter animation reaches the DOM node. */
  className?: string;
  /** Forwarded by AnimatedList so the enter animation reaches the DOM node. */
  style?: React.CSSProperties;
}

const FileChangeRow = memo(({ fileChange, isUndoing, onOpen, onShowDiff, onUndo, t, className, style }: FileChangeRowProps) => {
  const status = String(fileChange.status || 'M');
  const statusClass = status === 'A' ? 'added' : 'modified';

  const handleOpen = useCallback(() => {
    onOpen(fileChange);
  }, [onOpen, fileChange]);

  const handleOpenKeyDown = useCallback((event: React.KeyboardEvent<HTMLSpanElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onOpen(fileChange);
    }
  }, [onOpen, fileChange]);

  const handleShowDiff = useCallback(() => {
    onShowDiff(fileChange);
  }, [onShowDiff, fileChange]);

  const handleUndo = useCallback(() => {
    onUndo(fileChange);
  }, [onUndo, fileChange]);

  return (
    <div className={['file-change-item', className].filter(Boolean).join(' ')} style={style}>
      {/* Status indicator (A/M) */}
      <span className={`file-change-status status-${statusClass}`}>
        {status}
      </span>

      {/* File icon */}
      <FileIcon filePath={fileChange.filePath} />

      {/* File name — keyboard accessible since it acts as a button */}
      <span
        className="file-change-name"
        role="button"
        tabIndex={0}
        onClick={handleOpen}
        onKeyDown={handleOpenKeyDown}
        title={fileChange.filePath}
      >
        {fileChange.fileName}
      </span>

      {/* Multiple agents in this session edited the same file */}
      {fileChange.multiAgent === true && (
        <span
          className="file-change-multi-agent"
          title={t('statusPanel.multiAgentEdited', {
            count: fileChange.agentIds?.length ?? 2,
          })}
        >
          <span className="codicon codicon-organization" aria-hidden />
          <span className="file-change-multi-agent-label">
            {t('statusPanel.multiAgentBadge')}
          </span>
        </span>
      )}

      {/* Stats — net session baseline→current, not sum of ops */}
      {(fileChange.additions > 0 || fileChange.deletions > 0) && (
        <span className="file-change-stats">
          {fileChange.additions > 0 && <span className="additions">+{fileChange.additions}</span>}
          {fileChange.deletions > 0 && <span className="deletions">-{fileChange.deletions}</span>}
        </span>
      )}

      {/* Actions */}
      <div className="file-change-actions">
        <button
          className="file-change-action-btn diff-btn"
          onClick={handleShowDiff}
          title={t('statusPanel.showDiff')}
        >
          <DiffViewIcon size={16} />
        </button>
        <button
          className="file-change-action-btn undo-btn"
          onClick={handleUndo}
          title={t('statusPanel.undoChanges')}
          disabled={isUndoing}
        >
          {isUndoing ? (
            <UnifiedLoader type="spin" size={16} />
          ) : (
            <UndoIcon size={16} />
          )}
        </button>
      </div>
    </div>
  );
});

FileChangeRow.displayName = 'FileChangeRow';

const FileChangesList = memo(({
  fileChanges,
  undoingFile,
  isDiscardingAll,
  onUndoClick,
  onDiscardAllClick,
  onKeepAllClick,
}: FileChangesListProps) => {
  const { t } = useTranslation();

  const handleOpenFile = useCallback((fileChange: FileChangeSummary) => {
    openFile(fileChange.filePath, fileChange.lineStart, fileChange.lineEnd);
  }, []);

  const handleShowDiff = useCallback((fileChange: FileChangeSummary) => {
    const operations = fileChange.operations.map((op) => ({
      oldString: op.oldString,
      newString: op.newString,
      replaceAll: op.replaceAll,
    }));
    // Use editable diff view for selective accept/reject of changes
    const status = fileChange.status === 'A' ? 'A' : 'M';
    showEditableDiff(fileChange.filePath, operations, status);
  }, []);

  if (fileChanges.length === 0) {
    return <div className="status-panel-empty">{t('statusPanel.noFileChanges')}</div>;
  }

  return (
    <div className="file-changes-container">
      {/* Batch action buttons */}
      <div className="file-changes-actions-bar">
        <button
          className="file-changes-action-btn discard-all-btn"
          onClick={onDiscardAllClick}
          disabled={isDiscardingAll}
          title={t('statusPanel.discardAll')}
        >
          {isDiscardingAll ? (
            <UnifiedLoader type="spin" size={16} />
          ) : (
            <TrashIcon size={16} />
          )}
          <span>{t('statusPanel.discardAll')}</span>
        </button>
        <button
          className="file-changes-action-btn keep-all-btn"
          onClick={onKeepAllClick}
          title={t('statusPanel.keepAll')}
        >
          <KeepAllIcon size={16} />
          <span>{t('statusPanel.keepAll')}</span>
        </button>
      </div>

      {/* File list */}
      <div className="file-changes-list">
        <AnimatedList stagger={50} offset={8}>
          {fileChanges.map((fileChange) => (
          <FileChangeRow
            key={fileChange.filePath}
            fileChange={fileChange}
            isUndoing={undoingFile === fileChange.filePath}
            onOpen={handleOpenFile}
            onShowDiff={handleShowDiff}
            onUndo={onUndoClick}
            t={t}
          />
          ))}
        </AnimatedList>
      </div>
    </div>
  );
});

FileChangesList.displayName = 'FileChangesList';

export default FileChangesList;
