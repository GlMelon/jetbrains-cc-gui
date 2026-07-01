import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useUIState } from '../../../contexts/UIStateContext';
import wxqImage from '../../../assets/images/wxq.png';
import styles from './style.module.less';
import { GitHubIcon, HistoryIcon } from '../../Icons';

const GITHUB_URL = 'https://github.com/zhukunpenglinyutong/idea-claude-code-gui';

interface CommunitySectionProps {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
}

const CommunitySection = ({ addToast }: CommunitySectionProps) => {
  const { t } = useTranslation();
  // 复用全局 ChangelogDialog 实例(AppDialogs 挂载),而非本地再起一个第二实例:
  // 两实例同时 isOpen 会叠加成双层 overlay(z-index 1100),且本地实例 onClose 不写 localStorage。
  const { openChangelogDialog } = useUIState();

  const handleCopyGitHub = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(GITHUB_URL);
      addToast(t('settings.githubCopied'), 'success');
    } catch {
      addToast(t('settings.githubCopyFailed'), 'error');
    }
  }, [addToast, t]);

  return (
    <div className={styles.configSection}>
      {/* Official community group */}
      <h3 className={styles.sectionTitle}>{t('settings.community')}</h3>
      <p className={styles.sectionDesc}>{t('settings.communityDesc')}</p>

      <div className={styles.qrcodeContainer}>
        <div className={styles.qrcodeWrapper}>
          <img
            src={wxqImage}
            alt={t('settings.communityQrAlt')}
            className={styles.qrcodeImage}
          />
          <p className={styles.qrcodeTip}>{t('settings.communityQrTip')}</p>
        </div>
      </div>

      {/* GitHub open source */}
      <div className={styles.githubSection}>
        <h3 className={styles.sectionTitle}>{t('settings.githubTitle')}</h3>
        <p className={styles.sectionDesc}>{t('settings.githubDesc')}</p>
        <button
          className={styles.githubBtn}
          onClick={handleCopyGitHub}
        >
          <GitHubIcon size={16} />
          {t('settings.githubCopyBtn')}
        </button>
      </div>

      {/* Version history — 点击交给全局 openChangelogDialog,不在本地渲染 ChangelogDialog */}
      <div className={styles.versionHistorySection}>
        <h3 className={styles.sectionTitle}>{t('settings.versionHistory')}</h3>
        <p className={styles.sectionDesc}>{t('settings.versionHistoryDesc')}</p>
        <button
          className={styles.versionHistoryBtn}
          onClick={openChangelogDialog}
        >
          <HistoryIcon size={16} />
          {t('settings.versionHistory')}
        </button>
      </div>
    </div>
  );
};

export default CommunitySection;
