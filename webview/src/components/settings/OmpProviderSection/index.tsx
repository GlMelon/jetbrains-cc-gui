import { useTranslation } from 'react-i18next';
import { InfoIcon, TerminalIcon } from '../../Icons';
import styles from './style.module.less';

interface OmpProviderSectionProps {
  showHeader?: boolean;
}

/**
 * OMP provider 管理面板(#3b)—— CLI-only provider 简化版,对称 GrokProviderSection。
 * OMP(pi fork)使用原生 CLI 配置(~/.omp),无 plugin 内 provider CRUD;
 * 模型角色(smol/slow/plan)经动态 listModels 由模型选择器展示,此处仅 CLI 环境说明。
 */
const OmpProviderSection = ({ showHeader = true }: OmpProviderSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.cli.tools.omp.name', 'OMP CLI')}</h3>
          <p className={styles.sectionDesc}>
            {t('settings.cli.tools.omp.description', 'Oh My Pi terminal coding agent with multi-provider model support')}
          </p>
        </>
      )}

      <div className={styles.cliInfoCard}>
        <div className={styles.cliInfoIcon}>
          <TerminalIcon size={20} />
        </div>
        <div className={styles.cliInfoContent}>
          <div className={styles.cliInfoTitle}>{t('settings.ompProvider.cliConfig', 'Native CLI Configuration')}</div>
          <div className={styles.cliInfoDesc}>
            {t(
              'settings.ompProvider.cliConfigDesc',
              'OMP uses native CLI configuration (~/.omp). Models and providers are managed through the omp CLI directly.',
            )}
          </div>
        </div>
      </div>

      <div className={styles.infoSection}>
        <InfoIcon size={16} />
        <p>
          {t(
            'settings.ompProvider.info',
            'OMP is a CLI-only provider (a pi fork). Authentication and configuration are handled by the omp CLI tool. To configure OMP, run "omp" in your terminal and follow the setup instructions. Model roles (smol / slow / plan) appear in the model selector automatically.',
          )}
        </p>
      </div>
    </div>
  );
};

export default OmpProviderSection;
