import { useTranslation } from 'react-i18next';
import { InfoIcon, TerminalIcon } from '../../Icons';
import styles from './style.module.less';

interface GrokProviderSectionProps {
  showHeader?: boolean;
}

/**
 * Grok provider 管理面板 —— CLI-only provider 简化版。
 * Grok 使用原生 CLI 配置 (~/.grok)，不支持 plugin 内 provider CRUD。
 * 本组件仅展示 CLI 环境信息，无 provider 列表管理。
 */
const GrokProviderSection = ({ showHeader = true }: GrokProviderSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.grokProvider.title', 'Grok Provider')}</h3>
          <p className={styles.sectionDesc}>{t('settings.grokProvider.description', 'Grok CLI provider configuration')}</p>
        </>
      )}

      <div className={styles.cliInfoCard}>
        <div className={styles.cliInfoIcon}>
          <TerminalIcon size={20} />
        </div>
        <div className={styles.cliInfoContent}>
          <div className={styles.cliInfoTitle}>{t('settings.grokProvider.cliConfig', 'Native CLI Configuration')}</div>
          <div className={styles.cliInfoDesc}>
            {t('settings.grokProvider.cliConfigDesc', 'Grok uses native CLI configuration (~/.grok). Provider settings are managed through the Grok CLI directly.')}
          </div>
        </div>
      </div>

      <div className={styles.infoSection}>
        <InfoIcon size={16} />
        <p>{t('settings.grokProvider.info', 'Grok is a CLI-only provider. Authentication and configuration are handled by the Grok CLI tool. To configure Grok, run "grok" in your terminal and follow the setup instructions.')}</p>
      </div>
    </div>
  );
};

export default GrokProviderSection;
