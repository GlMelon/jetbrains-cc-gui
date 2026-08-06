import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './style.module.less';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../../shared/BaseDialog';
import { ClickSpark } from '../../react-bits';

interface ImportConfirmDialogProps {
  providers: any[];
  existingProviders: any[];
  onConfirm: (providers: any[]) => void;
  onCancel: () => void;
}

export default function ImportConfirmDialog({
  providers,
  existingProviders,
  onConfirm,
  onCancel
}: ImportConfirmDialogProps) {
  const { t } = useTranslation();
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set(providers.map(p => p.id)));

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
    if (selectedIds.size === providers.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(providers.map(p => p.id)));
    }
  };

  const getStatus = (provider: any) => {
    const exists = existingProviders.some(p => p.id === provider.id);
    return exists ? t('settings.provider.importDialog.statusUpdate') : t('settings.provider.importDialog.statusNew');
  };

  const handleConfirm = () => {
    const selectedProviders = providers.filter(p => selectedIds.has(p.id));
    onConfirm(selectedProviders);
  };

  return (
    <BaseDialog isOpen onClose={onCancel} animation="pop">
      <DialogHeader title={t('settings.provider.importDialog.title')} onClose={onCancel} />
      <DialogBody>
        <div className={styles.summary}>
          {t('settings.provider.importDialog.summary', { total: providers.length })}
          <span className={styles.newBadge}>{t('settings.provider.importDialog.newCount', { count: providers.filter(p => !existingProviders.some(e => e.id === p.id)).length })}</span>
          ，
          <span className={styles.updateBadge}>{t('settings.provider.importDialog.updateCount', { count: providers.filter(p => existingProviders.some(e => e.id === p.id)).length })}</span>
        </div>

        <div className={styles.tableHeader}>
          <div className={styles.colCheckbox}>
            <input
              type="checkbox"
              checked={selectedIds.size === providers.length && providers.length > 0}
              onChange={toggleAll}
            />
          </div>
          <div className={styles.colName}>{t('settings.provider.importDialog.columnName')}</div>
          <div className={styles.colId}>{t('settings.provider.importDialog.columnId')}</div>
          <div className={styles.colStatus}>{t('settings.provider.importDialog.columnStatus')}</div>
        </div>

        <div className={styles.providerList}>
          {providers.map(provider => {
            const status = getStatus(provider);
            const isSelected = selectedIds.has(provider.id);
            return (
              <div
                key={provider.id}
                className={`${styles.providerRow} ${isSelected ? styles.selected : ''}`}
                onClick={() => toggleSelect(provider.id)}
              >
                <div className={styles.colCheckbox}>
                  <input type="checkbox" checked={isSelected} onChange={() => {}} />
                </div>
                <div className={styles.colName}>{provider.name || provider.id}</div>
                <div className={styles.colId}>{provider.id}</div>
                <div className={styles.colStatus}>
                  <span className={status === t('settings.provider.importDialog.statusNew') ? styles.tagNew : styles.tagUpdate}>
                    {status}
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      </DialogBody>
      <DialogFooter>
        <div className={styles.selectedCount}>
          {t('settings.provider.importDialog.selectedCount', { count: selectedIds.size })}
        </div>
        <button className={styles.btnCancel} onClick={onCancel}>{t('common.cancel')}</button>
        <ClickSpark>
          <button
            className={styles.btnConfirm}
            onClick={handleConfirm}
            disabled={selectedIds.size === 0}
          >
            {t('settings.provider.importDialog.confirmImport')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}