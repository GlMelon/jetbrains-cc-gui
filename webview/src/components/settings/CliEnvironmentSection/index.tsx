import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { HoverLift } from '../../react-bits/HoverLift';
import { SpinLoader } from '../../react-bits/SpinLoader';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import { RefreshIcon, AlertIcon, ExternalLinkIcon } from '../../Icons';
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

interface CliToolConfig {
  id: string;
  nameKey: string;
  description: string;
  icon: string;
  docUrl: string;
}

const CLI_TOOLS: CliToolConfig[] = [
  {
    id: 'claude',
    nameKey: 'settings.cli.claudeName',
    description: 'settings.cli.claudeDescription',
    icon: 'claude',
    docUrl: 'https://docs.anthropic.com/en/docs/claude-code/overview',
  },
  {
    id: 'codex',
    nameKey: 'settings.cli.codexName',
    description: 'settings.cli.codexDescription',
    icon: 'codex',
    docUrl: 'https://platform.openai.com/docs/guides/codex',
  },
  {
    id: 'opencode',
    nameKey: 'settings.cli.opencodeName',
    description: 'settings.cli.opencodeDescription',
    icon: 'opencode',
    docUrl: 'https://github.com/opencode-ai/opencode',
  },
];

const CliEnvironmentSection = ({ isActive }: CliEnvironmentSectionProps) => {
  const { t } = useTranslation();
  const [cliStatus, setCliStatus] = useState<Record<string, CliEnvironmentStatus>>({});
  const [loading, setLoading] = useState(true);
  const [checking, setChecking] = useState(false);
  const [lastChecked, setLastChecked] = useState<string>('');
  const [checkingTools, setCheckingTools] = useState<Set<string>>(new Set());
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
        setCheckingTools(new Set());
        setLastChecked(new Date().toLocaleString());
      } catch (error) {
        console.error('[CliEnvironmentSection] Failed to parse CLI environment status:', error);
        setLoading(false);
        setChecking(false);
        setCheckingTools(new Set());
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

  const handleRefresh = useCallback(() => {
    setChecking(true);
    setCheckingTools(new Set(CLI_TOOLS.map((t) => t.id)));
    sendAction(UPSTREAM.CHECK_CLI_ENVIRONMENT);
  }, []);

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

  const handleUpdate = useCallback((toolId: string) => {
    sendAction(UPSTREAM.UPDATE_CLI_TOOL, { toolId });
  }, []);

  const handleOpenDoc = useCallback((url: string) => {
    window.open(url, '_blank');
  }, []);

  const renderCard = (tool: CliToolConfig, status?: CliEnvironmentStatus, isChecking = false) => {
    const statusInfo = status ? getStatusInfo(status) : null;
    const isToolChecking = isChecking || checkingTools.has(tool.id);
    const showVersionLoading = isToolChecking && status?.installed;

    return (
      <HoverLift key={tool.id} shadowIntensity={0.5}>
        <div className={`${styles.cliCard} ${isToolChecking ? styles.loading : ''}`}>
          {isToolChecking && <div className={styles.loadingBar} />}
          <div className={styles.cliHeader}>
            <div className={`${styles.cliIcon} ${styles[tool.icon]}`}>
              <ProviderModelIcon providerId={tool.icon} size={20} colored />
            </div>
            <div className={styles.cliInfo}>
              <div className={styles.cliName}>
                {t(tool.nameKey)}
                {isToolChecking ? (
                  <span className={`${styles.cliStatus} ${styles.checking}`}>
                    <span className={`${styles.statusDot} ${styles.pulse}`}></span>
                    {t('settings.cli.checking')}
                  </span>
                ) : statusInfo ? (
                  <span className={`${styles.cliStatus} ${statusInfo.className}`}>
                    <span className={styles.statusDot}></span>
                    {statusInfo.text}
                  </span>
                ) : null}
              </div>
              <div className={styles.cliDescription}>{t(tool.description)}</div>
            </div>
          </div>

          <div className={`${styles.cliDetails} ${isToolChecking ? styles.dimmed : ''}`}>
            {status?.installed && (
              <div className={styles.detailItem}>
                <span className={styles.detailLabel}>{t('settings.cli.currentVersion')}</span>
                <span className={`${styles.detailValue} ${styles.version}`}>
                  {showVersionLoading ? (
                    <span className={styles.inlineLoader}>
                      <span className={styles.spinner}></span>
                      <span>{t('settings.cli.checkingVersion')}</span>
                    </span>
                  ) : (
                    <>
                      <span className={styles.versionBadge}>v{status.currentVersion}</span>
                      {status.hasUpdate && status.latestVersion && (
                        <>
                          <span className={styles.versionArrow}>→</span>
                          <span className={styles.updateAvailable}>v{status.latestVersion}</span>
                        </>
                      )}
                    </>
                  )}
                </span>
              </div>
            )}
            {status?.installed && status.installPath && (
              <div className={styles.detailItem}>
                <span className={styles.detailLabel}>{t('settings.cli.installPath')}</span>
                <span className={styles.detailValue}>{status.installPath}</span>
              </div>
            )}
            {status?.installed && status.installSource && (
              <div className={styles.detailItem}>
                <span className={styles.detailLabel}>{t('settings.cli.installSource')}</span>
                <span className={styles.detailValue}>{status.installSource}</span>
              </div>
            )}
            <div className={styles.detailItem}>
              <span className={styles.detailLabel}>{t('settings.cli.docUrl')}</span>
              <span className={styles.detailValue}>
                <a
                  href={tool.docUrl}
                  className={styles.docLink}
                  onClick={(e) => {
                    e.preventDefault();
                    handleOpenDoc(tool.docUrl);
                  }}
                >
                  <ExternalLinkIcon size={14} />
                  {new URL(tool.docUrl).hostname}
                </a>
              </span>
            </div>
          </div>

          {status?.errorMessage && (
            <div className={styles.errorMessage}>
              <AlertIcon size={14} />
              <span>{status.errorMessage}</span>
            </div>
          )}

          {!isToolChecking && (
            <div className={styles.cliActions}>
              {status?.hasUpdate && status.latestVersion && (
                <button
                  className={`${styles.actionBtn} ${styles.primary}`}
                  onClick={() => handleUpdate(tool.id)}
                >
                  {t('settings.cli.updateToVersion', { version: status.latestVersion })}
                </button>
              )}
              <button
                className={`${styles.actionBtn} ${styles.secondary}`}
                onClick={() => handleOpenDoc(tool.docUrl)}
              >
                <ExternalLinkIcon size={14} />
                {t('settings.cli.viewDocs')}
              </button>
            </div>
          )}
        </div>
      </HoverLift>
    );
  };

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

      <div className={styles.cliList}>
        {loading ? (
          CLI_TOOLS.map((tool) => renderCard(tool, undefined, true))
        ) : (
          CLI_TOOLS.map((tool) => renderCard(tool, cliStatus[tool.id], false))
        )}
      </div>
    </div>
  );
};

export default CliEnvironmentSection;
