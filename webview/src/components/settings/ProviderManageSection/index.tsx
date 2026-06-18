import { useTranslation } from 'react-i18next';
import type { ProviderConfig } from '../../../types/provider';
import { SPECIAL_PROVIDER_IDS } from '../../../types/provider';
import ProviderList from '../ProviderList';
import styles from './style.module.less';

interface ProviderManageSectionProps {
  providers: ProviderConfig[];
  loading: boolean;
  onAddProvider: () => void;
  onEditProvider: (provider: ProviderConfig) => void;
  onDeleteProvider: (provider: ProviderConfig) => void;
  onSwitchProvider: (id: string) => void;
  addToast: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
  showHeader?: boolean;
}

const ProviderManageSection = ({
  providers,
  loading,
  onAddProvider,
  onEditProvider,
  onDeleteProvider,
  onSwitchProvider,
  addToast,
  showHeader = true,
}: ProviderManageSectionProps) => {
  const { t } = useTranslation();
  const activeProvider = providers.find((provider) => provider.isActive);
  const managedProviderCount = providers.filter((provider) => (
    provider.id !== SPECIAL_PROVIDER_IDS.LOCAL_SETTINGS &&
    provider.id !== SPECIAL_PROVIDER_IDS.CLI_LOGIN
  )).length;
  const localAccessCount = providers.filter((provider) => (
    provider.id === SPECIAL_PROVIDER_IDS.LOCAL_SETTINGS ||
    provider.id === SPECIAL_PROVIDER_IDS.CLI_LOGIN
  )).length;

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <div className={styles.headerBand}>
          <div className={styles.headerText}>
            <h3 className={styles.sectionTitle}>{t('settings.providers')}</h3>
            <p className={styles.sectionDesc}>{t('settings.providersDesc')}</p>
          </div>
          <div className={styles.stats}>
            <div className={styles.statItem}>
              <span className={styles.statValue}>{activeProvider?.name || '-'}</span>
              <span className={styles.statLabel}>{t('settings.provider.inUse')}</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statValue}>{managedProviderCount}</span>
              <span className={styles.statLabel}>{t('settings.provider.allProviders')}</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statValue}>{localAccessCount}</span>
              <span className={styles.statLabel}>Local</span>
            </div>
          </div>
        </div>
      )}
      {loading && (
        <div className={styles.tempNotice}>
          <span className="codicon codicon-loading codicon-modifier-spin" />
          <p>{t('settings.provider.loading')}</p>
        </div>
      )}

      {!loading && (
        <ProviderList
          providers={providers}
          onAdd={onAddProvider}
          onEdit={onEditProvider}
          onDelete={onDeleteProvider}
          onSwitch={onSwitchProvider}
          addToast={addToast}
          emptyState={
            <>
              <span className="codicon codicon-info" />
              <p>{t('settings.provider.emptyProvider')}</p>
            </>
          }
        />
      )}
    </div>
  );
};

export default ProviderManageSection;
