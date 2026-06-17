import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  SdkId,
  SdkStatus,
  InstallProgress,
  InstallResult,
  UninstallResult,
  NodeEnvironmentStatus,
  UpdateCheckResult,
  DependencyVersionInfo,
  DependencyVersionResult,
} from '../../../types/dependency';
import {
  buildVersionOptions,
  getRequestedVersion,
  getVersionAction,
} from './versioning';
import styles from './style.module.less';
import { sendBridgeEvent } from '../../../utils/bridge';
import { bridgeHub, registerLegacyAlias } from '../../../bridge';

interface DependencySectionProps {
  addToast?: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
  isActive: boolean;
}

interface VersionSelectProps {
  value: string;
  options: string[];
  disabled: boolean;
  label: string;
  valueLabel: string;
  onChange: (version: string) => void;
}

const sendToJava = (event: string, payload?: unknown) => {
  const content = payload === undefined ? '' : JSON.stringify(payload);
  sendBridgeEvent(event, content);
};

const mergeDependencyUpdates = (
  previousStatus: Record<SdkId, SdkStatus>,
  updatePayload: UpdateCheckResult,
): Record<SdkId, SdkStatus> => {
  const nextStatus = { ...previousStatus };

  Object.entries(updatePayload).forEach(([sdkId, updateInfo]) => {
    const typedSdkId = sdkId as SdkId;
    const currentStatus = nextStatus[typedSdkId];
    if (!currentStatus) {
      return;
    }

    nextStatus[typedSdkId] = {
      ...currentStatus,
      hasUpdate: updateInfo.hasUpdate,
      latestVersion: updateInfo.latestVersion,
      lastChecked: new Date().toISOString(),
      errorMessage: updateInfo.error ?? currentStatus.errorMessage,
    };
  });

  return nextStatus;
};

const SDK_DEFINITIONS = [
  {
    id: 'claude-sdk' as SdkId,
    nameKey: 'settings.dependency.claudeSdkName',
    description: 'settings.dependency.claudeSdkDescription',
    relatedProviders: ['anthropic', 'bedrock'],
  },
  {
    id: 'codex-sdk' as SdkId,
    nameKey: 'settings.dependency.codexSdkName',
    description: 'settings.dependency.codexSdkDescription',
    relatedProviders: ['openai'],
  },
];

const VersionSelect = ({
  value,
  options,
  disabled,
  label,
  valueLabel,
  onChange,
}: VersionSelectProps) => {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const displayValue = value ? `v${value}` : '-';

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const handleDocumentMouseDown = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };

    document.addEventListener('mousedown', handleDocumentMouseDown);
    return () => document.removeEventListener('mousedown', handleDocumentMouseDown);
  }, [open]);

  useEffect(() => {
    if (disabled) {
      setOpen(false);
    }
  }, [disabled]);

  return (
    <div className={styles.versionSelect} ref={containerRef}>
      <button
        type="button"
        className={`${styles.versionSelectTrigger} ${open ? styles.open : ''}`}
        onClick={() => setOpen((prev) => !prev)}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={valueLabel}
      >
        <span className={styles.versionSelectValue}>{displayValue}</span>
        <span className={`codicon codicon-chevron-down ${styles.versionSelectIcon}`} />
      </button>

      {open && (
        <div className={styles.versionDropdown} role="listbox" aria-label={label}>
          {options.map((version) => {
            const selected = version === value;

            return (
              <button
                key={version}
                type="button"
                role="option"
                aria-selected={selected}
                className={`${styles.versionOption} ${selected ? styles.selected : ''}`}
                onClick={() => {
                  onChange(version);
                  setOpen(false);
                }}
              >
                <span>{`v${version}`}</span>
                {selected && <span className="codicon codicon-check" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};

const DependencySection = ({ addToast, isActive }: DependencySectionProps) => {
  const { t } = useTranslation();
  const [sdkStatus, setSdkStatus] = useState<Record<SdkId, SdkStatus>>({} as Record<SdkId, SdkStatus>);
  const [loading, setLoading] = useState(true);
  const [installingSdk, setInstallingSdk] = useState<SdkId | null>(null);
  const [uninstallingSdk, setUninstallingSdk] = useState<SdkId | null>(null);
  const [updatingSdk, setUpdatingSdk] = useState<SdkId | null>(null);
  const updatingSdkRef = useRef<SdkId | null>(null);
  const [installLogs, setInstallLogs] = useState<string>('');
  const [showLogs, setShowLogs] = useState(false);
  const [nodeAvailable, setNodeAvailable] = useState<boolean | null>(null);
  const [sdkVersions, setSdkVersions] = useState<Record<SdkId, DependencyVersionInfo>>({} as Record<SdkId, DependencyVersionInfo>);
  const [selectedVersions, setSelectedVersions] = useState<Record<SdkId, string>>({} as Record<SdkId, string>);
  const [loadingVersions, setLoadingVersions] = useState<Record<SdkId, boolean>>({
    'claude-sdk': false,
    'codex-sdk': false,
  });
  const logContainerRef = useRef<HTMLDivElement>(null);
  const isNodePathReadyRef = useRef(false);
  const sdkStatusRef = useRef<Record<SdkId, SdkStatus>>({} as Record<SdkId, SdkStatus>);

  // Use refs to store the latest callback and t function to avoid useEffect re-runs
  const addToastRef = useRef(addToast);
  const tRef = useRef(t);

  // Update refs when props change
  useEffect(() => {
    addToastRef.current = addToast;
    tRef.current = t;
  }, [addToast, t]);

  useEffect(() => {
    sdkStatusRef.current = sdkStatus;
  }, [sdkStatus]);

  // Auto-scroll logs to bottom
  useEffect(() => {
    if (logContainerRef.current && showLogs) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [installLogs, showLogs]);

  // Use a ref to track isActive so the mount-only effect can access the latest value
  const isActiveRef = useRef(isActive);
  useEffect(() => {
    isActiveRef.current = isActive;
  }, [isActive]);

  // Setup window callbacks - run once on mount only
  useEffect(() => {
    // [归一化] 所有 dependency/node 回调经 bridgeHub 订阅,替代旧 window.xxx 覆盖 + 链式转发。
    // bridgeHub 广播到所有订阅者(sessionCallbacks 的 dependency.status 订阅也会被调用)。
    registerLegacyAlias('updateDependencyStatus', 'dependency.status');
    registerLegacyAlias('dependencyInstallProgress', 'dependency.install_progress');
    registerLegacyAlias('dependencyInstallResult', 'dependency.install_result');
    registerLegacyAlias('dependencyUninstallResult', 'dependency.uninstall_result');
    registerLegacyAlias('dependencyUpdateAvailable', 'dependency.update_available');
    registerLegacyAlias('dependencyVersionsLoaded', 'dependency.versions_loaded');
    registerLegacyAlias('nodeEnvironmentStatus', 'node.env_status');
    registerLegacyAlias('checkNodeEnvironment', 'node.check_env');

    const unsubs: Array<() => void> = [];

    unsubs.push(bridgeHub.subscribe('dependency.status', (jsonStr) => {
      try {
        const status = JSON.parse(jsonStr as string);
        setSdkStatus(status);
        sdkStatusRef.current = status;
        setLoading(false);
      } catch (error) {
        console.error('[DependencySection] Failed to parse dependency status:', error);
        setLoading(false);
      }
    }));

    unsubs.push(bridgeHub.subscribe('dependency.install_progress', (jsonStr) => {
      try {
        const progress: InstallProgress = JSON.parse(jsonStr as string);
        setInstallLogs((prev) => prev + progress.log + '\n');
      } catch (error) {
        console.error('[DependencySection] Failed to parse install progress:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('dependency.install_result', (jsonStr) => {
      try {
        const result: InstallResult = JSON.parse(jsonStr as string);
        const wasUpdating = updatingSdkRef.current === result.sdkId;
        setInstallingSdk(null);
        setUpdatingSdk(null);
        updatingSdkRef.current = null;

        if (result.success) {
          const sdkDef = SDK_DEFINITIONS.find(d => d.id === result.sdkId);
          const sdkName = sdkDef ? tRef.current(sdkDef.nameKey) : result.sdkId;
          const msgKey = wasUpdating ? 'settings.dependency.updateSuccess' : 'settings.dependency.installSuccess';
          addToastRef.current?.(tRef.current(msgKey, { name: sdkName }), 'success');
          sendToJava('get_dependency_status');
          sendToJava('check_dependency_updates', { id: result.sdkId });
          sendToJava('get_dependency_versions', { id: result.sdkId });
        } else if (result.error === 'node_not_configured') {
          addToastRef.current?.(tRef.current('settings.dependency.nodeNotConfigured'), 'warning');
        } else {
          addToastRef.current?.(tRef.current('settings.dependency.installFailed', { error: result.error }), 'error');
        }
      } catch (error) {
        console.error('[DependencySection] Failed to parse install result:', error);
        setInstallingSdk(null);
        setUpdatingSdk(null);
        updatingSdkRef.current = null;
      }
    }));

    unsubs.push(bridgeHub.subscribe('dependency.uninstall_result', (jsonStr) => {
      try {
        const result: UninstallResult = JSON.parse(jsonStr as string);
        setUninstallingSdk(null);

        if (result.success) {
          const sdkDef = SDK_DEFINITIONS.find(d => d.id === result.sdkId);
          const sdkName = sdkDef ? tRef.current(sdkDef.nameKey) : result.sdkId;
          addToastRef.current?.(tRef.current('settings.dependency.uninstallSuccess', { name: sdkName }), 'success');
          setSdkStatus((prev) => ({
            ...prev,
            [result.sdkId]: {
              ...prev[result.sdkId],
              hasUpdate: false,
              latestVersion: undefined,
              lastChecked: new Date().toISOString(),
              errorMessage: undefined,
            },
          }));
          sendToJava('get_dependency_versions', { id: result.sdkId });
        } else {
          addToastRef.current?.(tRef.current('settings.dependency.uninstallFailed', { error: result.error }), 'error');
        }
      } catch (error) {
        console.error('[DependencySection] Failed to parse uninstall result:', error);
        setUninstallingSdk(null);
      }
    }));

    unsubs.push(bridgeHub.subscribe('dependency.update_available', (jsonStr) => {
      try {
        const updatePayload: UpdateCheckResult = JSON.parse(jsonStr as string);
        setSdkStatus((prev) => mergeDependencyUpdates(prev, updatePayload));
      } catch (error) {
        console.error('[DependencySection] Failed to parse dependency update result:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('dependency.versions_loaded', (jsonStr) => {
      try {
        const versionsPayload: DependencyVersionResult = JSON.parse(jsonStr as string);
        setSdkVersions((prev) => ({ ...prev, ...versionsPayload }));
        setLoadingVersions((prev) => {
          const next = { ...prev };
          Object.keys(versionsPayload).forEach((sdkId) => {
            next[sdkId as SdkId] = false;
          });
          return next;
        });
        setSelectedVersions((prev) => {
          const next = { ...prev };

          Object.entries(versionsPayload).forEach(([sdkId, versionInfo]) => {
            const typedSdkId = sdkId as SdkId;
            const installedVersion = sdkStatusRef.current[typedSdkId]?.installedVersion;
            const options = buildVersionOptions({
              availableVersions: versionInfo.versions,
              fallbackVersions: versionInfo.fallbackVersions,
              installedVersion,
            });
            const preferred = installedVersion ?? versionInfo.latestVersion ?? options[0];
            const current = getRequestedVersion(next[typedSdkId]);
            if (!current || !options.includes(current)) {
              next[typedSdkId] = preferred ?? '';
            }
          });

          return next;
        });
      } catch (error) {
        console.error('[DependencySection] Failed to parse dependency versions result:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('node.env_status', (jsonStr) => {
      try {
        const status: NodeEnvironmentStatus = JSON.parse(jsonStr as string);
        setNodeAvailable(status.available);
      } catch (error) {
        console.error('[DependencySection] Failed to parse node environment status:', error);
      }
    }));

    unsubs.push(bridgeHub.subscribe('node.check_env', () => {
      sendToJava('check_node_environment');
    }));
    if (import.meta.env.DEV) {
      window.runNodeEnvironmentStressTest = (count: number = 10) => {
        for (let i = 0; i < count; i += 1) {
          sendToJava('check_node_environment');
        }
      };
    }

    if (window.__pendingDependencyUpdates) {
      bridgeHub.dispatch('dependency.update_available', window.__pendingDependencyUpdates);
      window.__pendingDependencyUpdates = undefined;
    }
    if (window.__pendingDependencyVersions) {
      bridgeHub.dispatch('dependency.versions_loaded', window.__pendingDependencyVersions);
      window.__pendingDependencyVersions = undefined;
    }

    const handleNodePathReady = () => {
      isNodePathReadyRef.current = true;
      if (isActiveRef.current) {
        sendToJava('check_node_environment');
      }
    };
    window.addEventListener('nodePathReady', handleNodePathReady);

    return () => {
      unsubs.forEach((u) => u());
      window.removeEventListener('nodePathReady', handleNodePathReady);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Fetch data when tab becomes active
  useEffect(() => {
    if (!isActive) {
      return;
    }
    setLoadingVersions({
      'claude-sdk': true,
      'codex-sdk': true,
    });
    sendToJava('get_dependency_status');
    sendToJava('check_dependency_updates');
    sendToJava('get_dependency_versions');
    if (isNodePathReadyRef.current) {
      sendToJava('check_node_environment');
    }
  }, [isActive]);

  const handleInstall = (sdkId: SdkId) => {
    if (nodeAvailable === false) {
      addToast?.(t('settings.dependency.nodeNotConfigured'), 'warning');
      return;
    }

    setInstallingSdk(sdkId);
    setInstallLogs('');
    setShowLogs(true);
    sendToJava('install_dependency', {
      id: sdkId,
      version: getRequestedVersion(selectedVersions[sdkId]),
    });
  };

  const handleUninstall = (sdkId: SdkId) => {
    setUninstallingSdk(sdkId);
    sendToJava('uninstall_dependency', { id: sdkId });
  };

  const handleUpdate = (sdkId: SdkId) => {
    if (nodeAvailable === false) {
      addToast?.(t('settings.dependency.nodeNotConfigured'), 'warning');
      return;
    }

    setUpdatingSdk(sdkId);
    updatingSdkRef.current = sdkId;
    setInstallLogs('');
    setShowLogs(true);
    sendToJava('update_dependency', {
      id: sdkId,
      version: getRequestedVersion(selectedVersions[sdkId]),
    });
  };

  const getSdkInfo = (sdkId: SdkId): SdkStatus | undefined => {
    return sdkStatus[sdkId];
  };

  const isInstalled = (sdkId: SdkId): boolean => {
    const info = getSdkInfo(sdkId);
    return info?.status === 'installed';
  };

  const getVersionInfo = (sdkId: SdkId): DependencyVersionInfo | undefined => sdkVersions[sdkId];

  const getTargetVersion = (sdkId: SdkId): string | undefined =>
    getRequestedVersion(selectedVersions[sdkId]);

  const getActionLabel = (sdkId: SdkId, installed: boolean, installedVersion?: string) => {
    const targetVersion = getTargetVersion(sdkId);
    const action = getVersionAction({
      installed,
      installedVersion,
      requestedVersion: targetVersion,
    });

    if (!installed) {
      return targetVersion
        ? t('settings.dependency.installVersion', { version: `v${targetVersion}` })
        : t('settings.dependency.install');
    }

    if (!targetVersion || action === 'current') {
      return t('settings.dependency.currentVersionAction');
    }

    if (action === 'rollback') {
      return t('settings.dependency.rollbackToVersion', { version: `v${targetVersion}` });
    }

    return t('settings.dependency.updateToVersion', { version: `v${targetVersion}` });
  };

  return (
    <div className={styles.dependencySection}>
      <h3 className={styles.sectionTitle}>{t('settings.dependency.title')}</h3>
      <p className={styles.sectionDesc}>{t('settings.dependency.description')}</p>

      {/* SDK Install Policy Tip */}
      <div className={styles.sdkWarningBar}>
        <span className="codicon codicon-info" />
        <span className={styles.warningText}>{t('settings.dependency.installPolicyTip')}</span>
      </div>

      {/* Node.js Environment Warning */}
      {nodeAvailable === false && (
        <div className={styles.warningBanner}>
          <span className="codicon codicon-warning" />
          <span>{t('settings.dependency.nodeNotConfigured')}</span>
        </div>
      )}

      {/* SDK List */}
      <div className={styles.sdkList}>
        {loading ? (
          <div className={styles.loadingState}>
            <span className="codicon codicon-loading codicon-modifier-spin" />
            <span>{t('settings.dependency.loading')}</span>
          </div>
        ) : (
          SDK_DEFINITIONS.map((sdk) => {
            const info = getSdkInfo(sdk.id);
            const installed = isInstalled(sdk.id);
            const isInstalling = installingSdk === sdk.id;
            const isUninstalling = uninstallingSdk === sdk.id;
            const isUpdating = updatingSdk === sdk.id;
            const hasUpdate = info?.hasUpdate;
            const versionInfo = getVersionInfo(sdk.id);
            const versionOptions = buildVersionOptions({
              availableVersions: versionInfo?.versions,
              fallbackVersions: versionInfo?.fallbackVersions,
              installedVersion: info?.installedVersion,
            });
            const isVersionLoading = loadingVersions[sdk.id];
            const targetVersion = getTargetVersion(sdk.id);
            const targetVersionLabel = targetVersion
              ? t('settings.dependency.targetVersionValue', { version: `v${targetVersion}` })
              : t('settings.dependency.targetVersion');
            const action = getVersionAction({
              installed,
              installedVersion: info?.installedVersion,
              requestedVersion: targetVersion,
            });
            // Only allow one operation at a time (install, uninstall, or update)
            const isAnyOperationInProgress = installingSdk !== null || uninstallingSdk !== null || updatingSdk !== null;
            const updateDisabled = isAnyOperationInProgress || nodeAvailable === false || action === 'current';

            return (
              <div key={sdk.id} className={styles.sdkCard}>
                <div className={styles.sdkHeader}>
                  <div className={styles.sdkInfo}>
                    <div className={styles.sdkName}>
                      <span className={`codicon ${installed ? 'codicon-check' : 'codicon-package'}`} />
                      <span>{t(sdk.nameKey)}</span>
                      {installed && info?.installedVersion && (
                        <span className={styles.versionBadge}>v{info.installedVersion}</span>
                      )}
                      {installed && hasUpdate && info?.latestVersion && (
                        <span className={styles.versionBadge}>→ v{info.latestVersion}</span>
                      )}
                      {hasUpdate && (
                        <span className={styles.updateBadge}>
                          {t('settings.dependency.updateAvailable')}
                        </span>
                      )}
                    </div>
                    <div className={styles.sdkDescription}>{t(sdk.description)}</div>
                    <div className={styles.versionControls}>
                      <div className={styles.versionToolbar}>
                        <div className={styles.versionField}>
                          <span className={styles.versionLabelInline}>{t('settings.dependency.targetVersion')}</span>
                          <VersionSelect
                            value={selectedVersions[sdk.id] ?? ''}
                            options={versionOptions}
                            disabled={isAnyOperationInProgress || isVersionLoading || versionOptions.length === 0}
                            label={t('settings.dependency.targetVersion')}
                            valueLabel={targetVersionLabel}
                            onChange={(nextVersion) => {
                              setSelectedVersions((prev) => ({ ...prev, [sdk.id]: nextVersion }));
                            }}
                          />
                        </div>
                        <div className={styles.sdkActions}>
                          {!installed ? (
                            <button
                              className={`${styles.installBtn} ${isInstalling ? styles.installing : ''}`}
                              onClick={() => handleInstall(sdk.id)}
                              disabled={isAnyOperationInProgress || nodeAvailable === false}
                            >
                              {isInstalling ? (
                                <>
                                  <span className="codicon codicon-loading codicon-modifier-spin" />
                                  <span>{t('settings.dependency.installing')}</span>
                                </>
                              ) : (
                                <>
                                  <span className="codicon codicon-cloud-download" />
                                  <span>{getActionLabel(sdk.id, installed, info?.installedVersion)}</span>
                                </>
                              )}
                            </button>
                          ) : (
                            <>
                              <button
                                className={styles.updateBtn}
                                onClick={() => handleUpdate(sdk.id)}
                                disabled={updateDisabled}
                              >
                                {isUpdating ? (
                                  <>
                                    <span className="codicon codicon-loading codicon-modifier-spin" />
                                    <span>{t('settings.dependency.updating')}</span>
                                  </>
                                ) : (
                                  <>
                                    <span className="codicon codicon-sync" />
                                    <span>{getActionLabel(sdk.id, installed, info?.installedVersion)}</span>
                                  </>
                                )}
                              </button>
                              <button
                                className={styles.uninstallBtn}
                                onClick={() => handleUninstall(sdk.id)}
                                disabled={isAnyOperationInProgress}
                              >
                                {isUninstalling ? (
                                  <>
                                    <span className="codicon codicon-loading codicon-modifier-spin" />
                                    <span>{t('settings.dependency.uninstalling')}</span>
                                  </>
                                ) : (
                                  <>
                                    <span className="codicon codicon-trash" />
                                    <span>{t('settings.dependency.uninstall')}</span>
                                  </>
                                )}
                              </button>
                            </>
                          )}
                        </div>
                      </div>
                      {isVersionLoading && (
                        <div className={styles.versionLoadingHint}>
                          <span className="codicon codicon-loading codicon-modifier-spin" />
                          <span>{t('settings.dependency.loadingVersions')}</span>
                        </div>
                      )}
                      <div className={styles.versionMeta}>
                        {info?.installedVersion && (
                          <span>{t('settings.dependency.installedVersion', { version: `v${info.installedVersion}` })}</span>
                        )}
                        {versionInfo?.latestVersion && (
                          <span>{t('settings.dependency.latestStableVersion', { version: `v${versionInfo.latestVersion}` })}</span>
                        )}
                      </div>
                      {versionInfo?.source === 'fallback' && (
                        <div className={styles.versionHint}>
                          {t('settings.dependency.versionSourceFallback')}
                        </div>
                      )}
                      {installed && action === 'rollback' && (
                        <div className={styles.rollbackHint}>
                          {t('settings.dependency.rollbackWarning')}
                        </div>
                      )}
                    </div>
                  </div>

                </div>

                {/* Install path info */}
                {installed && info?.installPath && (
                  <div className={styles.installPath}>
                    <span className="codicon codicon-folder" />
                    <span>{info.installPath}</span>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Install Logs */}
      {showLogs && (
        <div className={styles.logsSection}>
          <div className={styles.logsHeader}>
            <span>{t('settings.dependency.installLogs')}</span>
            <button className={styles.closeLogsBtn} onClick={() => setShowLogs(false)}>
              <span className="codicon codicon-close" />
            </button>
          </div>
          <div className={styles.logsContainer} ref={logContainerRef}>
            <pre>{installLogs || t('settings.dependency.waitingForLogs')}</pre>
          </div>
        </div>
      )}
    </div>
  );
};

export default DependencySection;
