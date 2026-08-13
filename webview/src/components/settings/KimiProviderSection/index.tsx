import { useTranslation } from 'react-i18next';
import { InfoIcon, TerminalIcon } from '../../Icons';
import styles from './style.module.less';

interface KimiProviderSectionProps {
  showHeader?: boolean;
}

/**
 * Kimi provider 管理面板 —— CLI-only provider 简化版。
 * Kimi 使用原生 CLI 配置，不支持 plugin 内 provider CRUD。
 * 本组件仅展示 CLI 环境信息，无 provider 列表管理。
 */
const KimiProviderSection = ({ showHeader = true }: KimiProviderSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.kimiProvider.title', 'Kimi Provider')}</h3>
          <p className={styles.sectionDesc}>{t('settings.kimiProvider.description', 'Kimi CLI provider configuration')}</p>
        </>
      )}

      <div className={styles.cliInfoCard}>
        <div className={styles.cliInfoIcon}>
          <TerminalIcon size={20} />
        </div>
        <div className={styles.cliInfoContent}>
          <div className={styles.cliInfoTitle}>{t('settings.kimiProvider.cliConfig', 'Native CLI Configuration')}</div>
          <div className={styles.cliInfoDesc}>
            {t('settings.kimiProvider.cliConfigDesc', 'Kimi uses native CLI configuration. Provider settings are managed through the Kimi CLI directly.')}
          </div>
        </div>
      </div>

      <div className={styles.infoSection}>
        <InfoIcon size={16} />
        <p>{t('settings.kimiProvider.info', 'Kimi is a CLI-only provider. Authentication and configuration are handled by the Kimi CLI tool. To configure Kimi, run "kimi" in your terminal and follow the setup instructions.')}</p>
      </div>
    </div>
  );
};

export default KimiProviderSection;
