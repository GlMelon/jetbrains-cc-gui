import styles from './style.module.less';
import { useTranslation } from 'react-i18next';
import type { CommitAiConfig, CommitAiProvider } from '../../../types/aiFeatureConfig';
import { DEFAULT_COMMIT_AI_CONFIG } from '../../../types/aiFeatureConfig';
import AiFeatureProviderModelPanel from '../AiFeatureProviderModelPanel';
import AiFeatureSettingsCard from '../AiFeatureSettingsCard';
import { EditIcon, FolderIcon, InfoIcon } from '../../Icons';
import { UnifiedLoader } from '../../UnifiedLoader';

interface CommitSectionProps {
  commitAiConfig?: CommitAiConfig;
  onCommitAiProviderChange?: (provider: CommitAiProvider) => void;
  onCommitAiModelChange?: (model: string) => void;
  commitPrompt: string;
  projectCommitPrompt: string;
  onCommitPromptChange: (prompt: string) => void;
  onProjectCommitPromptChange: (prompt: string) => void;
  onSaveCommitPrompt: () => void;
  onSaveProjectCommitPrompt: () => void;
  savingCommitPrompt: boolean;
  savingProjectCommitPrompt: boolean;
}

const CommitSection = ({
  commitAiConfig = DEFAULT_COMMIT_AI_CONFIG,
  onCommitAiProviderChange = () => {},
  onCommitAiModelChange = () => {},
  commitPrompt,
  projectCommitPrompt,
  onCommitPromptChange,
  onProjectCommitPromptChange,
  onSaveCommitPrompt,
  onSaveProjectCommitPrompt,
  savingCommitPrompt,
  savingProjectCommitPrompt,
}: CommitSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.configSection}>
      <AiFeatureSettingsCard
        title={t('settings.commit.title')}
        description={t('settings.commit.description')}
        testId="commit-ai-provider-card"
      >
        <AiFeatureProviderModelPanel
          config={commitAiConfig}
          settingsKeyPrefix="settings.commit.providerModel"
          providerKeyPrefix="settings.basic.promptEnhancer.provider"
          fallbackProvider="codex"
          onProviderChange={onCommitAiProviderChange}
          onModelChange={onCommitAiModelChange}
        />
      </AiFeatureSettingsCard>

      {/* Commit AI prompt configuration */}
      <div className={styles.promptSection}>
        <div className={styles.fieldHeader}>
          <EditIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.commit.prompt.label')}</span>
        </div>
        <div className={styles.promptInputWrapper}>
          <textarea
            className={styles.promptTextarea}
            placeholder={t('settings.commit.prompt.placeholder')}
            value={commitPrompt}
            onChange={(e) => onCommitPromptChange(e.target.value)}
            rows={6}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveCommitPrompt}
            disabled={savingCommitPrompt}
          >
            {savingCommitPrompt && (
              <UnifiedLoader type="spin" size={14} />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.commit.prompt.hint')}</span>
        </small>
      </div>

      {/* Project-level commit prompt configuration */}
      <div className={styles.promptSection}>
        <div className={styles.fieldHeader}>
          <FolderIcon size={16} />
          <span className={styles.fieldLabel}>{t('settings.commit.projectPrompt.label')}</span>
        </div>
        <div className={styles.promptInputWrapper}>
          <textarea
            className={styles.promptTextarea}
            placeholder={t('settings.commit.projectPrompt.placeholder')}
            value={projectCommitPrompt}
            onChange={(e) => onProjectCommitPromptChange(e.target.value)}
            rows={6}
          />
          <button
            className={styles.saveBtn}
            onClick={onSaveProjectCommitPrompt}
            disabled={savingProjectCommitPrompt}
          >
            {savingProjectCommitPrompt && (
              <UnifiedLoader type="spin" size={14} />
            )}
            {t('common.save')}
          </button>
        </div>
        <small className={styles.formHint}>
          <InfoIcon size={16} />
          <span>{t('settings.commit.projectPrompt.hint')}</span>
        </small>
      </div>
    </div>
  );
};

export default CommitSection;
