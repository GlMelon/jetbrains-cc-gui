import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { PromptConfig } from '../../../types/prompt';
import type { ImportPreviewResult, ConflictStrategy } from '../../../types/import';
import styles from '../ProviderList/style.module.less';
import { AlertIcon } from '../../Icons';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../../shared/BaseDialog';
import { ClickSpark } from '../../react-bits';

interface PromptImportConfirmDialogProps {
  previewData: ImportPreviewResult<PromptConfig>;
  onConfirm: (selectedIds: string[], strategy: ConflictStrategy) => void;
  onCancel: () => void;
}

export default function PromptImportConfirmDialog({
  previewData,
  onConfirm,
  onCancel
}: PromptImportConfirmDialogProps) {
  const { t } = useTranslation();
  const [selectedIds, setSelectedIds] = useState<Set<string>>(
    new Set(previewData.items.map(item => item.data.id))
  );
  const [strategy, setStrategy] = useState<ConflictStrategy>('skip');

  const toggleSelect = (id: string) => {
    const newSelected = new Set(selectedIds);
    if (newSelected.has(id)) {
      newSelected.delete(id);
    } else {
      newSelected.add(id);
    }
    setSelectedIds(newSelected);
  };

  const toggleAll = () => {
    if (selectedIds.size === previewData.items.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(previewData.items.map(item => item.data.id)));
    }
  };

  const handleConfirm = () => {
    onConfirm(Array.from(selectedIds), strategy);
  };

  const hasConflicts = previewData.items.some(item => item.conflict);

  return (
    <BaseDialog isOpen onClose={onCancel} animation="pop">
      <DialogHeader title={t('settings.prompt.importDialog.title')} onClose={onCancel} />
      <DialogBody>
        <div className={styles.summary}>
          {t('settings.prompt.importDialog.summary', { total: previewData.summary.total })}
          <span className={styles.newBadge}>
            {t('settings.prompt.importDialog.newCount', { count: previewData.summary.newCount })}
          </span>
          ，
          <span className={styles.updateBadge}>
            {t('settings.prompt.importDialog.updateCount', { count: previewData.summary.updateCount })}
          </span>
        </div>

        {hasConflicts && (
          <div className={styles.strategySection}>
            <label className={styles.strategyLabel}>
              {t('settings.prompt.importDialog.conflictStrategy')}
            </label>
            <div className={styles.strategyOptions}>
              <label className={styles.strategyOption}>
                <input type="radio" name="strategy" value="skip" checked={strategy === 'skip'} onChange={(e) => setStrategy(e.target.value as ConflictStrategy)} />
                <span className={styles.strategyText}>
                  <strong>{t('settings.prompt.importDialog.strategySkip')}</strong>
                  <span className={styles.strategyDesc}>{t('settings.prompt.importDialog.strategySkipDesc')}</span>
                </span>
              </label>
              <label className={styles.strategyOption}>
                <input type="radio" name="strategy" value="overwrite" checked={strategy === 'overwrite'} onChange={(e) => setStrategy(e.target.value as ConflictStrategy)} />
                <span className={styles.strategyText}>
                  <strong>{t('settings.prompt.importDialog.strategyOverwrite')}</strong>
                  <span className={styles.strategyDesc}>{t('settings.prompt.importDialog.strategyOverwriteDesc')}</span>
                </span>
              </label>
              <label className={styles.strategyOption}>
                <input type="radio" name="strategy" value="duplicate" checked={strategy === 'duplicate'} onChange={(e) => setStrategy(e.target.value as ConflictStrategy)} />
                <span className={styles.strategyText}>
                  <strong>{t('settings.prompt.importDialog.strategyDuplicate')}</strong>
                  <span className={styles.strategyDesc}>{t('settings.prompt.importDialog.strategyDuplicateDesc')}</span>
                </span>
              </label>
            </div>
          </div>
        )}

        <div className={styles.tableHeader}>
          <div className={styles.colCheckbox}>
            <input type="checkbox" checked={selectedIds.size === previewData.items.length && previewData.items.length > 0} onChange={toggleAll} />
          </div>
          <div className={styles.colName}>{t('settings.prompt.importDialog.columnName')}</div>
          <div className={styles.colId}>{t('settings.prompt.importDialog.columnId')}</div>
          <div className={styles.colStatus}>{t('settings.prompt.importDialog.columnStatus')}</div>
        </div>

        <div className={styles.providerList}>
          {previewData.items.map(item => {
            const prompt = item.data;
            const isSelected = selectedIds.has(prompt.id);
            const statusText = item.status === 'new' ? t('settings.prompt.importDialog.statusNew') : t('settings.prompt.importDialog.statusUpdate');
            return (
              <div key={prompt.id} className={`${styles.providerRow} ${isSelected ? styles.selected : ''}`} onClick={() => toggleSelect(prompt.id)}>
                <div className={styles.colCheckbox}>
                  <input type="checkbox" checked={isSelected} onChange={() => {}} />
                </div>
                <div className={styles.colName}>{prompt.name}</div>
                <div className={styles.colId}>{prompt.id}</div>
                <div className={styles.colStatus}>
                  <span className={item.status === 'new' ? styles.tagNew : styles.tagUpdate}>{statusText}</span>
                  {item.conflict && (
                    <span className={styles.conflictIcon} title={t('settings.prompt.importDialog.conflictWarning')}>
                      <AlertIcon size={16} />
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </DialogBody>
      <DialogFooter>
        <div className={styles.selectedCount}>
          {t('settings.prompt.importDialog.selectedCount', { count: selectedIds.size })}
        </div>
        <button className={styles.btnCancel} onClick={onCancel}>{t('common.cancel')}</button>
        <ClickSpark>
          <button className={styles.btnConfirm} onClick={handleConfirm} disabled={selectedIds.size === 0}>
            {t('settings.prompt.importDialog.confirmImport')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}