import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { AgentConfig } from '../../../types/agent';
import styles from './style.module.less';
import { DownloadIcon, EditIcon, UploadIcon, KebabVerticalIcon, PlusIcon, RobotIcon, TrashIcon } from '../../Icons';

interface AgentSectionProps {
  agents: AgentConfig[];
  loading: boolean;
  onAdd: () => void;
  onEdit: (agent: AgentConfig) => void;
  onDelete: (agent: AgentConfig) => void;
  onExport: () => void;
  onImport: () => void;
}

export default function AgentSection({
  agents,
  loading,
  onAdd,
  onEdit,
  onDelete,
  onExport,
  onImport,
}: AgentSectionProps) {
  const { t } = useTranslation();
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Close menu when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpenMenuId(null);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, []);

  const handleMenuToggle = (agentId: string) => {
    setOpenMenuId(openMenuId === agentId ? null : agentId);
  };

  const handleEditClick = (agent: AgentConfig) => {
    setOpenMenuId(null);
    onEdit(agent);
  };

  const handleDeleteClick = (agent: AgentConfig) => {
    setOpenMenuId(null);
    onDelete(agent);
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <div className={styles.titleWrapper}>
          <h3 className={styles.title}>{t('settings.agent.title')}</h3>
        </div>
        <div className={styles.headerActions}>
          <button className={styles.exportButton} onClick={onExport}>
            <UploadIcon size={16} />
            {t('settings.agent.export')}
          </button>
          <button className={styles.importButton} onClick={onImport}>
            <DownloadIcon size={16} />
            {t('settings.agent.import')}
          </button>
          <button className={styles.addButton} onClick={onAdd}>
            <PlusIcon size={16} />
            {t('settings.agent.create')}
          </button>
        </div>
      </div>

      <div className={styles.section}>
        <h4 className={styles.sectionTitle}>{t('settings.agent.customAgents')}</h4>

        {loading ? (
          <div className={styles.loadingState}>
            <span className="codicon codicon-loading codicon-modifier-spin" />
            <span>{t('settings.agent.loading')}</span>
          </div>
        ) : agents.length === 0 ? (
          <div className={styles.emptyState}>
            <span>{t('settings.agent.noAgents')}</span>
            <button className={styles.createLink} onClick={onAdd}>
              {t('settings.agent.create')}
            </button>
          </div>
        ) : (
          <div className={styles.agentList}>
            {agents.map((agent) => (
              <div key={agent.id} className={styles.agentCard}>
                <div className={styles.agentIcon}>
                  <RobotIcon size={16} />
                </div>
                <div className={styles.agentInfo}>
                  <div className={styles.agentName}>{agent.name}</div>
                  {agent.prompt && (
                    <div className={styles.agentPrompt} title={agent.prompt}>
                      {agent.prompt.length > 50
                        ? agent.prompt.substring(0, 50) + '...'
                        : agent.prompt}
                    </div>
                  )}
                </div>
                <div className={styles.agentActions} ref={openMenuId === agent.id ? menuRef : null}>
                  <button
                    className={styles.menuButton}
                    onClick={() => handleMenuToggle(agent.id)}
                    title={t('settings.agent.menu')}
                  >
                    <KebabVerticalIcon size={16} />
                  </button>
                  {openMenuId === agent.id && (
                    <div className={styles.dropdownMenu}>
                      <button
                        className={styles.menuItem}
                        onClick={() => handleEditClick(agent)}
                      >
                        <EditIcon size={16} />
                        {t('common.edit')}
                      </button>
                      <button
                        className={`${styles.menuItem} ${styles.danger}`}
                        onClick={() => handleDeleteClick(agent)}
                      >
                        <TrashIcon size={16} />
                        {t('common.delete')}
                      </button>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
