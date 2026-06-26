import styles from './style.module.less';
import { useTranslation } from 'react-i18next';
import RuntimePolicySection from './RuntimePolicySection';
import { CheckIcon, FolderIcon, InfoIcon, RocketIcon, ServerProcessIcon, TerminalIcon, AlertIcon } from '../../Icons';

export interface EnvironmentTabProps {
  nodePath: string;
  onNodePathChange: (path: string) => void;
  onSaveNodePath: () => void;
  savingNodePath: boolean;
  nodeVersion?: string | null;
  minNodeVersion?: number;
  claudeCliPath?: string;
  onClaudeCliPathChange?: (path: string) => void;
  onSaveClaudeCliPath?: () => void;
  savingClaudeCliPath?: boolean;
  workingDirectory?: string;
  onWorkingDirectoryChange?: (dir: string) => void;
  onSaveWorkingDirectory?: () => void;
  savingWorkingDirectory?: boolean;
  invocationMode?: 'sdk' | 'cli';
  onInvocationModeChange?: (mode: 'sdk' | 'cli') => void;
  cliPath?: string;
  onCliPathChange?: (path: string) => void;
}

const EnvironmentTab = ({
  nodePath,
  onNodePathChange,
  onSaveNodePath,
  savingNodePath,
  nodeVersion,
  minNodeVersion = 18,
  claudeCliPath = '',
  onClaudeCliPathChange = () => {},
  onSaveClaudeCliPath = () => {},
  savingClaudeCliPath = false,
  workingDirectory = '',
  onWorkingDirectoryChange = () => {},
  onSaveWorkingDirectory = () => {},
  savingWorkingDirectory = false,
  invocationMode = 'sdk',
  onInvocationModeChange = () => {},
  cliPath = '',
  onCliPathChange = () => {},
}: EnvironmentTabProps) => {
  const { t } = useTranslation();

  // Parse the major version number
  const parseMajorVersion = (version: string | null | undefined): number => {
    if (!version) return 0;
    const versionStr = version.startsWith('v') ? version.substring(1) : version;
    const dotIndex = versionStr.indexOf('.');
    if (dotIndex > 0) {
      return parseInt(versionStr.substring(0, dotIndex), 10) || 0;
    }
    return parseInt(versionStr, 10) || 0;
  };

  const majorVersion = parseMajorVersion(nodeVersion);
  const isVersionTooLow = nodeVersion && majorVersion > 0 && majorVersion < minNodeVersion;

  return (
    <div className={styles.tabContent}>
      {/* Invocation mode configuration */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <ServerProcessIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.invocationMode.label')}</span>
        </div>
        <div className={styles.themeGrid}>
          <div
            className={`${styles.themeCard} ${invocationMode === 'sdk' ? styles.active : ''}`}
            onClick={() => onInvocationModeChange('sdk')}
          >
            {invocationMode === 'sdk' && (
              <div className={styles.checkBadge}>
                <CheckIcon size={16} />
              </div>
            )}
            <div className={styles.themeCardTitle}>{t('settings.basic.invocationMode.sdk')}</div>
            <div className={styles.themeCardDesc}>{t('settings.basic.invocationMode.sdkDesc')}</div>
          </div>
          <div
            className={`${styles.themeCard} ${invocationMode === 'cli' ? styles.active : ''}`}
            onClick={() => onInvocationModeChange('cli')}
          >
            {invocationMode === 'cli' && (
              <div className={styles.checkBadge}>
                <CheckIcon size={16} />
              </div>
            )}
            <div className={styles.themeCardTitle}>{t('settings.basic.invocationMode.cli')}</div>
            <div className={styles.themeCardDesc}>{t('settings.basic.invocationMode.cliDesc')}</div>
          </div>
        </div>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.invocationMode.policyHint')}</span>
        </small>
        {invocationMode === 'cli' && (
          <div className={styles.nodePathInputWrapper} style={{ marginTop: 8 }}>
            <input
              type="text"
              className={styles.nodePathInput}
              placeholder={t('settings.basic.invocationMode.cliPathPlaceholder')}
              value={cliPath}
              onChange={(e) => onCliPathChange(e.target.value)}
            />
          </div>
        )}
        {invocationMode === 'cli' && (
          <small className={styles.formHint}>
            <InfoIcon size={16} />
            <span>{t('settings.basic.invocationMode.hint')}</span>
          </small>
        )}
      </div>

      {/* Runtime routing policy (advanced, collapsible) — linked with the invocation mode above */}
      <RuntimePolicySection invocationMode={invocationMode} />

      {/* Node.js path configuration */}
      <div className={styles.nodePathSection}>
        <div className={styles.fieldHeader}>
          <TerminalIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.nodePath.label')}</span>
          {nodeVersion && (
            <span className={`${styles.versionBadge} ${isVersionTooLow ? styles.versionBadgeError : styles.versionBadgeOk}`}>
              {nodeVersion}
            </span>
          )}
        </div>
        {isVersionTooLow && (
          <div className={styles.versionWarning}>
            <AlertIcon size={16} />
            {t('settings.basic.nodePath.versionTooLow', { minVersion: minNodeVersion })}
          </div>
        )}
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.nodePath.placeholder')}
            value={nodePath}
            onChange={(e) => onNodePathChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveNodePath}
            disabled={savingNodePath}
          >
            {savingNodePath && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>
            {t('settings.basic.nodePath.hint')} <code>{t('settings.basic.nodePath.hintCommand')}</code> {t('settings.basic.nodePath.hintText')}
          </span>
        </small>
      </div>

      {/* Custom Claude CLI path */}
      <div className={styles.nodePathSection}>
        <div className={styles.fieldHeader}>
          <RocketIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.claudeCliPath.label')}</span>
        </div>
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.claudeCliPath.placeholder')}
            value={claudeCliPath}
            onChange={(e) => onClaudeCliPathChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveClaudeCliPath}
            disabled={savingClaudeCliPath}
          >
            {savingClaudeCliPath && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.claudeCliPath.hint')}</span>
        </small>
      </div>

      {/* Working directory configuration */}
      <div className={styles.workingDirSection}>
        <div className={styles.fieldHeader}>
          <FolderIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.workingDirectory.label')}</span>
        </div>
        <div className={styles.nodePathInputWrapper}>
          <input
            type="text"
            className={styles.nodePathInput}
            placeholder={t('settings.basic.workingDirectory.placeholder')}
            value={workingDirectory}
            onChange={(e) => onWorkingDirectoryChange(e.target.value)}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveWorkingDirectory}
            disabled={savingWorkingDirectory}
          >
            {savingWorkingDirectory && (
              <span
                className="codicon codicon-loading codicon-modifier-spin"
              />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>
            {t('settings.basic.workingDirectory.hint')}
          </span>
        </small>
      </div>
    </div>
  );
};

export default EnvironmentTab;
