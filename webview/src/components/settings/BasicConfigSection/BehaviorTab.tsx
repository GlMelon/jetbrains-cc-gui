import styles from './style.module.less';
import { useTranslation } from 'react-i18next';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from '../../../utils/permissionDialogTimeout';
import { PermissionDialogTimeoutSetting } from './PermissionDialogTimeoutSetting';
import {
  BellIcon,
  CheckIcon,
  CommentIcon,
  DiffIcon,
  FileIcon,
  GitCommitIcon,
  InfoIcon,
  KeyboardIcon,
  LayoutIcon,
  LightbulbIcon,
  SparklesIcon,
  SyncIcon,
} from '../../Icons';

const BehaviorTab = ({
  sendShortcut = 'enter',
  onSendShortcutChange = () => {},
  streamingEnabled = true,
  onStreamingEnabledChange = () => {},
  showThinkingEnabled = true,
  onShowThinkingEnabledChange = () => {},
  autoOpenFileEnabled = true,
  onAutoOpenFileEnabledChange = () => {},
  diffExpandedByDefault = false,
  onDiffExpandedByDefaultChange = () => {},
  commitGenerationEnabled = true,
  onCommitGenerationEnabledChange = () => {},
  mcpGatewayEnabled = true,
  onMcpGatewayEnabledChange = () => {},
  statusBarWidgetEnabled = true,
  onStatusBarWidgetEnabledChange = () => {},
  aiTitleGenerationEnabled = true,
  onAiTitleGenerationEnabledChange = () => {},
  newSessionConfirmEnabled = true,
  onNewSessionConfirmEnabledChange = () => {},
  taskCompletionNotificationEnabled = false,
  onTaskCompletionNotificationEnabledChange = () => {},
  askUserQuestionNotificationEnabled = false,
  onAskUserQuestionNotificationEnabledChange = () => {},
  detailedOutputEnabled = false,
  onDetailedOutputEnabledChange = () => {},
  permissionDialogTimeoutSeconds = DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  onPermissionDialogTimeoutChange = () => {},
}: {
  sendShortcut?: 'enter' | 'cmdEnter';
  onSendShortcutChange?: (shortcut: 'enter' | 'cmdEnter') => void;
  streamingEnabled?: boolean;
  onStreamingEnabledChange?: (enabled: boolean) => void;
  showThinkingEnabled?: boolean;
  onShowThinkingEnabledChange?: (enabled: boolean) => void;
  autoOpenFileEnabled?: boolean;
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
  diffExpandedByDefault?: boolean;
  onDiffExpandedByDefaultChange?: (enabled: boolean) => void;
  commitGenerationEnabled?: boolean;
  onCommitGenerationEnabledChange?: (enabled: boolean) => void;
  mcpGatewayEnabled?: boolean;
  onMcpGatewayEnabledChange?: (enabled: boolean) => void;
  statusBarWidgetEnabled?: boolean;
  onStatusBarWidgetEnabledChange?: (enabled: boolean) => void;
  aiTitleGenerationEnabled?: boolean;
  onAiTitleGenerationEnabledChange?: (enabled: boolean) => void;
  /**
   * Whether the "create new session with existing messages" confirm dialog is
   * enabled (i.e. shown). Positive semantics: `true` = dialog shows, `false` =
   * silently create the new session. Default `true` to preserve safer behaviour
   * for upgrading users.
   */
  newSessionConfirmEnabled?: boolean;
  onNewSessionConfirmEnabledChange?: (enabled: boolean) => void;
  taskCompletionNotificationEnabled?: boolean;
  onTaskCompletionNotificationEnabledChange?: (enabled: boolean) => void;
  askUserQuestionNotificationEnabled?: boolean;
  onAskUserQuestionNotificationEnabledChange?: (enabled: boolean) => void;
  detailedOutputEnabled?: boolean;
  onDetailedOutputEnabledChange?: (enabled: boolean) => void;
  permissionDialogTimeoutSeconds?: number;
  onPermissionDialogTimeoutChange?: (seconds: number) => void;
}) => {
  const { t } = useTranslation();

  return (
    <div className={styles.tabContent}>
      {/* Send shortcut configuration */}
      <div className={styles.sendShortcutSection}>
        <div className={styles.fieldHeader}>
          <KeyboardIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.sendShortcut.label')}</span>
        </div>
        <div className={styles.themeGrid}>
          <div
            className={`${styles.themeCard} ${sendShortcut === 'enter' ? styles.active : ''}`}
            onClick={() => onSendShortcutChange('enter')}
          >
            {sendShortcut === 'enter' && (
              <div className={styles.checkBadge}>
                <CheckIcon size={16} />
              </div>
            )}
            <div className={styles.themeCardTitle}>{t('settings.basic.sendShortcut.enter')}</div>
            <div className={styles.themeCardDesc}>{t('settings.basic.sendShortcut.enterDesc')}</div>
          </div>

          <div
            className={`${styles.themeCard} ${sendShortcut === 'cmdEnter' ? styles.active : ''}`}
            onClick={() => onSendShortcutChange('cmdEnter')}
          >
            {sendShortcut === 'cmdEnter' && (
              <div className={styles.checkBadge}>
                <CheckIcon size={16} />
              </div>
            )}
            <div className={styles.themeCardTitle}>{t('settings.basic.sendShortcut.cmdEnter')}</div>
            <div className={styles.themeCardDesc}>
              {t('settings.basic.sendShortcut.cmdEnterDesc')}
            </div>
          </div>
        </div>
      </div>

      <PermissionDialogTimeoutSetting
        permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
        onPermissionDialogTimeoutChange={onPermissionDialogTimeoutChange}
      />

      {/* Streaming configuration */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <SyncIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.streaming.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={streamingEnabled}
            onChange={(e) => onStreamingEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {streamingEnabled
              ? t('settings.basic.streaming.enabled')
              : t('settings.basic.streaming.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.streaming.hint')}</span>
        </small>
      </div>

      {/* Show thinking configuration — 显示思考区开关(跨所有 provider/调用模式)。
          off 时后端不推送 thinking delta/thinking-status(模型照常思考,纯显示控制);
          思考预算仍由上方的 reasoning effort 控制,与此开关解耦。 */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <LightbulbIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.showThinking.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={showThinkingEnabled}
            onChange={(e) => onShowThinkingEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {showThinkingEnabled
              ? t('settings.basic.showThinking.enabled')
              : t('settings.basic.showThinking.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.showThinking.hint')}</span>
        </small>
      </div>

      {/* Auto open file configuration */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <FileIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.autoOpenFile.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={autoOpenFileEnabled}
            onChange={(e) => onAutoOpenFileEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {autoOpenFileEnabled
              ? t('settings.basic.autoOpenFile.enabled')
              : t('settings.basic.autoOpenFile.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.autoOpenFile.hint')}</span>
        </small>
      </div>

      {/* Diff expanded by default configuration */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <DiffIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.diffExpanded.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={diffExpandedByDefault}
            onChange={(e) => onDiffExpandedByDefaultChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {diffExpandedByDefault
              ? t('settings.basic.diffExpanded.enabled')
              : t('settings.basic.diffExpanded.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.diffExpanded.hint')}</span>
        </small>
      </div>

      {/* AI commit generation toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <GitCommitIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.commitGeneration.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={commitGenerationEnabled}
            onChange={(e) => onCommitGenerationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {commitGenerationEnabled
              ? t('settings.basic.commitGeneration.enabled')
              : t('settings.basic.commitGeneration.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.commitGeneration.hint')}</span>
        </small>
      </div>

      {/* MCP Gateway acceleration toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <SyncIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.mcpGateway.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={mcpGatewayEnabled}
            onChange={(e) => onMcpGatewayEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {mcpGatewayEnabled
              ? t('settings.basic.mcpGateway.enabled')
              : t('settings.basic.mcpGateway.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.mcpGateway.hint')}</span>
        </small>
      </div>

      {/* Status bar widget toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <LayoutIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.statusBarWidget.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={statusBarWidgetEnabled}
            onChange={(e) => onStatusBarWidgetEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {statusBarWidgetEnabled
              ? t('settings.basic.statusBarWidget.enabled')
              : t('settings.basic.statusBarWidget.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.statusBarWidget.hint')}</span>
        </small>
      </div>

      {/* Task completion notification toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <BellIcon size={16} />
          <span className={styles.fieldLabel}>
            {t('settings.basic.taskCompletionNotification.label')}
          </span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={taskCompletionNotificationEnabled}
            onChange={(e) => onTaskCompletionNotificationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {taskCompletionNotificationEnabled
              ? t('settings.basic.taskCompletionNotification.enabled')
              : t('settings.basic.taskCompletionNotification.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.taskCompletionNotification.hint')}</span>
        </small>
      </div>

      {/* Detailed output information toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-output" aria-hidden="true" />
          <span className={styles.fieldLabel}>{t('settings.basic.detailedOutput.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={detailedOutputEnabled}
            onChange={(event) => onDetailedOutputEnabledChange(event.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {detailedOutputEnabled
              ? t('settings.basic.detailedOutput.enabled')
              : t('settings.basic.detailedOutput.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.detailedOutput.hint')}</span>
        </small>
      </div>

      {/* AskUserQuestion reminder notification toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <CommentIcon size={16} />
          <span className={styles.fieldLabel}>
            {t('settings.basic.askUserQuestionNotification.label')}
          </span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={askUserQuestionNotificationEnabled}
            onChange={(e) => onAskUserQuestionNotificationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {askUserQuestionNotificationEnabled
              ? t('settings.basic.askUserQuestionNotification.enabled')
              : t('settings.basic.askUserQuestionNotification.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.askUserQuestionNotification.hint')}</span>
        </small>
      </div>

      {/* AI session title generation toggle */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <SparklesIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.other.aiTitleGeneration.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={aiTitleGenerationEnabled}
            onChange={(e) => onAiTitleGenerationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {aiTitleGenerationEnabled
              ? t('settings.other.aiTitleGeneration.enabled')
              : t('settings.other.aiTitleGeneration.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.other.aiTitleGeneration.hint')}</span>
        </small>
      </div>

      {/* New-session confirm dialog toggle.
          Positive semantics throughout (no inversions in JSX) — the storage
          layer in utils/skipNewSessionConfirm.ts owns the negation. */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <CommentIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.basic.newSessionConfirm.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={newSessionConfirmEnabled}
            onChange={(e) => onNewSessionConfirmEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {newSessionConfirmEnabled
              ? t('settings.basic.newSessionConfirm.enabled')
              : t('settings.basic.newSessionConfirm.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.basic.newSessionConfirm.hint')}</span>
        </small>
      </div>
    </div>
  );
};

export default BehaviorTab;
