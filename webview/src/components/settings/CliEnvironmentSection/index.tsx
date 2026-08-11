import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Skeleton } from '../../react-bits/Skeleton';
import { HoverLift } from '../../react-bits/HoverLift';
import { SpinLoader } from '../../react-bits/SpinLoader';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import { RefreshIcon, AlertIcon } from '../../Icons';
import styles from './style.module.less';

interface CliEnvironmentStatus {
  name: string;
  displayName: string;
  installed: boolean;
  currentVersion?: string;
  latestVersion?: string;
  installPath?: string;
  installSource?: string;
  hasUpdate?: boolean;
  errorMessage?: string;
}

interface CliEnvironmentSectionProps {
  isActive: boolean;
}

const CLI_TOOLS = [
  {
    id: 'claude',
    nameKey: 'settings.cli.claudeName',
    description: 'settings.cli.claudeDescription',
    icon: 'claude',
  },
  {
    id: 'codex',
    nameKey: 'settings.cli.codexName',
    description: 'settings.cli.codexDescription',
    icon: 'codex',
  },
  {
    id: 'opencode',
    nameKey: 'settings.cli.opencodeName',
    description: 'settings.cli.opencodeDescription',
    icon: 'opencode',
  },
];

const CliEnvironmentSection = ({ isActive }: CliEnvironmentSectionProps) => {
  const { t } = useTranslation();
  const [cliStatus, setCliStatus] = useState<Record<string, CliEnvironmentStatus>>({});
  const [loading, setLoading] = useState(true);
  const [checking, setChecking] = useState(false);
  const [lastChecked, setLastChecked] = useState<string>('');
  const isActiveRef = useRef(isActive);

  useEffect(() => {
    isActiveRef.current = isActive;
  }, [isActive]);

  useEffect(() => {
    const unsubs: Array<() => void> = [];

    unsubs.push(subscribeEvent(DOWNSTREAM.CLI_ENVIRONMENT_STATUS, (jsonStr) => {
      try {
        const status: Record<string, CliEnvironmentStatus> = JSON.parse(jsonStr as string);
        setCliStatus(status);
        setLoading(false);
        setChecking(false);
        setLastChecked(new Date().toLocaleString());
      } catch (error) {
        console.error('[CliEnvironmentSection] Failed to parse CLI environment status:', error);
        setLoading(false);
        setChecking(false);
      }
    }));

    return () => {
      unsubs.forEach((u) => u());
    };
  }, []);

  useEffect(() => {
    if (isActive && Object.keys(cliStatus).length === 0) {
      setLoading(true);
      sendAction(UPSTREAM.CHECK_CLI_ENVIRONMENT);
    }
  }, [isActive]);

  const handleRefresh = () => {
    setChecking(true);
    setLoading(true);
    sendAction(UPSTREAM.CHECK_CLI_ENVIRONMENT);
  };

  const getStatusInfo = (status: CliEnvironmentStatus) => {
    if (status.installed) {
      if (status.hasUpdate) {
        return {
          text: t('settings.cli.updateAvailable'),
          className: styles.updateAvailable,
        };
      }
      return {
        text: t('settings.cli.installed'),
        className: styles.installed,
      };
    }
    return {
      text: t('settings.cli.notInstalled'),
      className: styles.notInstalled,
    };
  };

  const renderSkeleton = () => (
    <div className={styles.cliList}>
      {CLI_TOOLS.map((tool) => (
        <div key={tool.id} className={styles.cliCard}>
          <div className={styles.cliHeader}>
            <Skeleton width={40} height={40} borderRadius={8} />
            <div className={styles.cliInfo}>
              <Skeleton width={120} height={16} />
              <div style={{ marginTop: 4 }}>
                <Skeleton width={80} height={12} />
              </div>
            </div>
          </div>
          <div className={styles.cliDetails}>
            <Skeleton width={200} height={12} />
            <div style={{ marginTop: 8 }}>
              <Skeleton width={160} height={12} />
            </div>
          </div>
        </div>
      ))}
    </div>
  );

  return (
    <div className={styles.cliEnvironmentSection}>
      <div className={styles.header}>
        <div className={styles.headerLeft}>
          <h3 className={styles.sectionTitle}>{t('settings.cli.title')}</h3>
          <p className={styles.sectionDesc}>{t('settings.cli.description')}</p>
        </div>
        <div className={styles.headerRight}>
          {lastChecked && (
            <span className={styles.lastChecked}>
              {t('settings.cli.lastChecked')}: {lastChecked}
            </span>
          )}
          <button
            className={styles.refreshBtn}
            onClick={handleRefresh}
            disabled={checking}
          >
            {checking ? (
              <SpinLoader size={14} />
            ) : (
              <RefreshIcon size={14} />
            )}
            {t('settings.cli.refreshCheck')}
          </button>
        </div>
      </div>

      {loading ? (
        renderSkeleton()
      ) : (
        <div className={styles.cliList}>
          {CLI_TOOLS.map((tool) => {
            const status = cliStatus[tool.id];
            if (!status) return null;

            const statusInfo = getStatusInfo(status);

            return (
              <HoverLift key={tool.id} shadowIntensity={0.5}>
                <div className={styles.cliCard}>
                  <div className={styles.cliHeader}>
                    <div className={`${styles.cliIcon} ${styles[tool.icon]}`}>
                      <ProviderModelIcon providerId={tool.icon} size={20} colored />
                    </div>
                    <div className={styles.cliInfo}>
                      <div className={styles.cliName}>
                        {t(tool.nameKey)}
                        <span className={`${styles.cliStatus} ${statusInfo.className}`}>
                          <span className={styles.statusDot}></span>
                          {statusInfo.text}
                        </span>
                      </div>
                      <div className={styles.cliDescription}>{t(tool.description)}</div>
                    </div>
                  </div>

                  <div className={styles.cliDetails}>
                    {status.installed && status.currentVersion && (
                      <div className={styles.detailItem}>
                        <span className={styles.detailLabel}>{t('settings.cli.currentVersion')}</span>
                        <span className={styles.detailValue}>
                          v{status.currentVersion}
                          {status.hasUpdate && status.latestVersion && (
                            <>
                              <span className={styles.versionArrow}>→</span>
                              <span className={styles.updateAvailable}>v{status.latestVersion}</span>
                            </>
                          )}
                        </span>
                      </div>
                    )}
                    {status.installed && status.installPath && (
                      <div className={styles.detailItem}>
                        <span className={styles.detailLabel}>{t('settings.cli.installPath')}</span>
                        <span className={styles.detailValue}>{status.installPath}</span>
                      </div>
                    )}
                    {status.installed && status.installSource && (
                      <div className={styles.detailItem}>
                        <span className={styles.detailLabel}>{t('settings.cli.installSource')}</span>
                        <span className={styles.detailValue}>{status.installSource}</span>
                      </div>
                    )}
                  </div>

                  {status.errorMessage && (
                    <div className={styles.errorMessage}>
                      <AlertIcon size={14} />
                      <span>{status.errorMessage}</span>
                    </div>
                  )}
                </div>
              </HoverLift>
            );
          })}
        </div>
      )}
    </div>
  );
};

export default CliEnvironmentSection;
