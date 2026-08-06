import { useState, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type { PromptConfig, PromptScope } from '../../../types/prompt';
import styles from './style.module.less';
import { BookmarkIcon, DownloadIcon, EditIcon, UploadIcon, KebabVerticalIcon, PlusIcon, TrashIcon } from '../../Icons';
import { UnifiedLoader } from '../../UnifiedLoader';

interface PromptScopeSectionProps {
  /** Section title (e.g., "Global Prompts" or "Project Prompts - ProjectName") */
  title: string;
  /** Prompt scope (global or project) */
  scope: PromptScope;
  /** Prompt list for this scope */
  prompts: PromptConfig[];
  /** Loading state */
  loading: boolean;
  /** Handler for add button */
  onAdd: () => void;
  /** Handler for edit */
  onEdit: (prompt: PromptConfig) => void;
  /** Handler for delete */
  onDelete: (prompt: PromptConfig) => void;
  /** Handler for export */
  onExport: () => void;
  /** Handler for import */
  onImport: () => void;
}

export default function PromptScopeSection({
  title,
  // scope is kept for API consistency and may be used in future enhancements
  scope: _scope,
  prompts,
  loading,
  onAdd,
  onEdit,
  onDelete,
  onExport,
  onImport,
}: PromptScopeSectionProps) {
  const { t } = useTranslation();
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

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

  const handleMenuToggle = (promptId: string) => {
    setOpenMenuId(openMenuId === promptId ? null : promptId);
  };

  const handleEditClick = (prompt: PromptConfig) => {
    setOpenMenuId(null);
    onEdit(prompt);
  };

  const handleDeleteClick = (prompt: PromptConfig) => {
    setOpenMenuId(null);
    onDelete(prompt);
  };

  return (
    <div className={styles.section}>
      <div className={styles.sectionHeader}>
        <h4 className={styles.sectionTitle}>{title}</h4>
        <div className={styles.sectionActions}>
          <button
            className={styles.exportButton}
            onClick={onExport}
            title={t('settings.prompt.export')}
          >
            <UploadIcon size={16} />
            {t('settings.prompt.export')}
          </button>
          <button
            className={styles.importButton}
            onClick={onImport}
            title={t('settings.prompt.import')}
          >
            <DownloadIcon size={16} />
            {t('settings.prompt.import')}
          </button>
          <button
            className={styles.addButton}
            onClick={onAdd}
            title={t('settings.prompt.create')}
          >
            <PlusIcon size={16} />
            {t('settings.prompt.create')}
          </button>
        </div>
      </div>

      {loading ? (
        <div className={styles.loadingState}>
          <UnifiedLoader type="wave" size={16} />
          <span>{t('settings.prompt.loading')}</span>
        </div>
      ) : prompts.length === 0 ? (
        <div className={styles.emptyState}>
          <span>{t('settings.prompt.noPrompts')}</span>
          <button className={styles.createLink} onClick={onAdd}>
            {t('settings.prompt.create')}
          </button>
        </div>
      ) : (
        <div className={styles.promptList}>
          {prompts.map((prompt) => (
            <div key={prompt.id} className={styles.promptCard}>
              <div className={styles.promptIcon}>
                <BookmarkIcon size={16} />
              </div>
              <div className={styles.promptInfo}>
                <div className={styles.promptName}>{prompt.name}</div>
                {prompt.content && (
                  <div className={styles.promptContent} title={prompt.content}>
                    {prompt.content.length > 80
                      ? prompt.content.substring(0, 80) + '...'
                      : prompt.content}
                  </div>
                )}
              </div>
              <div
                className={styles.promptActions}
                ref={openMenuId === prompt.id ? menuRef : null}
              >
                <button
                  className={styles.menuButton}
                  onClick={() => handleMenuToggle(prompt.id)}
                  title={t('settings.prompt.menu')}
                  aria-label={t('settings.prompt.menu')}
                  aria-expanded={openMenuId === prompt.id}
                  aria-haspopup="true"
                >
                  <KebabVerticalIcon size={16} />
                </button>
                {openMenuId === prompt.id && (
                  <div className={styles.dropdownMenu} role="menu">
                    <button
                      className={styles.menuItem}
                      onClick={() => handleEditClick(prompt)}
                      role="menuitem"
                    >
                      <EditIcon size={16} />
                      {t('common.edit')}
                    </button>
                    <button
                      className={`${styles.menuItem} ${styles.danger}`}
                      onClick={() => handleDeleteClick(prompt)}
                      role="menuitem"
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
  );
}
