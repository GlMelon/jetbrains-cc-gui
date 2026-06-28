import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import type { AiFeatureConfig, AiFeatureProvider } from '../../../types/aiFeatureConfig';
import { getModelsForProvider, subscribeModelRegistry } from '../../../utils/modelRegistry';
import { readClaudeModelMapping, resolveMappedModelName } from '../../../utils/claudeModelMapping';
import { CLAUDE_ROLE_MODEL_IDS } from '../../ChatInputBox/types';
import styles from './style.module.less';
import { ChevronDownIcon, InfoIcon } from '../../Icons';

interface AiFeatureProviderModelPanelProps {
  config: AiFeatureConfig;
  settingsKeyPrefix: string;
  providerKeyPrefix: string;
  fallbackProvider?: AiFeatureProvider;
  onProviderChange?: (provider: AiFeatureProvider) => void;
  onModelChange?: (model: string) => void;
}

const AiFeatureProviderModelPanel = ({
  config,
  settingsKeyPrefix,
  providerKeyPrefix,
  fallbackProvider = 'codex',
  onProviderChange = () => {},
  onModelChange = () => {},
}: AiFeatureProviderModelPanelProps) => {
  const { t } = useTranslation();

  // 订阅 model registry 更新:后端推送新 registry 时递增 version,触发 useMemo 重算 modelOptions。
  // 参照 useModelProviderState:98-99 + ButtonArea:45 的相同模式。
  const [modelRegistryVersion, setModelRegistryVersion] = useState(0);
  useEffect(() => subscribeModelRegistry(() => setModelRegistryVersion((v) => v + 1)), []);

  const selectedProvider = config.provider
    ?? config.effectiveProvider
    ?? fallbackProvider;
  const statusProvider = config.effectiveProvider ?? config.provider ?? fallbackProvider;
  const modelOptions = useMemo(() => {
    const configuredModel = config.models[selectedProvider];
    const registryModels = getModelsForProvider(selectedProvider);
    // A1:不再回退本地表 CLAUDE_MODELS/CODEX_MODELS;registry 空时仅用 configuredModel 兜底(见下)。
    let options = registryModels;
    // 对 claude provider 应用 modelMapping,与对话模型下拉显示保持一致
    if (selectedProvider === 'claude') {
      try {
        const mapping = readClaudeModelMapping();
        if (Object.keys(mapping).length > 0) {
          options = options.map((model) => {
            const roleEntry = Object.entries(CLAUDE_ROLE_MODEL_IDS).find(([, v]) => v === model.id);
            if (!roleEntry) return model;
            const mappedName = resolveMappedModelName(roleEntry[0], mapping);
            if (mappedName) return { ...model, label: mappedName };
            return model;
          });
        }
      } catch { /* mapping read failure is non-fatal */ }
    }
    if (configuredModel && !options.some((model) => model.id === configuredModel)) {
      return [
        {
          id: configuredModel,
          label: configuredModel,
        },
        ...options,
      ];
    }
    return options;
  }, [config.models, selectedProvider, modelRegistryVersion]);
  const statusText = config.resolutionSource === 'auto'
    ? t(`${settingsKeyPrefix}.currentProviderAuto`, {
      provider: t(`${providerKeyPrefix}.${statusProvider}`),
    })
    : config.resolutionSource === 'manual'
      ? t(`${settingsKeyPrefix}.currentProviderManual`, {
        provider: t(`${providerKeyPrefix}.${statusProvider}`),
      })
      : t(`${settingsKeyPrefix}.currentProviderUnavailable`, {
        provider: t(`${providerKeyPrefix}.${statusProvider}`),
      });

  const getProviderLabel = useCallback((provider: AiFeatureProvider) => {
    return t(`${providerKeyPrefix}.${provider}`);
  }, [t, providerKeyPrefix]);

  return (
    <div className={styles.panel}>
      <div className={styles.selectGroup}>
        <div className={styles.selectWrap}>
          <span className={styles.iconWrap} data-testid="provider-select-icon" aria-hidden="true">
            <ProviderModelIcon providerId={selectedProvider} size={14} colored />
          </span>
          <select
            className={styles.providerSelect}
            value={selectedProvider}
            onChange={(e) => onProviderChange(e.target.value as AiFeatureProvider)}
            aria-label={t(`${settingsKeyPrefix}.label`)}
          >
            {(['claude', 'codex', 'opencode'] as AiFeatureProvider[]).map((provider) => (
              <option key={provider} value={provider} disabled={!config.availability[provider]}>
                {getProviderLabel(provider)}{!config.availability[provider] ? ` (${t(`${settingsKeyPrefix}.providerUnavailable`)})` : ''}
              </option>
            ))}
          </select>
          <ChevronDownIcon size={16} className={styles.selectArrow} />
        </div>

        <div className={styles.selectWrap}>
          <select
            id={`${settingsKeyPrefix}-model`}
            className={styles.modelSelect}
            value={config.models[selectedProvider]}
            onChange={(e) => onModelChange(e.target.value)}
            aria-label={t(`${settingsKeyPrefix}.modelLabel`)}
          >
            {modelOptions.map((model) => (
              <option key={model.id} value={model.id}>
                {model.label}
              </option>
            ))}
          </select>
          <ChevronDownIcon size={16} className={styles.selectArrow} />
        </div>
      </div>

      <div className={styles.actionsRow} data-testid="ai-feature-actions-row">
        <div className={styles.statusHint} data-testid="ai-feature-status-hint">
          <InfoIcon size={16} />
          <span className={styles.statusText} title={statusText}>{statusText}</span>
        </div>

      </div>
    </div>
  );
};

export default AiFeatureProviderModelPanel;
