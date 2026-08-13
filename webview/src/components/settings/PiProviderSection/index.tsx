import { useTranslation } from 'react-i18next';
import { InfoIcon, TerminalIcon } from '../../Icons';
import styles from './style.module.less';

interface PiProviderSectionProps {
  showHeader?: boolean;
}

/**
 * Pi provider 管理面板 —— CLI-only provider 简化版。
 * Pi 使用原生 CLI 配置 (~/.pi)，不支持 plugin 内 provider CRUD。
 * 本组件仅展示 CLI 环境信息，无 provider 列表管理。
 */
const PiProviderSection = ({ showHeader = true }: PiProviderSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.piProvider.title', 'Pi Provider')}</h3>
          <p className={styles.sectionDesc}>{t('settings.piProvider.description', 'Pi CLI provider configuration')}</p>
        </>
      )}

      <div className={styles.cliInfoCard}>
        <div className={styles.cliInfoIcon}>
          <TerminalIcon size={20} />
        </div>
        <div className={styles.cliInfoContent}>
          <div className={styles.cliInfoTitle}>{t('settings.piProvider.cliConfig', 'Native CLI Configuration')}</div>
          <div className={styles.cliInfoDesc}>
            {t('settings.piProvider.cliConfigDesc', 'Pi uses native CLI configuration (~/.pi). Provider settings are managed through the Pi CLI directly.')}
          </div>
        </div>
      </div>

      <div className={styles.infoSection}>
        <InfoIcon size={16} />
        <p>{t('settings.piProvider.info', 'Pi is a CLI-only provider. Authentication and configuration are handled by the Pi CLI tool. To configure Pi, run "pi" in your terminal and follow the setup instructions.')}</p>
      </div>
    </div>
  );
};

export default PiProviderSection;
