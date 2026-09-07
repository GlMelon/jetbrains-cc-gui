import { useTranslation } from 'react-i18next';
import { InfoIcon, TerminalIcon } from '../../Icons';
import styles from './style.module.less';

interface MiniMaxProviderSectionProps {
  showHeader?: boolean;
}

/**
 * MiniMax provider 管理面板 —— CLI-only provider 简化版(对称 GrokProviderSection)。
 * MiniMax Code 使用原生 CLI 配置(~/.minimax),不支持 plugin 内 provider CRUD。
 * 本组件仅展示 CLI 环境信息,无 provider 列表管理。
 */
const MiniMaxProviderSection = ({ showHeader = true }: MiniMaxProviderSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.minimaxProvider.title', 'MiniMax Provider')}</h3>
          <p className={styles.sectionDesc}>{t('settings.minimaxProvider.description', 'MiniMax Code CLI provider configuration')}</p>
        </>
      )}

      <div className={styles.cliInfoCard}>
        <div className={styles.cliInfoIcon}>
          <TerminalIcon size={20} />
        </div>
        <div className={styles.cliInfoContent}>
          <div className={styles.cliInfoTitle}>{t('settings.minimaxProvider.cliConfig', 'Native CLI Configuration')}</div>
          <div className={styles.cliInfoDesc}>
            {t('settings.minimaxProvider.cliConfigDesc', 'MiniMax Code uses native CLI configuration (~/.minimax). Provider settings are managed through the mcode CLI directly.')}
          </div>
        </div>
      </div>

      <div className={styles.infoSection}>
        <InfoIcon size={16} />
        <p>{t('settings.minimaxProvider.info', 'MiniMax is a CLI-only provider. Authentication and configuration are handled by the mcode CLI tool. To configure MiniMax, run "mcode login" in your terminal, or use "mcode provider" to manage API keys and custom providers.')}</p>
      </div>
    </div>
  );
};

export default MiniMaxProviderSection;
