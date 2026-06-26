import { useTranslation } from 'react-i18next';
import { InfoIcon } from '../../Icons';
import sharedStyles from '../ProviderList/style.module.less';
import styles from './style.module.less';

interface OpenCodeProviderSectionProps {
  addToast: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
  showHeader?: boolean;
}

const OpenCodeProviderSection = ({
  addToast: _addToast,
  showHeader = true,
}: OpenCodeProviderSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.openCodeProvider.title')}</h3>
          <p className={styles.sectionDesc}>{t('settings.openCodeProvider.description')}</p>
        </>
      )}

      <div className={styles.statusPanel}>
        <div className={sharedStyles.card}>
          <div className={sharedStyles.cardInfo}>
            <div className={sharedStyles.name}>
              {t('settings.openCodeProvider.daemonTitle')}
            </div>
            <div className={sharedStyles.website}>
              {t('settings.openCodeProvider.daemonDescription')}
            </div>
          </div>
        </div>

        <div className={styles.infoSection}>
          <InfoIcon size={16} />
          <p>{t('settings.openCodeProvider.info')}</p>
        </div>
      </div>
    </div>
  );
};

export default OpenCodeProviderSection;
