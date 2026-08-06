import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { UnifiedLoader } from '../../UnifiedLoader';
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
  resolveVersionAction,
} from './versioning';
import {
  isDependencyStatusResponse,
  requestFreshDependencyStatus,
  requestDependencyStatusUntilSettled,
  retryDependencyStatusRequest,
  settleDependencyStatusRequest,
} from '../../../utils/bridgeStartup';
import styles from './style.module.less';
import { bridgeHub, registerLegacyAlias } from '../../../bridge';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import { CheckIcon, ChevronDownIcon, DownloadIcon, FolderIcon, InfoIcon, SyncIcon, TrashIcon, AlertIcon, CloseIcon } from '../../Icons';

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
  {
    id: 'opencode-sdk' as SdkId,
    nameKey: 'settings.dependency.opencodeSdkName',
    description: 'settings.dependency.opencodeSdkDescription',
    relatedProviders: ['opencode'],
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
        <ChevronDownIcon size={16} className={styles.versionSelectIcon} />
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
                {selected && <CheckIcon size={16} />}
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
  const [statusError, setStatusError] = useState(false);
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
    'opencode-sdk': false,
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
    registerLegacyAlias('updateDependencyStatus', DOWNSTREAM.DEPENDENCY_STATUS);
    registerLegacyAlias('dependencyInstallProgress', DOWNSTREAM.DEPENDENCY_INSTALL_PROGRESS);
    registerLegacyAlias('dependencyInstallResult', DOWNSTREAM.DEPENDENCY_INSTALL_RESULT);
    registerLegacyAlias('dependencyUninstallResult', DOWNSTREAM.DEPENDENCY_UNINSTALL_RESULT);
    registerLegacyAlias('dependencyUpdateAvailable', DOWNSTREAM.DEPENDENCY_UPDATE_AVAILABLE);
    registerLegacyAlias('dependencyVersionsLoaded', DOWNSTREAM.DEPENDENCY_VERSIONS_LOADED);
    registerLegacyAlias('nodeEnvironmentStatus', DOWNSTREAM.NODE_ENV_STATUS);
    registerLegacyAlias('checkNodeEnvironment', DOWNSTREAM.NODE_CHECK_ENV);

    const unsubs: Array<() => void> = [];

    unsubs.push(subscribeEvent(DOWNSTREAM.DEPENDENCY_STATUS, (jsonStr) => {
      try {
        const status = JSON.parse(jsonStr as string);
        if (!isDependencyStatusResponse(status)) {
          setStatusError(true);
          setLoading(false);
          settleDependencyStatusRequest('error');
        } else {
          setSdkStatus(status);
          sdkStatusRef.current = status;
          setStatusError(false);
          setLoading(false);
          settleDependencyStatusRequest('ready');
        }
      } catch (error) {
        console.error('[DependencySection] Failed to parse dependency status:', error);
        setStatusError(true);
        setLoading(false);
        settleDependencyStatusRequest('error');
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.DEPENDENCY_INSTALL_PROGRESS, (jsonStr) => {
      try {
        const progress: InstallProgress = JSON.parse(jsonStr as string);
        setInstallLogs((prev) => prev + progress.log + '\n');
      } catch (error) {
        console.error('[DependencySection] Failed to parse install progress:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.DEPENDENCY_INSTALL_RESULT, (jsonStr) => {
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
setStatusError(false);
          setLoading(true);
          requestFreshDependencyStatus();
          sendAction(UPSTREAM.CHECK_DEPENDENCY_UPDATES, { id: result.sdkId });
          sendAction(UPSTREAM.GET_DEPENDENCY_VERSIONS, { id: result.sdkId });
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

    unsubs.push(subscribeEvent(DOWNSTREAM.DEPENDENCY_UNINSTALL_RESULT, (jsonStr) => {
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
          sendAction(UPSTREAM.GET_DEPENDENCY_VERSIONS, { id: result.sdkId });
        } else {
          addToastRef.current?.(tRef.current('settings.dependency.uninstallFailed', { error: result.error }), 'error');
        }
      } catch (error) {
        console.error('[DependencySection] Failed to parse uninstall result:', error);
        setUninstallingSdk(null);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.DEPENDENCY_UPDATE_AVAILABLE, (jsonStr) => {
      try {
        const updatePayload: UpdateCheckResult = JSON.parse(jsonStr as string);
        setSdkStatus((prev) => mergeDependencyUpdates(prev, updatePayload));
      } catch (error) {
        console.error('[DependencySection] Failed to parse dependency update result:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.DEPENDENCY_VERSIONS_LOADED, (jsonStr) => {
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

    unsubs.push(subscribeEvent(DOWNSTREAM.NODE_ENV_STATUS, (jsonStr) => {
      try {
        const status: NodeEnvironmentStatus = JSON.parse(jsonStr as string);
        setNodeAvailable(status.available);
      } catch (error) {
        console.error('[DependencySection] Failed to parse node environment status:', error);
      }
    }));

    unsubs.push(subscribeEvent(DOWNSTREAM.NODE_CHECK_ENV, () => {
      sendAction(UPSTREAM.CHECK_NODE_ENVIRONMENT);
    }));
    if (import.meta.env.DEV) {
      window.runNodeEnvironmentStressTest = (count: number = 10) => {
        for (let i = 0; i < count; i += 1) {
          sendAction(UPSTREAM.CHECK_NODE_ENVIRONMENT);
        }
      };
    }

    if (window.__pendingDependencyUpdates) {
      bridgeHub.dispatch(DOWNSTREAM.DEPENDENCY_UPDATE_AVAILABLE, window.__pendingDependencyUpdates);
      window.__pendingDependencyUpdates = undefined;
    }
    if (window.__pendingDependencyVersions) {
      bridgeHub.dispatch(DOWNSTREAM.DEPENDENCY_VERSIONS_LOADED, window.__pendingDependencyVersions);
      window.__pendingDependencyVersions = undefined;
    }

    const handleNodePathReady = () => {
      isNodePathReadyRef.current = true;
      if (isActiveRef.current) {
        sendAction(UPSTREAM.CHECK_NODE_ENVIRONMENT);
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
      'opencode-sdk': true,
    });
setStatusError(false);
    setLoading(true);
    if (window.__dependencyStatusState === 'pending') {
      requestDependencyStatusUntilSettled();
    } else {
      retryDependencyStatusRequest();
    }
    sendAction(UPSTREAM.CHECK_DEPENDENCY_UPDATES);
    sendAction(UPSTREAM.GET_DEPENDENCY_VERSIONS);
    if (isNodePathReadyRef.current) {
      sendAction(UPSTREAM.CHECK_NODE_ENVIRONMENT);
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
    sendAction(UPSTREAM.INSTALL_DEPENDENCY, {
      id: sdkId,
      version: getRequestedVersion(selectedVersions[sdkId]),
    });
  };

  const handleUninstall = (sdkId: SdkId) => {
    setUninstallingSdk(sdkId);
    sendAction(UPSTREAM.UNINSTALL_DEPENDENCY, { id: sdkId });
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
    sendAction(UPSTREAM.UPDATE_DEPENDENCY, {
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

  const getActionLabel = (sdkId: SdkId, installed: boolean) => {
    const targetVersion = getTargetVersion(sdkId);
    const action = resolveVersionAction({
      installed,
      targetVersion,
      versionActions: getVersionInfo(sdkId)?.versionActions,
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

  const handleRetryStatus = () => {
    setStatusError(false);
    setLoading(true);
    retryDependencyStatusRequest();
  };

  return (
    <div className={styles.dependencySection}>
      <h3 className={styles.sectionTitle}>{t('settings.dependency.title')}</h3>
      <p className={styles.sectionDesc}>{t('settings.dependency.description')}</p>

      {/* SDK Install Policy Tip */}
      <div className={styles.sdkWarningBar}>
        <InfoIcon size={16} />
        <span className={styles.warningText}>{t('settings.dependency.installPolicyTip')}</span>
      </div>

      {/* Node.js Environment Warning */}
      {nodeAvailable === false && (
        <div className={styles.warningBanner}>
          <AlertIcon size={16} />
          <span>{t('settings.dependency.nodeNotConfigured')}</span>
        </div>
      )}

      {/* SDK List */}
      <div className={styles.sdkList}>
        {loading ? (
          <div className={styles.loadingState}>
            <UnifiedLoader type="bounce" size={16} />
            <span>{t('settings.dependency.loading')}</span>
          </div>
        ) : statusError ? (
          <div className={styles.loadingState}>
            <span className="codicon codicon-warning" />
            <span>{t('chat.sdkStatusUnavailable')}</span>
            <button type="button" className={styles.retryButton} onClick={handleRetryStatus}>
              <span className="codicon codicon-refresh" />
              <span>{t('chat.retrySdkStatus')}</span>
            </button>
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
            const action = resolveVersionAction({
              installed,
              targetVersion,
              versionActions: versionInfo?.versionActions,
            });
            // Only allow one operation at a time (install, uninstall, or update)
            const isAnyOperationInProgress = installingSdk !== null || uninstallingSdk !== null || updatingSdk !== null;
            const updateDisabled = isAnyOperationInProgress || nodeAvailable === false || action === 'current';

            return (
              <div key={sdk.id} className={styles.sdkCard}>
                <div className={styles.sdkHeader}>
                  <div className={styles.sdkInfo}>
                    <div className={styles.sdkName}>
                      {/* §15.5 B20:三路映射 SDK id → provider 图标(原二分把 opencode-sdk 错渲为 codex) */}
                      <ProviderModelIcon
                        providerId={sdk.id === 'claude-sdk' ? 'claude' : sdk.id === 'opencode-sdk' ? 'opencode' : 'codex'}
                        size={20}
                        colored
                      />
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
                                  <UnifiedLoader type="spin" size={14} />
                                  <span>{t('settings.dependency.installing')}</span>
                                </>
                              ) : (
                                <>
                                  <DownloadIcon size={16} />
                                  <span>{getActionLabel(sdk.id, installed)}</span>
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
                                    <UnifiedLoader type="spin" size={14} />
                                    <span>{t('settings.dependency.updating')}</span>
                                  </>
                                ) : (
                                  <>
                                    <SyncIcon size={16} />
                                    <span>{getActionLabel(sdk.id, installed)}</span>
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
                                    <UnifiedLoader type="spin" size={14} />
                                    <span>{t('settings.dependency.uninstalling')}</span>
                                  </>
                                ) : (
                                  <>
                                    <TrashIcon size={16} />
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
                          <UnifiedLoader type="pulse" size={14} />
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
                    <FolderIcon size={16} />
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
              <CloseIcon size={16} />
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
