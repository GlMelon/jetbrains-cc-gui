import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { AgentConfig } from '../../../types/agent';
import styles from '../ProviderList/style.module.less';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../../shared/BaseDialog';
import { ClickSpark } from '../../react-bits';

interface AgentExportDialogProps {
  agents: AgentConfig[];
  onConfirm: (selectedIds: string[]) => void;
  onCancel: () => void;
}

export default function AgentExportDialog({
  agents,
  onConfirm,
  onCancel
}: AgentExportDialogProps) {
  const { t } = useTranslation();
  const [selectedIds, setSelectedIds] = useState<Set<string>>(
    new Set(agents.map(agent => agent.id))
  );

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
    if (selectedIds.size === agents.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(agents.map(agent => agent.id)));
    }
  };

  const handleConfirm = () => {
    if (selectedIds.size === 0) return;
    onConfirm(Array.from(selectedIds));
  };

  return (
    <BaseDialog isOpen onClose={onCancel} animation="pop">
      <DialogHeader title={t('settings.agent.exportDialog.title')} onClose={onCancel} />
      <DialogBody>
        <div className={styles.summary}>
          {t('settings.agent.exportDialog.selectHint')}
        </div>

        <div className={styles.tableHeader}>
          <div className={styles.colCheckbox}>
            <input type="checkbox" checked={selectedIds.size === agents.length && agents.length > 0} onChange={toggleAll} />
          </div>
          <div className={styles.colName}>{t('settings.agent.importDialog.columnName')}</div>
          <div className={styles.colId}>{t('settings.agent.importDialog.columnId')}</div>
        </div>

        <div className={styles.providerList}>
          {agents.map(agent => {
            const isSelected = selectedIds.has(agent.id);
            return (
              <div key={agent.id} className={`${styles.providerRow} ${isSelected ? styles.selected : ''}`} onClick={() => toggleSelect(agent.id)}>
                <div className={styles.colCheckbox}>
                  <input type="checkbox" checked={isSelected} onChange={() => {}} />
                </div>
                <div className={styles.colName}>{agent.name}</div>
                <div className={styles.colId}>{agent.id}</div>
              </div>
            );
          })}
        </div>
      </DialogBody>
      <DialogFooter>
        <div className={styles.selectedCount}>
          {t('settings.agent.importDialog.selectedCount', { count: selectedIds.size })}
        </div>
        <button className={styles.btnCancel} onClick={onCancel}>{t('common.cancel')}</button>
        <ClickSpark>
          <button className={styles.btnConfirm} onClick={handleConfirm} disabled={selectedIds.size === 0}>
            {t('settings.agent.exportDialog.confirmExport')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}