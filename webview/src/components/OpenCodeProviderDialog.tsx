import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { BracesIcon, RefreshIcon } from './Icons';
import type { OpenCodeProviderConfig } from '../types/provider';
import { GuidedProviderDialog, type GuidedStep } from './shared/GuidedProviderDialog';
import DualViewSwitcher, { type DualViewMode } from './shared/DualViewSwitcher';
import { openCodeAdvancedAdapter } from './shared/dualView/adapters';
import OpenCodeAdvancedForm, { extractAdvancedRaw } from './shared/OpenCodeAdvancedForm';
import { fetchProviderModels } from '../utils/bridge';

const FORM_HEADER_STYLE: React.CSSProperties = { display: 'flex', justifyContent: 'space-between', alignItems: 'center' };
const FORMAT_BUTTON_STYLE: React.CSSProperties = { padding: '4px 8px', fontSize: '12px' };
const CODE_TEXTAREA_STYLE: React.CSSProperties = {
  fontFamily: 'var(--idea-editor-font-family, monospace)',
  fontSize: '12px',
  lineHeight: '1.5',
};
const FETCHED_CHIP_STYLE: React.CSSProperties = { fontSize: '12px', padding: '2px 8px' };
const FETCHED_LIST_STYLE: React.CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: '4px', marginTop: '4px' };

interface OpenCodeProviderDialogProps {
  isOpen: boolean;
  provider?: OpenCodeProviderConfig | null;
  onClose: () => void;
  onSave: (provider: OpenCodeProviderConfig) => void;
  addToast: (message: string, type: 'success' | 'error' | 'info') => void;
}

/**
 * OpenCode provider 新增/编辑弹窗 —— 对称 {@link CodexProviderDialog}(Principle 6)。
 *
 * <p>三步引导流(复用 {@link GuidedProviderDialog} 骨架,对称 Claude {@link ProviderDialog}):
 * <ol>
 *   <li>基本信息:Provider Key(= id)+ Provider Name</li>
 *   <li>凭证:Base URL + API Key</li>
 *   <li>模型:models JSON 对象 + 从 baseURL/key 动态拉取真实模型列表(业务逻辑下沉后端)</li>
 * </ol>
 *
 * <p>字段语义:opencode 原生 provider 段 {@code {name, models:{...}, apiKey?, baseURL?}}。
 * Provider Key(= id)是 opencode.json 的 provider 段键,也是 actualModel 的 {@code providerKey/} 前缀,
 * 改动会破坏 actualModel 引用,故编辑模式下只读。
 */
export default function OpenCodeProviderDialog({
  isOpen,
  provider,
  onClose,
  onSave,
  addToast,
}: OpenCodeProviderDialogProps) {
  const { t } = useTranslation();
  const isAdding = !provider;

  const [providerKey, setProviderKey] = useState('');
  const [providerName, setProviderName] = useState('');
  const [baseURL, setBaseURL] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [modelsJson, setModelsJson] = useState('');
  // 高级/透传字段(opencode 原生 options/npm/command 等):JSON↔表单双视图,save 时原样透传
  const [advancedRaw, setAdvancedRaw] = useState<Record<string, any>>({});
  const [advancedViewMode, setAdvancedViewMode] = useState<DualViewMode>('form');
  // 引导步骤:0=基本信息 1=凭证 2=模型
  const [currentStep, setCurrentStep] = useState(0);
  // 从 baseURL+key 动态拉取的真实模型列表(Phase 2 后端能力的前端入口)
  const [fetchedModels, setFetchedModels] = useState<string[]>([]);
  const [fetchingModels, setFetchingModels] = useState(false);
  const [fetchError, setFetchError] = useState('');

  // Initialize form
  useEffect(() => {
    if (isOpen) {
      if (provider) {
        // Edit mode - load existing data
        setProviderKey(provider.id || '');
        setProviderName(provider.name || '');
        setBaseURL(provider.baseURL || provider.apiBase || '');
        setApiKey(provider.apiKey || '');
        setModelsJson(provider.models ? JSON.stringify(provider.models, null, 2) : '{}');
        // 回灌 opencode 原生透传字段(剥离已知业务字段),避免编辑丢失
        setAdvancedRaw(extractAdvancedRaw(provider));
      } else {
        // Add mode - reset with default template
        setProviderKey('');
        setProviderName('');
        setBaseURL('');
        setApiKey('');
        setAdvancedRaw({});
        setModelsJson(`{
  "glm-5.2": {
    "name": "GLM 5.2",
    "limit": {
      "context": 128000,
      "output": 8192
    }
  }
}`);
      }
      setCurrentStep(0);
      setFetchedModels([]);
      setFetchingModels(false);
      setFetchError('');
    }
  }, [isOpen, provider]);

  // Format JSON
  const handleFormatModelsJson = () => {
    try {
      const parsed = JSON.parse(modelsJson || '{}');
      setModelsJson(JSON.stringify(parsed, null, 2));
      addToast(t('settings.openCodeProvider.dialog.formatSuccess'), 'success');
    } catch (e) {
      addToast(t('settings.openCodeProvider.dialog.formatError'), 'error');
    }
  };

  // 从 baseURL+key 动态拉取真实模型列表。业务逻辑下沉后端 ModelFetchService,前端只做入口。
  const handleFetchModels = async () => {
    if (!baseURL.trim() || fetchingModels) return;
    setFetchingModels(true);
    setFetchError('');
    try {
      const result = await fetchProviderModels({
        baseUrl: baseURL.trim(),
        apiKey: apiKey.trim() || undefined,
      });
      if (result.error) {
        setFetchError(result.error);
        setFetchedModels([]);
      } else if (result.models && result.models.length > 0) {
        setFetchedModels(result.models);
      } else {
        setFetchError(t('settings.openCodeProvider.dialog.fetchModelsEmpty', '未返回任何模型'));
      }
    } catch {
      setFetchError(t('settings.openCodeProvider.dialog.fetchModelsFailed', '拉取失败,请手动填写'));
    } finally {
      setFetchingModels(false);
    }
  };

  // 点击拉取到的 model id,追加骨架条目到 modelsJson(已存在则跳过,非法 JSON 则忽略)
  const handleAppendModel = (modelId: string) => {
    try {
      const parsed = modelsJson.trim() ? JSON.parse(modelsJson) : {};
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && !parsed[modelId]) {
        parsed[modelId] = { name: modelId };
        setModelsJson(JSON.stringify(parsed, null, 2));
      }
    } catch {
      // modelsJson 当前非法,不追加以免破坏用户正在编辑的内容
    }
  };

  const handleSave = () => {
    if (!providerKey.trim()) {
      addToast(t('settings.openCodeProvider.dialog.keyRequired'), 'error');
      return;
    }
    if (!providerName.trim()) {
      addToast(t('settings.openCodeProvider.dialog.nameRequired'), 'error');
      return;
    }

    // Validate models JSON format (must be valid JSON object)
    let parsedModels: Record<string, any> = {};
    if (modelsJson.trim()) {
      try {
        const parsed = JSON.parse(modelsJson);
        if (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null) {
          addToast(t('settings.openCodeProvider.dialog.modelsJsonError'), 'error');
          return;
        }
        parsedModels = parsed;
      } catch (e) {
        addToast(t('settings.openCodeProvider.dialog.modelsJsonError'), 'error');
        return;
      }
    }

    const providerData: OpenCodeProviderConfig = {
      id: providerKey.trim(),
      name: providerName.trim(),
      apiKey: apiKey || undefined,
      baseURL: baseURL.trim() || undefined,
      models: parsedModels,
      // 透传 opencode 原生高级字段(options/npm/command 等),不截断
      ...advancedRaw,
      // 编辑模式保留原 createdAt/isActive(后端按 id 合并,这里只传递业务字段)
      ...(provider?.createdAt !== undefined ? { createdAt: provider.createdAt } : {}),
    };

    onSave(providerData);
    onClose();
  };

  // 引导步骤定义(标题走 i18n)
  const steps: GuidedStep[] = [
    { id: 'basic', title: t('settings.openCodeProvider.dialog.stepBasic', '基本信息') },
    { id: 'credentials', title: t('settings.openCodeProvider.dialog.stepCredentials', '凭证') },
    { id: 'models', title: t('settings.openCodeProvider.dialog.stepModels', '模型') },
  ];

  // 前进门禁:基本信息步要求 Provider Key + Name 均非空(对齐 handleSave 校验)
  const canProceed = currentStep === 0
    ? providerKey.trim().length > 0 && providerName.trim().length > 0
    : true;

  if (!isOpen) {
    return null;
  }

  return (
    <GuidedProviderDialog
      isOpen={isOpen}
      onClose={onClose}
      ariaLabel={isAdding ? t('settings.openCodeProvider.dialog.addTitle') : t('settings.openCodeProvider.dialog.editTitle', { name: provider?.name })}
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
              ? t('settings.openCodeProvider.dialog.addDescription')
              : t('settings.openCodeProvider.dialog.editDescription')}
          </p>

          {/* Provider Key (= opencode.json provider segment key) */}
          <div className="form-group">
            <label htmlFor="providerKey">
              {t('settings.openCodeProvider.dialog.providerKey')}
              <span className="required">{t('settings.provider.dialog.required')}</span>
            </label>
            <input
              id="providerKey"
              type="text"
              className="form-input"
              placeholder={t('settings.openCodeProvider.dialog.providerKeyPlaceholder')}
              value={providerKey}
              onChange={(e) => setProviderKey(e.target.value)}
              disabled={!isAdding}
            />
            <small className="form-hint">{t('settings.openCodeProvider.dialog.providerKeyHint')}</small>
          </div>

          {/* Provider Name */}
          <div className="form-group">
            <label htmlFor="providerName">
              {t('settings.openCodeProvider.dialog.providerName')}
              <span className="required">{t('settings.provider.dialog.required')}</span>
            </label>
            <input
              id="providerName"
              type="text"
              className="form-input"
              placeholder={t('settings.openCodeProvider.dialog.providerNamePlaceholder')}
              value={providerName}
              onChange={(e) => setProviderName(e.target.value)}
            />
          </div>
        </>
      )}

      {/* ===== Step 1:凭证 ===== */}
      {currentStep === 1 && (
        <>
          {/* Base URL */}
          <div className="form-group">
            <label htmlFor="baseURL">{t('settings.openCodeProvider.dialog.baseURL')}</label>
            <input
              id="baseURL"
              type="text"
              className="form-input"
              placeholder={t('settings.openCodeProvider.dialog.baseURLPlaceholder')}
              value={baseURL}
              onChange={(e) => setBaseURL(e.target.value)}
            />
          </div>

          {/* API Key */}
          <div className="form-group">
            <label htmlFor="apiKey">{t('settings.openCodeProvider.dialog.apiKey')}</label>
            <input
              id="apiKey"
              type="password"
              className="form-input"
              placeholder={t('settings.openCodeProvider.dialog.apiKeyPlaceholder')}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
            <small className="form-hint">{t('settings.openCodeProvider.dialog.apiKeyHint')}</small>
          </div>
        </>
      )}

      {/* ===== Step 2:模型 ===== */}
      {currentStep === 2 && (
        <>
          {/* 拉取真实模型列表 */}
          <div className="form-group">
            <label>{t('settings.openCodeProvider.dialog.fetchModelsTitle', '拉取可用模型')}</label>
            <small className="form-hint" style={{ marginBottom: '8px', display: 'block' }}>
              {t('settings.openCodeProvider.dialog.fetchModelsHint', '填入上方 Base URL 与 API Key 后,可自动拉取该服务支持的真实模型,点击即追加到下方 JSON。')}
            </small>
            <div className="json-toolbar">
              <button
                type="button"
                className="format-btn"
                onClick={handleFetchModels}
                disabled={fetchingModels || !baseURL.trim()}
              >
                <RefreshIcon size={14} />
                {fetchingModels
                  ? t('settings.openCodeProvider.dialog.fetchModelsLoading', '拉取中…')
                  : t('settings.openCodeProvider.dialog.fetchModelsButton', '拉取可用模型')}
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
                  {t('settings.openCodeProvider.dialog.fetchModelsSuccess', { count: fetchedModels.length, defaultValue: '已拉取 {{count}} 个模型,点击追加到 JSON' })}
                </small>
                <div style={FETCHED_LIST_STYLE}>
                  {fetchedModels.map((m) => (
                    <button
                      key={m}
                      type="button"
                      className="preset-btn"
                      style={FETCHED_CHIP_STYLE}
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

          {/* Models (JSON object) */}
          <div className="form-group">
            <div style={FORM_HEADER_STYLE}>
              <label htmlFor="modelsJson">
                {t('settings.openCodeProvider.dialog.models')}
              </label>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={handleFormatModelsJson}
                style={FORMAT_BUTTON_STYLE}
              >
                <BracesIcon size={14} />
                {t('settings.openCodeProvider.dialog.formatJson')}
              </button>
            </div>
            <textarea
              id="modelsJson"
              className="form-input code-input"
              value={modelsJson}
              onChange={(e) => setModelsJson(e.target.value)}
              rows={10}
              style={CODE_TEXTAREA_STYLE}
            />
            <small className="form-hint">{t('settings.openCodeProvider.dialog.modelsHint')}</small>
          </div>

          {/* 高级/透传字段 — JSON/表单双视图(opencode 原生 options/npm/command 等) */}
          <DualViewSwitcher
            label={t('settings.openCodeProvider.dialog.advancedTitle', '高级 / 透传字段')}
            formState={{ raw: advancedRaw }}
            onFormStateChange={(next) => setAdvancedRaw(next.raw)}
            adapter={openCodeAdvancedAdapter}
            mode={advancedViewMode}
            onModeChange={setAdvancedViewMode}
            jsonHint={t('settings.openCodeProvider.dialog.advancedJsonHint', '含 opencode 原生透传字段')}
            renderForm={(state, onChange) => (
              <OpenCodeAdvancedForm state={state} onChange={onChange} />
            )}
          />
        </>
      )}
    </GuidedProviderDialog>
  );
}
