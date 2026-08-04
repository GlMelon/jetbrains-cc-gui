import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { BracesIcon, RefreshIcon } from './Icons';
import DualViewSwitcher, { type DualViewMode } from './shared/DualViewSwitcher';
import { codexEnvAdapter, type CodexEnvFormState } from './shared/dualView/adapters';
import type { CodexProviderConfig, EnvVarEntry } from '../types/provider';
import {
  validateEnvVarEntries,
  ENV_VAR_VALUE_MAX_LENGTH,
} from '../types/provider';
import EnvVarEditor from './EnvVarEditor';
import { GuidedProviderDialog, type GuidedStep } from './shared/GuidedProviderDialog';
import { fetchProviderModels } from '../utils/bridge';
import { ProviderModelIcon } from './shared/ProviderModelIcon';

const OFFICIAL_DIRECT_PRESET_ID = 'official_direct';
const OFFICIAL_CODEX_PROVIDER_NAME = 'Official Codex Direct';
const OFFICIAL_CODEX_CONFIG_TOML = `disable_response_storage = true
model = "o3"
model_reasoning_effort = "high"
model_provider = "crs"

[model_providers.crs]
base_url = "https://api.openai.com/v1"
name = "crs"
requires_openai_auth = true
wire_api = "responses"`;
const DEFAULT_CODEX_AUTH_JSON = `{
  "OPENAI_API_KEY": ""
}`;
const CODEX_PROVIDER_PRESETS: Array<{
  id: string;
  name: string;
  nameKey: string;
  configToml: string;
  authJson: string;
}> = [];

const FORM_HEADER_STYLE: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  gap: '12px',
  flexWrap: 'wrap',
};
const FORMAT_BUTTON_STYLE: React.CSSProperties = {
  width: 'auto',
  minWidth: 'auto',
  flex: '0 0 auto',
  padding: '4px 10px',
  fontSize: '12px',
  lineHeight: 1.2,
  whiteSpace: 'nowrap',
};
const CODE_TEXTAREA_STYLE: React.CSSProperties = {
  fontFamily: 'var(--idea-editor-font-family, monospace)',
  fontSize: '12px',
  lineHeight: '1.5',
};
const FETCHED_CHIP_STYLE: React.CSSProperties = { fontSize: '12px', padding: '2px 8px' };
const FETCHED_LIST_STYLE: React.CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: '4px', marginTop: '4px' };

// 从 config.toml 文本粗提取 base_url(粗解析:TOML 行 base_url = "...");提取失败返回空串
const extractBaseUrlFromToml = (toml: string): string => {
  const m = toml.match(/base_url\s*=\s*"([^"]+)"/);
  return m ? m[1] : '';
};
// 从 auth.json 文本提取 API Key(优先 OPENAI_API_KEY);解析失败返回空串
const extractApiKeyFromAuth = (auth: string): string => {
  try {
    const parsed = JSON.parse(auth);
    if (parsed && typeof parsed === 'object') {
      return parsed.OPENAI_API_KEY || parsed.openai_api_key || '';
    }
  } catch {
    // 非法 JSON —— 返回空
  }
  return '';
};

interface CodexProviderDialogProps {
  isOpen: boolean;
  provider?: CodexProviderConfig | null;
  onClose: () => void;
  onSave: (provider: CodexProviderConfig) => void;
  addToast: (message: string, type: 'success' | 'error' | 'info') => void;
}

/**
 * Codex provider 新增/编辑弹窗 —— 对称 {@link OpenCodeProviderDialog}(Principle 6)。
 *
 * <p>三步引导流(复用 {@link GuidedProviderDialog} 骨架,对称 Claude {@link ProviderDialog}):
 * <ol>
 *   <li>基本信息:Provider Name</li>
 *   <li>接入与凭证:config.toml(含 base_url + model_provider)+ auth.json(凭据)</li>
 *   <li>模型与环境:Model Catalog(可从 base_url+key 动态拉取)+ Message/MCP 环境变量</li>
 * </ol>
 *
 * <p>模型拉取:从 config.toml 粗提取 base_url、从 auth.json 提取 OPENAI_API_KEY,
 * 调后端 {@code ModelFetchService} 拉取真实模型列表(业务逻辑下沉,前端只做入口),
 * 点击模型 id 追加到 Model Catalog(JSON 数组)。
 */
export default function CodexProviderDialog({
  isOpen,
  provider,
  onClose,
  onSave,
  addToast,
}: CodexProviderDialogProps) {
  const { t } = useTranslation();
  const isAdding = !provider;

  const [providerName, setProviderName] = useState('');
  const [configTomlJson, setConfigTomlJson] = useState('');
  const [authJson, setAuthJson] = useState('');
  const [modelCatalogJson, setModelCatalogJson] = useState('');
  const [messageEnvVars, setMessageEnvVars] = useState<EnvVarEntry[]>([]);
  const [mcpEnvVars, setMcpEnvVars] = useState<EnvVarEntry[]>([]);
  // 环境变量区块的 JSON↔表单视图模式(form 默认;JSON 视图可整体编辑两段 env)
  const [envViewMode, setEnvViewMode] = useState<DualViewMode>('form');
  // 引导步骤:0=基本信息 1=接入与凭证 2=模型与环境
  const [currentStep, setCurrentStep] = useState(0);
  // 从 base_url+key 动态拉取的真实模型列表(Phase 2 后端能力的前端入口)
  const [fetchedModels, setFetchedModels] = useState<string[]>([]);
  const [fetchingModels, setFetchingModels] = useState(false);
  const [fetchError, setFetchError] = useState('');
  const [activePreset, setActivePreset] = useState('custom');

  // Initialize form
  useEffect(() => {
    if (isOpen) {
      if (provider) {
        // Edit mode - load existing data
        setProviderName(provider.name || '');
        setConfigTomlJson(provider.configToml || '');
        setAuthJson(provider.authJson || '');
        setModelCatalogJson(JSON.stringify(provider.modelCatalog ?? provider.customModels ?? [], null, 2));
        setMessageEnvVars(provider.messageEnvVars || []);
        setMcpEnvVars(provider.mcpEnvVars || []);
        setActivePreset('custom');
      } else {
        // Add mode - reset with default template
        setProviderName('');
        setConfigTomlJson(`disable_response_storage = true
model = "your-model-id"
model_reasoning_effort = "high"
model_provider = "crs"

[model_providers.crs]
base_url = "https://api.example.com/v1"
name = "crs"
requires_openai_auth = true
wire_api = "responses"`);
        setAuthJson(`{
  "OPENAI_API_KEY": ""
}`);
        setModelCatalogJson('[]');
        setMessageEnvVars([]);
        setMcpEnvVars([]);
        setActivePreset(OFFICIAL_DIRECT_PRESET_ID);
      }
      setCurrentStep(0);
      setFetchedModels([]);
      setFetchingModels(false);
      setFetchError('');
    }
  }, [isOpen, provider]);

  const handlePresetClick = (presetId: string) => {
    if (presetId === OFFICIAL_DIRECT_PRESET_ID) {
      setActivePreset(OFFICIAL_DIRECT_PRESET_ID);
      setProviderName(OFFICIAL_CODEX_PROVIDER_NAME);
      setConfigTomlJson(OFFICIAL_CODEX_CONFIG_TOML);
      setAuthJson(DEFAULT_CODEX_AUTH_JSON);
      return;
    }

    const preset = CODEX_PROVIDER_PRESETS.find(item => item.id === presetId);
    if (!preset) return;

    setActivePreset(preset.id);
    setProviderName(preset.name);
    setConfigTomlJson(preset.configToml);
    setAuthJson(preset.authJson);
  };

  // Format JSON
  const handleFormatConfigJson = () => {
    try {
      const parsed = JSON.parse(configTomlJson);
      setConfigTomlJson(JSON.stringify(parsed, null, 2));
      addToast(t('settings.codexProvider.dialog.formatSuccess'), 'success');
    } catch (e) {
      addToast(t('settings.codexProvider.dialog.formatError'), 'error');
    }
  };

  const handleFormatAuthJson = () => {
    try {
      const parsed = JSON.parse(authJson);
      setAuthJson(JSON.stringify(parsed, null, 2));
      addToast(t('settings.codexProvider.dialog.formatSuccess'), 'success');
    } catch (e) {
      addToast(t('settings.codexProvider.dialog.formatError'), 'error');
    }
  };

  // ESC is handled by GuidedProviderDialog (BaseDialog)

  const reportEnvVarIssue = (
    issue: { reason: string; key?: string },
    sectionLabel: string,
  ): boolean => {
    const reasonKey = (() => {
      switch (issue.reason) {
        case 'invalid':
          return 'settings.codexProvider.dialog.envKeyInvalid';
        case 'protected':
          return 'settings.codexProvider.dialog.envKeyProtected';
        case 'duplicate':
          return 'settings.codexProvider.dialog.envKeyDuplicate';
        case 'value_too_long':
          return 'settings.codexProvider.dialog.envValueTooLong';
        default:
          return null;
      }
    })();
    if (!reasonKey) return false;
    addToast(
      `${sectionLabel}: ${t(reasonKey, { key: issue.key, max: ENV_VAR_VALUE_MAX_LENGTH })}`,
      'error',
    );
    return true;
  };

  // 从 config.toml 的 base_url + auth.json 的 key 动态拉取真实模型列表(业务逻辑下沉后端)
  const handleFetchModels = async () => {
    const baseUrl = extractBaseUrlFromToml(configTomlJson);
    if (!baseUrl || fetchingModels) return;
    const key = extractApiKeyFromAuth(authJson);
    setFetchingModels(true);
    setFetchError('');
    try {
      const result = await fetchProviderModels({
        baseUrl,
        apiKey: key || undefined,
      });
      if (result.error) {
        setFetchError(result.error);
        setFetchedModels([]);
      } else if (result.models && result.models.length > 0) {
        setFetchedModels(result.models);
      } else {
        setFetchError(t('settings.codexProvider.dialog.fetchModelsEmpty', '未返回任何模型'));
      }
    } catch {
      setFetchError(t('settings.codexProvider.dialog.fetchModelsFailed', '拉取失败,请确认 config.toml 的 base_url 与 auth.json 的 key'));
    } finally {
      setFetchingModels(false);
    }
  };

  // 点击拉取到的 model id,追加到 Model Catalog JSON 数组(已存在则跳过,非法 JSON 则忽略)
  const handleAppendModel = (modelId: string) => {
    try {
      const arr = modelCatalogJson.trim() ? JSON.parse(modelCatalogJson) : [];
      if (Array.isArray(arr) && !arr.includes(modelId)) {
        arr.push(modelId);
        setModelCatalogJson(JSON.stringify(arr, null, 2));
      }
    } catch {
      // modelCatalogJson 当前非法,不追加以免破坏用户正在编辑的内容
    }
  };

  const handleSave = () => {
    if (!providerName.trim()) {
      addToast(t('settings.codexProvider.dialog.nameRequired'), 'error');
      return;
    }

    // Validate auth.json format (must be valid JSON)
    if (authJson.trim()) {
      try {
        JSON.parse(authJson);
      } catch (e) {
        addToast(t('settings.codexProvider.dialog.authJsonError'), 'error');
        return;
      }
    }

    // Validate env vars before saving
    let parsedModelCatalog: CodexProviderConfig['modelCatalog'] = [];
    if (modelCatalogJson.trim()) {
      try {
        const parsed = JSON.parse(modelCatalogJson);
        if (!Array.isArray(parsed)) {
          addToast(t('settings.codexProvider.dialog.modelCatalogJsonError', 'Model catalog must be a JSON array'), 'error');
          return;
        }
        parsedModelCatalog = parsed;
      } catch {
        addToast(t('settings.codexProvider.dialog.modelCatalogJsonError', 'Model catalog must be a JSON array'), 'error');
        return;
      }
    }

    // Validate env vars before saving
    const messageIssues = validateEnvVarEntries(messageEnvVars);
    if (messageIssues.length > 0) {
      reportEnvVarIssue(messageIssues[0], t('settings.codexProvider.dialog.messageEnvLabel'));
      return;
    }
    const mcpIssues = validateEnvVarEntries(mcpEnvVars);
    if (mcpIssues.length > 0) {
      reportEnvVarIssue(mcpIssues[0], t('settings.codexProvider.dialog.mcpEnvLabel'));
      return;
    }

    const providerData: CodexProviderConfig = {
      id: provider?.id || (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()),
      name: providerName.trim(),
      createdAt: provider?.createdAt,
      configToml: configTomlJson.trim(),
      authJson: authJson.trim(),
      modelCatalog: parsedModelCatalog,
      customModels: parsedModelCatalog,
      messageEnvVars: messageEnvVars.filter(e => e.key.trim() !== ''),
      mcpEnvVars: mcpEnvVars.filter(e => e.key.trim() !== ''),
    };

    onSave(providerData);
    onClose();
  };

  // 引导步骤定义(标题走 i18n)
  const steps: GuidedStep[] = [
    { id: 'basic', title: t('settings.codexProvider.dialog.stepBasic', '基本信息') },
    { id: 'connection', title: t('settings.codexProvider.dialog.stepConnection', '接入与凭证') },
    { id: 'models', title: t('settings.codexProvider.dialog.stepModels', '模型与环境') },
  ];

  // 前进门禁:基本信息步要求 Provider Name 非空(对齐 handleSave 校验)
  const canProceed = currentStep === 0 ? providerName.trim().length > 0 : true;

  // 模型拉取按钮是否可用:需能从 config.toml 提取到 base_url
  const baseUrlForFetch = extractBaseUrlFromToml(configTomlJson);

  if (!isOpen) {
    return null;
  }

  return (
    <GuidedProviderDialog
      isOpen={isOpen}
      onClose={onClose}
      ariaLabel={isAdding ? t('settings.codexProvider.dialog.addTitle') : t('settings.codexProvider.dialog.editTitle', { name: provider?.name })}
      steps={steps}
      currentStep={currentStep}
      onStepChange={setCurrentStep}
      canProceed={canProceed}
      onFinish={handleSave}
      finishLabel={isAdding ? t('settings.provider.dialog.confirmAdd') : t('settings.provider.dialog.saveChanges')}
      size="lg"
    >
      {/* ===== Step 0:基本信息 ===== */}
      {currentStep === 0 && (
        <>
          <p className="dialog-desc">
            {isAdding
              ? t('settings.codexProvider.dialog.addDescription')
              : t('settings.codexProvider.dialog.editDescription')}
          </p>

          {isAdding && (
            <>
              <div className="notice-box notice-box--info">
                <span className="codicon codicon-shield" />
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
                      <ProviderModelIcon providerId="codex" size={16} colored />
                    </span>
                    {t('settings.codexProvider.dialog.officialPreset')}
                  </button>
                </div>
                <small className="form-hint">{t('settings.codexProvider.dialog.officialSectionHint')}</small>
              </div>
            </>
          )}

          {isAdding && (
            <div className="form-group">
              <label>{t('settings.provider.dialog.proxySectionTitle')}</label>
              <div className="preset-buttons" role="radiogroup" aria-label={t('settings.provider.dialog.proxySectionTitle')}>
                {CODEX_PROVIDER_PRESETS.map((preset) => (
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
              <small className="form-hint">{t('settings.codexProvider.dialog.presetHint')}</small>
            </div>
          )}

          {/* Provider Name */}
          <div className="form-group">
            <label htmlFor="providerName">
              {t('settings.codexProvider.dialog.providerName')}
              <span className="required">{t('settings.provider.dialog.required')}</span>
            </label>
            <input
              id="providerName"
              type="text"
              className="form-input"
              placeholder={t('settings.codexProvider.dialog.providerNamePlaceholder')}
              value={providerName}
              onChange={(e) => setProviderName(e.target.value)}
            />
          </div>
        </>
      )}

      {/* ===== Step 1:接入与凭证 ===== */}
      {currentStep === 1 && (
        <>
          {/* config.toml JSON */}
          <div className="form-group">
            <div style={FORM_HEADER_STYLE}>
              <label htmlFor="configTomlJson">
                config.toml {t('settings.codexProvider.dialog.configJson')}
                <span className="required">{t('settings.provider.dialog.required')}</span>
              </label>
              <button
                type="button"
                className="btn-small"
                onClick={handleFormatConfigJson}
                style={FORMAT_BUTTON_STYLE}
                title={t('settings.codexProvider.dialog.formatJson')}
              >
                <BracesIcon size={14} />
                {t('settings.codexProvider.dialog.formatJson')}
              </button>
            </div>
            <textarea
              id="configTomlJson"
              className="form-input code-input"
              value={configTomlJson}
              onChange={(e) => setConfigTomlJson(e.target.value)}
              rows={15}
              style={CODE_TEXTAREA_STYLE}
            />
            <small className="form-hint">{t('settings.codexProvider.dialog.configJsonHint')}</small>
          </div>

          {/* auth.json */}
          <div className="form-group">
            <div style={FORM_HEADER_STYLE}>
              <label htmlFor="authJson">
                auth.json {t('settings.codexProvider.dialog.authJsonLabel')}
              </label>
              <button
                type="button"
                className="btn-small"
                onClick={handleFormatAuthJson}
                style={FORMAT_BUTTON_STYLE}
                title={t('settings.codexProvider.dialog.formatJson')}
              >
                <BracesIcon size={14} />
                {t('settings.codexProvider.dialog.formatJson')}
              </button>
            </div>
            <textarea
              id="authJson"
              className="form-input code-input"
              value={authJson}
              onChange={(e) => setAuthJson(e.target.value)}
              rows={6}
              style={CODE_TEXTAREA_STYLE}
            />
            <small className="form-hint">{t('settings.codexProvider.dialog.authJsonHint')}</small>
          </div>
        </>
      )}

      {/* ===== Step 2:模型与环境 ===== */}
      {currentStep === 2 && (
        <>
          {/* 拉取真实模型列表(从 config.toml 的 base_url + auth.json 的 key) */}
          <div className="form-group">
            <label>{t('settings.codexProvider.dialog.fetchModelsTitle', '拉取可用模型')}</label>
            <small className="form-hint" style={{ marginBottom: '8px', display: 'block' }}>
              {t('settings.codexProvider.dialog.fetchModelsHint', '从上一步 config.toml 的 base_url 与 auth.json 的 key 拉取该服务支持的真实模型,点击即追加到下方 Model Catalog。')}
            </small>
            <div className="json-toolbar">
              <button
                type="button"
                className="format-btn"
                onClick={handleFetchModels}
                disabled={fetchingModels || !baseUrlForFetch}
              >
                <RefreshIcon size={14} />
                {fetchingModels
                  ? t('settings.codexProvider.dialog.fetchModelsLoading', '拉取中…')
                  : t('settings.codexProvider.dialog.fetchModelsButton', '拉取可用模型')}
              </button>
            </div>
            {fetchError && (
              <p className="json-error" style={{ marginTop: '8px' }}>
                {fetchError}
              </p>
            )}
            {fetchedModels.length > 0 && (
              <div style={{ marginTop: '8px' }}>
                <small className="form-hint" style={{ display: 'block', marginBottom: '4px' }}>
                  {t('settings.codexProvider.dialog.fetchModelsSuccess', { count: fetchedModels.length, defaultValue: '已拉取 {{count}} 个模型,点击追加到 Model Catalog' })}
                </small>
                <div style={FETCHED_LIST_STYLE}>
                  {fetchedModels.map((m, index) => (
                    <button
                      key={m}
                      type="button"
                      className="preset-btn"
                      style={{ ...FETCHED_CHIP_STYLE, animation: 'fadeIn 0.3s ease-out both', animationDelay: `${index * 50}ms` }}
                      onClick={() => handleAppendModel(m)}
                      title={m}
                    >
                      {m}
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>

          <div className="form-group">
            <label htmlFor="modelCatalogJson">
              {t('settings.codexProvider.dialog.modelCatalog', 'Model Catalog')}
            </label>
            <textarea
              id="modelCatalogJson"
              className="form-input code-input"
              value={modelCatalogJson}
              onChange={(e) => setModelCatalogJson(e.target.value)}
              rows={6}
              style={CODE_TEXTAREA_STYLE}
            />
            <small className="form-hint">
              {t('settings.codexProvider.dialog.modelCatalogHint', 'Optional JSON array. If empty, the selector uses the model from config.toml.')}
            </small>
          </div>

          {/* Environment Variables — JSON/表单双视图(分块切换,双向同步) */}
          <DualViewSwitcher
            label={t('settings.codexProvider.dialog.envVarsTitle')}
            formState={{ messageEnvVars, mcpEnvVars }}
            onFormStateChange={(next: CodexEnvFormState) => {
              setMessageEnvVars(next.messageEnvVars);
              setMcpEnvVars(next.mcpEnvVars);
            }}
            adapter={codexEnvAdapter}
            mode={envViewMode}
            onModeChange={setEnvViewMode}
            renderForm={(state: CodexEnvFormState, onChange: (s: CodexEnvFormState) => void) => (
              <>
                <div className="form-group" style={{ marginTop: '16px' }}>
                  <label>{t('settings.codexProvider.dialog.messageEnvLabel')}</label>
                  <small className="form-hint">{t('settings.codexProvider.dialog.messageEnvHint')}</small>
                  <EnvVarEditor
                    entries={state.messageEnvVars}
                    onChange={(next) => onChange({ messageEnvVars: next, mcpEnvVars: state.mcpEnvVars })}
                  />
                </div>
                <div className="form-group">
                  <label>{t('settings.codexProvider.dialog.mcpEnvLabel')}</label>
                  <small className="form-hint">{t('settings.codexProvider.dialog.mcpEnvHint')}</small>
                  <EnvVarEditor
                    entries={state.mcpEnvVars}
                    onChange={(next) => onChange({ messageEnvVars: state.messageEnvVars, mcpEnvVars: next })}
                  />
                </div>
              </>
            )}
          />
        </>
      )}
    </GuidedProviderDialog>
  );
}
