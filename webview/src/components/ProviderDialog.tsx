import { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { CloudIcon, EyeIcon, EyeOffIcon, InfoIcon, RefreshIcon, ShieldIcon, XCircleIcon } from './Icons';
import type { ProviderConfig } from '../types/provider';
import { CLAUDE_MODEL_MAPPING_ENV_KEYS, PROVIDER_PRESETS } from '../types/provider';
import { GuidedProviderDialog, type GuidedStep } from './shared/GuidedProviderDialog';
import DualViewSwitcher, { type DualViewMode } from './shared/DualViewSwitcher';
import { claudeConfigAdapter, type ClaudeConfigFormState } from './shared/dualView/adapters';
import EnvRecordEditor from './shared/dualView/EnvRecordEditor';
import { fetchProviderModels } from '../utils/bridge';

const INFO_ICON_STYLE: React.CSSProperties = { fontSize: '12px', marginRight: '4px' };
const NOTICE_MT_STYLE: React.CSSProperties = { marginTop: '8px' };

const OFFICIAL_DIRECT_PRESET_ID = 'official_direct';
const OFFICIAL_ANTHROPIC_URL = 'https://api.anthropic.com';
const CUSTOM_PRESET_ID = 'custom';
const CUSTOM_PROXY_PRESET_ID = 'custom_proxy';
const FETCHED_MODELS_DATALIST_ID = 'provider-fetched-models';

const isOfficialAnthropicEndpoint = (baseUrl?: string) => {
  const normalized = (baseUrl || '').trim().toLowerCase();
  if (normalized === '') return true;
  try {
    const url = new URL(normalized);
    return url.hostname === 'api.anthropic.com';
  } catch {
    // Invalid URL cannot be an official endpoint
    return false;
  }
};

interface BuildConfigOptions {
  envOverrides?: Record<string, string>;
  defaultBaseUrl?: string;
  includeModelMapping?: boolean;
}

const trimString = (value: unknown): string => (
  typeof value === 'string' ? value.trim() : ''
);

const readHaikuModel = (env: Record<string, unknown>): string => (
  trimString(env.ANTHROPIC_DEFAULT_HAIKU_MODEL)
);

const readStringEnv = (env: Record<string, unknown>, key: string): string => (
  trimString(env[key])
);

function normalizeProviderEnvForSave(
  env: Record<string, unknown>,
  options: { stripAllModelMappings?: boolean } = {}
): Record<string, unknown> {
  const nextEnv = { ...env };

  if (options.stripAllModelMappings) {
    for (const key of CLAUDE_MODEL_MAPPING_ENV_KEYS) {
      delete nextEnv[key];
    }
    return nextEnv;
  }

  const mainModel = trimString(nextEnv.ANTHROPIC_MODEL);
  if (!mainModel) {
    delete nextEnv.ANTHROPIC_MODEL;
    return nextEnv;
  }

  const specificModels = [
    trimString(nextEnv.ANTHROPIC_DEFAULT_HAIKU_MODEL),
    trimString(nextEnv.ANTHROPIC_DEFAULT_SONNET_MODEL),
    trimString(nextEnv.ANTHROPIC_DEFAULT_OPUS_MODEL),
    trimString(nextEnv.ANTHROPIC_DEFAULT_FABLE_MODEL),
  ].filter(Boolean);

  if (specificModels.length === 0 || specificModels.every(model => model === mainModel)) {
    delete nextEnv.ANTHROPIC_MODEL;
  }

  return nextEnv;
}

function sanitizeProviderJsonConfig(
  rawJsonConfig: string,
  options: { stripAllModelMappings?: boolean } = {}
): string {
  let parsed: Record<string, unknown>;
  try {
    parsed = rawJsonConfig ? JSON.parse(rawJsonConfig) : {};
  } catch {
    return rawJsonConfig;
  }
  const prevEnv = parsed.env && typeof parsed.env === 'object'
    ? parsed.env as Record<string, unknown>
    : {};
  const nextEnv = normalizeProviderEnvForSave(prevEnv, options);

  const nextConfig = Object.keys(nextEnv).length > 0
    ? { ...parsed, env: nextEnv }
    : Object.fromEntries(Object.entries(parsed).filter(([key]) => key !== 'env'));

  return JSON.stringify(nextConfig, null, 2);
}

interface ProviderDialogProps {
  isOpen: boolean;
  provider?: ProviderConfig | null; // null indicates add mode
  onClose: () => void;
  onSave: (data: {
    providerName: string;
    remark: string;
    apiKey: string;
    apiUrl: string;
    jsonConfig: string;
  }) => void;
  onDelete?: (provider: ProviderConfig) => void;
  canDelete?: boolean;
  addToast: (message: string, type: 'success' | 'error' | 'info') => void;
}

export default function ProviderDialog({
  isOpen,
  provider,
  onClose,
  onSave,
  onDelete: _onDelete,
  canDelete: _canDelete = true,
  addToast: _addToast,
}: ProviderDialogProps) {
  const { t } = useTranslation();
  const isAdding = !provider;

  const [providerName, setProviderName] = useState('');
  const [remark, setRemark] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [apiUrl, setApiUrl] = useState('');
  const [activePreset, setActivePreset] = useState<string>('custom');

  const [haikuModel, setHaikuModel] = useState('');
  const [haikuDisplayName, setHaikuDisplayName] = useState('');
  const [sonnetModel, setSonnetModel] = useState('');
  const [sonnetDisplayName, setSonnetDisplayName] = useState('');
  const [opusModel, setOpusModel] = useState('');
  const [opusDisplayName, setOpusDisplayName] = useState('');
  const [fableModel, setFableModel] = useState('');
  const [fableDisplayName, setFableDisplayName] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [jsonConfig, setJsonConfig] = useState('');
  const [jsonError, setJsonError] = useState('');
  // 凭证步环境变量/配置区块的 JSON↔表单视图模式(form=自定义 env 表单;json=完整 settingsConfig)
  const [envViewMode, setEnvViewMode] = useState<DualViewMode>('form');
  // 引导步骤:0=接入方式 1=凭证 2=模型映射
  const [currentStep, setCurrentStep] = useState(0);
  // 第三方/代理预设:从 baseUrl+key 动态拉取的真实模型列表(Phase 2 后端能力的前端入口)
  const [fetchedModels, setFetchedModels] = useState<string[]>([]);
  const [fetchingModels, setFetchingModels] = useState(false);
  const [fetchError, setFetchError] = useState('');
  const thirdPartyPresets = PROVIDER_PRESETS;
  const isOfficialDirectMode = activePreset === OFFICIAL_DIRECT_PRESET_ID;
  // Model mapping should always be shown – the 'custom' preset button was removed
  // from the UI, so users can never explicitly opt out of model mapping.
  const showModelMappingSection = true;

  const buildConfig = ({
    envOverrides = {},
    defaultBaseUrl = OFFICIAL_ANTHROPIC_URL,
    includeModelMapping = true,
  }: BuildConfigOptions = {}) => {
    const normalizedEnv = normalizeProviderEnvForSave(envOverrides, {
      stripAllModelMappings: !includeModelMapping,
    });

    return {
      env: {
        ANTHROPIC_AUTH_TOKEN: '',
        ANTHROPIC_BASE_URL: defaultBaseUrl,
        ...(includeModelMapping ? {
          ANTHROPIC_DEFAULT_SONNET_MODEL: '',
          ANTHROPIC_DEFAULT_SONNET_MODEL_NAME: '',
          ANTHROPIC_DEFAULT_OPUS_MODEL: '',
          ANTHROPIC_DEFAULT_OPUS_MODEL_NAME: '',
          ANTHROPIC_DEFAULT_FABLE_MODEL: '',
          ANTHROPIC_DEFAULT_FABLE_MODEL_NAME: '',
          ANTHROPIC_DEFAULT_HAIKU_MODEL: '',
          ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME: '',
        } : {}),
        ...normalizedEnv,
      }
    };
  };

  const updateEnvField = (key: string, value: string) => {
    try {
      const parsed = jsonConfig ? JSON.parse(jsonConfig) : {};
      const prevEnv = (parsed.env || {}) as Record<string, any>;
      const trimmed = typeof value === 'string' ? value.trim() : value;

      let nextEnv: Record<string, any>;
      if (!trimmed) {
        const { [key]: _, ...rest } = prevEnv;
        nextEnv = rest;
      } else {
        nextEnv = { ...prevEnv, [key]: value };
      }

      const normalizedEnv = normalizeProviderEnvForSave(nextEnv, {
        stripAllModelMappings: activePreset === CUSTOM_PRESET_ID,
      });

      const nextConfig = Object.keys(normalizedEnv).length > 0
        ? { ...parsed, env: normalizedEnv }
        : Object.fromEntries(Object.entries(parsed).filter(([k]) => k !== 'env'));

      setJsonConfig(JSON.stringify(nextConfig, null, 2));
      setJsonError('');
    } catch (err) {
      // silently ignore – the JSON textarea will show a validation error
    }
  };

  // Apply preset configuration
  const handlePresetClick = (presetId: string) => {
    setActivePreset(presetId);

    if (presetId === OFFICIAL_DIRECT_PRESET_ID) {
      const config = buildConfig();
      setJsonConfig(JSON.stringify(config, null, 2));
      setApiKey('');
      setApiUrl(OFFICIAL_ANTHROPIC_URL);
      setHaikuModel('');
      setHaikuDisplayName('');
      setSonnetModel('');
      setSonnetDisplayName('');
      setOpusModel('');
      setOpusDisplayName('');
      setFableModel('');
      setFableDisplayName('');
      setJsonError('');
      return;
    }

    if (presetId === CUSTOM_PRESET_ID) {
      const config = buildConfig({
        defaultBaseUrl: '',
        includeModelMapping: false,
      });
      setJsonConfig(JSON.stringify(config, null, 2));
      setApiKey('');
      setApiUrl('');
      setHaikuModel('');
      setHaikuDisplayName('');
      setSonnetModel('');
      setSonnetDisplayName('');
      setOpusModel('');
      setOpusDisplayName('');
      setFableModel('');
      setFableDisplayName('');
      setJsonError('');
      return;
    }

    const preset = PROVIDER_PRESETS.find(p => p.id === presetId);
    if (!preset) return;

    // Apply preset configuration
    const config = buildConfig({ envOverrides: preset.env });
    setJsonConfig(JSON.stringify(config, null, 2));

    // Sync form fields with preset values
    const env = preset.env;
    setApiUrl(env.ANTHROPIC_BASE_URL || '');
    setApiKey(env.ANTHROPIC_AUTH_TOKEN || '');
    setHaikuModel(readHaikuModel(env));
    setHaikuDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME'));
    setSonnetModel(env.ANTHROPIC_DEFAULT_SONNET_MODEL || '');
    setSonnetDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_SONNET_MODEL_NAME'));
    setOpusModel(env.ANTHROPIC_DEFAULT_OPUS_MODEL || '');
    setOpusDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_OPUS_MODEL_NAME'));
    setFableModel(env.ANTHROPIC_DEFAULT_FABLE_MODEL || '');
    setFableDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_FABLE_MODEL_NAME'));
    setJsonError('');
  };

  // Auto-detect matching preset based on environment variables
  const detectMatchingPreset = (env: Record<string, string | undefined>): string => {
    const baseUrl = env.ANTHROPIC_BASE_URL || '';

    if (isOfficialAnthropicEndpoint(baseUrl)) {
      return OFFICIAL_DIRECT_PRESET_ID;
    }

    for (const preset of PROVIDER_PRESETS) {
      if (preset.id === 'custom') continue;
      const presetBaseUrl = preset.env.ANTHROPIC_BASE_URL || '';
      if (baseUrl && presetBaseUrl && baseUrl === presetBaseUrl) {
        return preset.id;
      }
    }
    // Unrecognized URL: treat as a custom third-party proxy.
    // Return a non-'custom' value so model mapping stays enabled.
    return CUSTOM_PROXY_PRESET_ID;
  };


  // Initialize form
  useEffect(() => {
    if (isOpen) {
      if (provider) {
        // Edit mode
        setProviderName(provider.name || '');
        setRemark(provider.remark || provider.websiteUrl || '');
        setApiKey(provider.settingsConfig?.env?.ANTHROPIC_AUTH_TOKEN || provider.settingsConfig?.env?.ANTHROPIC_API_KEY || '');
        // In edit mode, do not populate default values to avoid overwriting the user's third-party proxy URL
        setApiUrl(provider.settingsConfig?.env?.ANTHROPIC_BASE_URL || '');
        const env = provider.settingsConfig?.env || {};

        // Auto-detect matching preset
        setActivePreset(detectMatchingPreset(env));

        setHaikuModel(readHaikuModel(env));
        setHaikuDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME'));
        setSonnetModel(env.ANTHROPIC_DEFAULT_SONNET_MODEL || '');
        setSonnetDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_SONNET_MODEL_NAME'));
        setOpusModel(env.ANTHROPIC_DEFAULT_OPUS_MODEL || '');
        setOpusDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_OPUS_MODEL_NAME'));
        setFableModel(env.ANTHROPIC_DEFAULT_FABLE_MODEL || '');
        setFableDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_FABLE_MODEL_NAME'));

        const config = provider.settingsConfig || buildConfig();
        setJsonConfig(JSON.stringify(config, null, 2));
      } else {
        // Add mode
        setActivePreset(OFFICIAL_DIRECT_PRESET_ID);
        setProviderName('');
        setRemark('');
        setApiKey('');
        setApiUrl(OFFICIAL_ANTHROPIC_URL);

        setHaikuModel('');
        setHaikuDisplayName('');
        setSonnetModel('');
        setSonnetDisplayName('');
        setOpusModel('');
        setOpusDisplayName('');
        setFableModel('');
        setFableDisplayName('');
        const config = buildConfig();
        setJsonConfig(JSON.stringify(config, null, 2));
      }
      setShowApiKey(false);
      setJsonError('');
      setCurrentStep(0);
      setFetchedModels([]);
      setFetchingModels(false);
      setFetchError('');
    }
  }, [isOpen, provider]);

  // ESC is handled by GuidedProviderDialog (BaseDialog)

  const handleApiKeyChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newApiKey = e.target.value;
    setApiKey(newApiKey);
    updateEnvField('ANTHROPIC_AUTH_TOKEN', newApiKey);
  };

  const handleApiUrlChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newApiUrl = e.target.value;
    setApiUrl(newApiUrl);
    updateEnvField('ANTHROPIC_BASE_URL', newApiUrl);
    setActivePreset(detectMatchingPreset({ ANTHROPIC_BASE_URL: newApiUrl }));
  };

  const handleHaikuModelChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setHaikuModel(value);
    updateEnvField('ANTHROPIC_DEFAULT_HAIKU_MODEL', value);
  };

  const handleHaikuDisplayNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setHaikuDisplayName(value);
    updateEnvField('ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME', value);
  };

  const handleSonnetModelChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setSonnetModel(value);
    updateEnvField('ANTHROPIC_DEFAULT_SONNET_MODEL', value);
  };

  const handleSonnetDisplayNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setSonnetDisplayName(value);
    updateEnvField('ANTHROPIC_DEFAULT_SONNET_MODEL_NAME', value);
  };

  const handleOpusModelChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setOpusModel(value);
    updateEnvField('ANTHROPIC_DEFAULT_OPUS_MODEL', value);
  };

  const handleOpusDisplayNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setOpusDisplayName(value);
    updateEnvField('ANTHROPIC_DEFAULT_OPUS_MODEL_NAME', value);
  };

  const handleFableModelChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setFableModel(value);
    updateEnvField('ANTHROPIC_DEFAULT_FABLE_MODEL', value);
  };

  const handleFableDisplayNameChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.target.value;
    setFableDisplayName(value);
    updateEnvField('ANTHROPIC_DEFAULT_FABLE_MODEL_NAME', value);
  };

  // 从 settingsConfig 对象同步派生表单 state(apiKey/apiUrl/preset/model 映射)。
  // DualViewSwitcher 的 onFormStateChange 调用:JSON 编辑后把 env 保留 key 同步回凭证/模型步,
  // 避免双写冲突。旧 handleJsonChange 的 reconcile 逻辑抽此复用(parse 由调用方保证成功)。
  const reconcileFromConfig = (config: unknown) => {
    const env = (config && typeof config === 'object' && !Array.isArray(config)
      ? (config as { env?: Record<string, any> }).env
      : undefined) || {};
    const has = Object.prototype.hasOwnProperty;

    if (has.call(env, 'ANTHROPIC_AUTH_TOKEN')) {
      setApiKey(env.ANTHROPIC_AUTH_TOKEN || '');
    } else if (has.call(env, 'ANTHROPIC_API_KEY')) {
      setApiKey(env.ANTHROPIC_API_KEY || '');
    } else {
      setApiKey('');
    }

    if (has.call(env, 'ANTHROPIC_BASE_URL')) {
      setApiUrl(env.ANTHROPIC_BASE_URL || '');
    } else {
      setApiUrl('');
    }

    setActivePreset(detectMatchingPreset(env));

    if (has.call(env, 'ANTHROPIC_DEFAULT_HAIKU_MODEL')) {
      setHaikuModel(readHaikuModel(env));
    } else {
      setHaikuModel('');
    }
    if (has.call(env, 'ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME')) {
      setHaikuDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME'));
    } else {
      setHaikuDisplayName('');
    }

    if (has.call(env, 'ANTHROPIC_DEFAULT_SONNET_MODEL')) {
      setSonnetModel(env.ANTHROPIC_DEFAULT_SONNET_MODEL || '');
    } else {
      setSonnetModel('');
    }
    if (has.call(env, 'ANTHROPIC_DEFAULT_SONNET_MODEL_NAME')) {
      setSonnetDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_SONNET_MODEL_NAME'));
    } else {
      setSonnetDisplayName('');
    }

    if (has.call(env, 'ANTHROPIC_DEFAULT_OPUS_MODEL')) {
      setOpusModel(env.ANTHROPIC_DEFAULT_OPUS_MODEL || '');
    } else {
      setOpusModel('');
    }
    if (has.call(env, 'ANTHROPIC_DEFAULT_OPUS_MODEL_NAME')) {
      setOpusDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_OPUS_MODEL_NAME'));
    } else {
      setOpusDisplayName('');
    }
    if (has.call(env, 'ANTHROPIC_DEFAULT_FABLE_MODEL')) {
      setFableModel(env.ANTHROPIC_DEFAULT_FABLE_MODEL || '');
    } else {
      setFableModel('');
    }
    if (has.call(env, 'ANTHROPIC_DEFAULT_FABLE_MODEL_NAME')) {
      setFableDisplayName(readStringEnv(env, 'ANTHROPIC_DEFAULT_FABLE_MODEL_NAME'));
    } else {
      setFableDisplayName('');
    }
  };

  // DualViewSwitcher 的 formState:从 jsonConfig 解析出的 settingsConfig 对象。
  // 非法 JSON 兜底 {}(切到 JSON 模式时 jsonDraft 由 adapter.serialize 重置,用户可修正)。
  const claudeFormState = useMemo<Record<string, any>>(() => {
    try {
      const parsed = jsonConfig ? JSON.parse(jsonConfig) : {};
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? parsed as Record<string, any>
        : {};
    } catch {
      return {};
    }
  }, [jsonConfig]);

  const handleSave = () => {
    let finalJsonConfig = jsonConfig;
    let finalApiUrl = apiUrl;

    if (isOfficialDirectMode) {
      try {
        const parsed = jsonConfig ? JSON.parse(jsonConfig) : {};
        const env = { ...(parsed.env || {}), ANTHROPIC_BASE_URL: OFFICIAL_ANTHROPIC_URL };
        finalJsonConfig = JSON.stringify({ ...parsed, env }, null, 2);
      } catch {
        finalJsonConfig = JSON.stringify(buildConfig({
          envOverrides: {
            ANTHROPIC_AUTH_TOKEN: apiKey,
            ANTHROPIC_DEFAULT_HAIKU_MODEL: haikuModel,
            ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME: haikuDisplayName,
            ANTHROPIC_DEFAULT_SONNET_MODEL: sonnetModel,
            ANTHROPIC_DEFAULT_SONNET_MODEL_NAME: sonnetDisplayName,
            ANTHROPIC_DEFAULT_OPUS_MODEL: opusModel,
            ANTHROPIC_DEFAULT_OPUS_MODEL_NAME: opusDisplayName,
            ANTHROPIC_DEFAULT_FABLE_MODEL: fableModel,
            ANTHROPIC_DEFAULT_FABLE_MODEL_NAME: fableDisplayName,
          },
        }), null, 2);
      }
      finalApiUrl = OFFICIAL_ANTHROPIC_URL;
    }

    try {
      finalJsonConfig = sanitizeProviderJsonConfig(finalJsonConfig, {
        stripAllModelMappings: activePreset === CUSTOM_PRESET_ID,
      });
    } catch {
      finalJsonConfig = JSON.stringify(buildConfig({
        envOverrides: {
          ANTHROPIC_AUTH_TOKEN: apiKey,
          ANTHROPIC_BASE_URL: finalApiUrl,
          ANTHROPIC_DEFAULT_HAIKU_MODEL: haikuModel,
          ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME: haikuDisplayName,
          ANTHROPIC_DEFAULT_SONNET_MODEL: sonnetModel,
          ANTHROPIC_DEFAULT_SONNET_MODEL_NAME: sonnetDisplayName,
          ANTHROPIC_DEFAULT_OPUS_MODEL: opusModel,
          ANTHROPIC_DEFAULT_OPUS_MODEL_NAME: opusDisplayName,
          ANTHROPIC_DEFAULT_FABLE_MODEL: fableModel,
          ANTHROPIC_DEFAULT_FABLE_MODEL_NAME: fableDisplayName,
        },
        defaultBaseUrl: activePreset === CUSTOM_PRESET_ID ? '' : finalApiUrl,
        includeModelMapping: activePreset !== CUSTOM_PRESET_ID,
      }), null, 2);
    }

    onSave({
      providerName,
      remark,
      apiKey,
      apiUrl: finalApiUrl,
      jsonConfig: finalJsonConfig,
    });
  };

  // 第三方/代理:用 baseUrl+key 动态拉取真实模型列表。
  // 业务逻辑下沉后端 ModelFetchService(候选 URL 构造 + HTTP GET + 解析),前端只做入口。
  const handleFetchModels = async () => {
    if (!apiUrl.trim() || fetchingModels) return;
    setFetchingModels(true);
    setFetchError('');
    try {
      const result = await fetchProviderModels({
        baseUrl: apiUrl.trim(),
        apiKey: apiKey.trim() || undefined,
      });
      if (result.error) {
        setFetchError(result.error);
        setFetchedModels([]);
      } else if (result.models && result.models.length > 0) {
        setFetchedModels(result.models);
      } else {
        setFetchError(t('settings.provider.dialog.fetchModelsEmpty', '未返回任何模型,请手动填写'));
      }
    } catch {
      setFetchError(t('settings.provider.dialog.fetchModelsFailed', '拉取失败,请手动填写'));
    } finally {
      setFetchingModels(false);
    }
  };

  // 引导步骤定义(标题走 i18n)
  const steps: GuidedStep[] = [
    { id: 'access', title: t('settings.provider.dialog.stepAccess', '接入方式') },
    { id: 'credentials', title: t('settings.provider.dialog.stepCredentials', '凭证') },
    { id: 'models', title: t('settings.provider.dialog.stepModels', '模型映射') },
  ];

  // 前进门禁:凭证步要求 providerName 非空;其余步总允许(接入方式默认选中官方,模型映射可空保存)
  const canProceed = currentStep === 1 ? providerName.trim().length > 0 : true;

  if (!isOpen) {
    return null;
  }

  return (
    <GuidedProviderDialog
      isOpen={isOpen}
      onClose={onClose}
      ariaLabel={isAdding ? t('settings.provider.dialog.addTitle') : t('settings.provider.dialog.editTitle', { name: provider?.name })}
      steps={steps}
      currentStep={currentStep}
      onStepChange={setCurrentStep}
      canProceed={canProceed}
      onFinish={handleSave}
      finishLabel={isAdding ? t('settings.provider.dialog.confirmAdd') : t('settings.provider.dialog.saveChanges')}
      size="lg"
    >
      {/* ===== Step 0:接入方式 ===== */}
      {currentStep === 0 && (
        <>
          <p className="dialog-desc">
            {isAdding ? t('settings.provider.dialog.addDescription') : t('settings.provider.dialog.editDescription')}
          </p>

          <div className="notice-box notice-box--info">
            <ShieldIcon size={16} />
            {t('settings.provider.dialog.securityNotice')}
          </div>

          <div className="form-group">
            <label>{t('settings.provider.dialog.officialSectionTitle')}</label>
            <div className="preset-buttons" role="radiogroup" aria-label={t('settings.provider.dialog.officialSectionTitle')}>
              <button
                type="button"
                role="radio"
                aria-checked={activePreset === OFFICIAL_DIRECT_PRESET_ID}
                className={`preset-btn ${activePreset === OFFICIAL_DIRECT_PRESET_ID ? 'active' : ''}`}
                onClick={() => handlePresetClick(OFFICIAL_DIRECT_PRESET_ID)}
              >
                <span aria-hidden="true" className="preset-btn-icon">
                  <ProviderModelIcon providerId="claude" size={16} colored />
                </span>
                {t('settings.provider.dialog.officialPreset')}
              </button>
            </div>
            <small className="form-hint">{t('settings.provider.dialog.officialSectionHint')}</small>
          </div>

          <div className="form-group">
            <label>{t('settings.provider.dialog.proxySectionTitle')}</label>
            <div className="preset-buttons" role="radiogroup" aria-label={t('settings.provider.dialog.proxySectionTitle')}>
              {thirdPartyPresets.map((preset) => (
                <button
                  key={preset.id}
                  type="button"
                  role="radio"
                  aria-checked={activePreset === preset.id}
                  className={`preset-btn ${activePreset === preset.id ? 'active' : ''}`}
                  onClick={() => handlePresetClick(preset.id)}
                >
                  <span aria-hidden="true" className="preset-btn-icon">
                    <ProviderModelIcon providerId={preset.id} size={16} colored />
                  </span>
                  {t(preset.nameKey)}
                </button>
              ))}
            </div>
            <small className="form-hint">{t('settings.provider.dialog.proxySectionHint')}</small>
          </div>
        </>
      )}

      {/* ===== Step 1:凭证 ===== */}
      {currentStep === 1 && (
        <>
          <div className="form-group">
            <label htmlFor="providerName">
              {t('settings.provider.dialog.providerName')}
              <span className="required">{t('settings.provider.dialog.required')}</span>
            </label>
            <input
              id="providerName"
              type="text"
              className="form-input"
              placeholder={t('settings.provider.dialog.providerNamePlaceholder')}
              value={providerName}
              onChange={(e) => setProviderName(e.target.value)}
            />
          </div>

          <div className="form-group">
            <label htmlFor="remark">{t('settings.provider.dialog.remark')}</label>
            <input
              id="remark"
              type="text"
              className="form-input"
              placeholder={t('settings.provider.dialog.remarkPlaceholder')}
              value={remark}
              onChange={(e) => setRemark(e.target.value)}
            />
          </div>

          <div className="form-group">
            <label htmlFor="apiKey">
              {t('settings.provider.dialog.apiKey')}
              <span className="required">{t('settings.provider.dialog.required')}</span>
            </label>
            <div className="input-with-visibility">
              <input
                id="apiKey"
                type={showApiKey ? 'text' : 'password'}
                className="form-input"
                placeholder={t('settings.provider.dialog.apiKeyPlaceholder')}
                value={apiKey}
                onChange={handleApiKeyChange}
              />
              <button
                type="button"
                className="visibility-toggle"
                onClick={() => setShowApiKey(!showApiKey)}
                title={showApiKey ? t('settings.provider.dialog.hideApiKey') : t('settings.provider.dialog.showApiKey')}
              >
                {showApiKey ? <EyeOffIcon size={16} /> : <EyeIcon size={16} />}
              </button>
            </div>
            <small className="form-hint">{t('settings.provider.dialog.apiKeyHint')}</small>
          </div>

          <div className="form-group">
            <label htmlFor="apiUrl">
              {t('settings.provider.dialog.apiUrl')}
              <span className="required">{t('settings.provider.dialog.required')}</span>
            </label>
            <input
              id="apiUrl"
              type="text"
              className="form-input"
              placeholder={t('settings.provider.dialog.apiUrlPlaceholder')}
              value={apiUrl}
              onChange={handleApiUrlChange}
              readOnly={isOfficialDirectMode}
            />
            <small className="form-hint">
              <InfoIcon size={16} style={INFO_ICON_STYLE} />
              {isOfficialDirectMode
                ? t('settings.provider.dialog.apiUrlLockedHint')
                : t('settings.provider.dialog.apiUrlHint')}
            </small>
            {!isOfficialAnthropicEndpoint(apiUrl) && (
              <div className="notice-box notice-box--warning" style={NOTICE_MT_STYLE}>
                <CloudIcon size={16} />
                {t('settings.provider.dialog.proxyEndpointWarning')}
              </div>
            )}
          </div>

          {/* 环境变量/配置 — JSON/表单双视图(分块切换,双向同步) */}
          <DualViewSwitcher
            label={t('settings.provider.dialog.jsonConfig')}
            formState={claudeFormState}
            onFormStateChange={(config: ClaudeConfigFormState) => {
              setJsonConfig(JSON.stringify(config, null, 2));
              reconcileFromConfig(config);
              setJsonError('');
            }}
            adapter={claudeConfigAdapter}
            mode={envViewMode}
            onModeChange={setEnvViewMode}
            jsonHint={t('settings.provider.dialog.jsonConfigDescription', '完整 settingsConfig:env + model + alwaysThinkingEnabled + …(切换到 JSON 视图可编辑全部字段)')}
            renderForm={(config: ClaudeConfigFormState, onChange: (s: ClaudeConfigFormState) => void) => (
              <EnvRecordEditor config={config} onChange={onChange} />
            )}
          />
          {jsonError && (
            <p className="json-error">
              <XCircleIcon size={16} />
              {jsonError}
            </p>
          )}
        </>
      )}

      {/* ===== Step 2:模型映射 ===== */}
      {currentStep === 2 && showModelMappingSection && (
        <>
          <div className="form-group">
            <label>{t('settings.provider.dialog.fetchModelsTitle', '拉取可用模型')}</label>
            <small className="form-hint" style={{ marginBottom: '8px', display: 'block' }}>
              {t('settings.provider.dialog.fetchModelsHint', '填入上方 baseUrl 与 key 后,可自动拉取该代理支持的真实模型列表,在下方输入框下拉选择。')}
            </small>
            <div className="json-toolbar">
              <button
                type="button"
                className="format-btn"
                onClick={handleFetchModels}
                disabled={fetchingModels || !apiUrl.trim()}
              >
                <RefreshIcon size={14} />
                {fetchingModels
                  ? t('settings.provider.dialog.fetchModelsLoading', '拉取中…')
                  : t('settings.provider.dialog.fetchModelsButton', '拉取可用模型')}
              </button>
            </div>
            {fetchError && (
              <p className="json-error" style={{ marginTop: '8px' }}>
                <XCircleIcon size={16} />
                {fetchError}
              </p>
            )}
            {fetchedModels.length > 0 && (
              <small className="form-hint" style={{ marginTop: '8px', display: 'block' }}>
                {t('settings.provider.dialog.fetchModelsSuccess', { count: fetchedModels.length, defaultValue: '已拉取 {{count}} 个模型,下方输入框可下拉选择' })}
              </small>
            )}
            <datalist id={FETCHED_MODELS_DATALIST_ID}>
              {fetchedModels.map((m) => <option key={m} value={m} />)}
            </datalist>
          </div>

          <div className="form-group">
            <label>{t('settings.provider.dialog.modelMapping')}</label>
            <div className="model-mapping-grid">
              <div className="model-mapping-row">
                <div className="model-mapping-role">{t('settings.provider.dialog.sonnetRole', 'Sonnet')}</div>
                <div className="model-mapping-field">
                  <label htmlFor="sonnetDisplayName">{t('settings.provider.dialog.displayName', 'Display Name')}</label>
                  <input
                    id="sonnetDisplayName"
                    type="text"
                    className="form-input"
                    placeholder={t('settings.provider.dialog.displayNamePlaceholder', 'mimo-v2.5')}
                    value={sonnetDisplayName}
                    onChange={handleSonnetDisplayNameChange}
                  />
                </div>
                <div className="model-mapping-field">
                  <label htmlFor="sonnetModel">{t('settings.provider.dialog.requestModel', 'Actual Request Model')}</label>
                  <input
                    id="sonnetModel"
                    type="text"
                    className="form-input"
                    list={FETCHED_MODELS_DATALIST_ID}
                    placeholder={t('settings.provider.dialog.sonnetModelPlaceholder')}
                    value={sonnetModel}
                    onChange={handleSonnetModelChange}
                  />
                </div>
              </div>
              <div className="model-mapping-row">
                <div className="model-mapping-role">{t('settings.provider.dialog.opusRole', 'Opus')}</div>
                <div className="model-mapping-field">
                  <label htmlFor="opusDisplayName">{t('settings.provider.dialog.displayName', 'Display Name')}</label>
                  <input
                    id="opusDisplayName"
                    type="text"
                    className="form-input"
                    placeholder={t('settings.provider.dialog.displayNamePlaceholder', 'mimo-v2.5-pro')}
                    value={opusDisplayName}
                    onChange={handleOpusDisplayNameChange}
                  />
                </div>
                <div className="model-mapping-field">
                  <label htmlFor="opusModel">{t('settings.provider.dialog.requestModel', 'Actual Request Model')}</label>
                  <input
                    id="opusModel"
                    type="text"
                    className="form-input"
                    list={FETCHED_MODELS_DATALIST_ID}
                    placeholder={t('settings.provider.dialog.opusModelPlaceholder')}
                    value={opusModel}
                    onChange={handleOpusModelChange}
                  />
                </div>
              </div>
              <div className="model-mapping-row">
                <div className="model-mapping-role">{t('settings.provider.dialog.fableRole', 'Fable')}</div>
                <div className="model-mapping-field">
                  <label htmlFor="fableDisplayName">{t('settings.provider.dialog.displayName', 'Display Name')}</label>
                  <input
                    id="fableDisplayName"
                    type="text"
                    className="form-input"
                    placeholder={t('settings.provider.dialog.displayNamePlaceholder', 'fable')}
                    value={fableDisplayName}
                    onChange={handleFableDisplayNameChange}
                  />
                </div>
                <div className="model-mapping-field">
                  <label htmlFor="fableModel">{t('settings.provider.dialog.requestModel', 'Actual Request Model')}</label>
                  <input
                    id="fableModel"
                    type="text"
                    className="form-input"
                    list={FETCHED_MODELS_DATALIST_ID}
                    placeholder={t('settings.provider.dialog.fableModelPlaceholder', 'your-fable-model')}
                    value={fableModel}
                    onChange={handleFableModelChange}
                  />
                </div>
              </div>
              <div className="model-mapping-row">
                <div className="model-mapping-role">{t('settings.provider.dialog.haikuRole', 'Haiku')}</div>
                <div className="model-mapping-field">
                  <label htmlFor="haikuDisplayName">{t('settings.provider.dialog.displayName', 'Display Name')}</label>
                  <input
                    id="haikuDisplayName"
                    type="text"
                    className="form-input"
                    placeholder={t('settings.provider.dialog.displayNamePlaceholder', 'mimo-v2.5')}
                    value={haikuDisplayName}
                    onChange={handleHaikuDisplayNameChange}
                  />
                </div>
                <div className="model-mapping-field">
                  <label htmlFor="haikuModel">{t('settings.provider.dialog.requestModel', 'Actual Request Model')}</label>
                  <input
                    id="haikuModel"
                    type="text"
                    className="form-input"
                    list={FETCHED_MODELS_DATALIST_ID}
                    placeholder={t('settings.provider.dialog.haikuModelPlaceholder')}
                    value={haikuModel}
                    onChange={handleHaikuModelChange}
                  />
                </div>
              </div>
            </div>
            <small className="form-hint">{t('settings.provider.dialog.modelMappingHint')}</small>
          </div>
        </>
      )}
    </GuidedProviderDialog>
  );
}
