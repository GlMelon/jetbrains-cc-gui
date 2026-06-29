import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { BracesIcon, SaveIcon, CloseIcon } from './Icons';
import type { OpenCodeProviderConfig } from '../types/provider';
import { BaseDialog } from './shared/BaseDialog';

const FORM_HEADER_STYLE: React.CSSProperties = { display: 'flex', justifyContent: 'space-between', alignItems: 'center' };
const FORMAT_BUTTON_STYLE: React.CSSProperties = { padding: '4px 8px', fontSize: '12px' };
const CODE_TEXTAREA_STYLE: React.CSSProperties = {
  fontFamily: 'var(--idea-editor-font-family, monospace)',
  fontSize: '12px',
  lineHeight: '1.5',
};
const FOOTER_ACTIONS_STYLE: React.CSSProperties = { marginLeft: 'auto' };

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
      } else {
        // Add mode - reset with default template
        setProviderKey('');
        setProviderName('');
        setBaseURL('');
        setApiKey('');
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
      // 编辑模式保留原 createdAt/isActive(后端按 id 合并,这里只传递业务字段)
      ...(provider?.createdAt !== undefined ? { createdAt: provider.createdAt } : {}),
    };

    onSave(providerData);
    onClose();
  };

  if (!isOpen) {
    return null;
  }

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} size="lg" ariaLabel={isAdding ? t('settings.openCodeProvider.dialog.addTitle') : t('settings.openCodeProvider.dialog.editTitle')}>
      <div className="dialog provider-dialog opencode-provider-dialog">
        <div className="dialog-header">
          <h3>
            {isAdding
              ? t('settings.openCodeProvider.dialog.addTitle')
              : t('settings.openCodeProvider.dialog.editTitle', { name: provider?.name })}
          </h3>
          <button className="close-btn" onClick={onClose}>
            <CloseIcon size={16} />
          </button>
        </div>

        <div className="dialog-body">
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

        </div>

        <div className="dialog-footer">
          <div className="footer-actions" style={FOOTER_ACTIONS_STYLE}>
            <button className="btn btn-secondary" onClick={onClose}>
              <CloseIcon size={16} />
              {t('common.cancel')}
            </button>
            <button className="btn btn-primary" onClick={handleSave} disabled={!providerKey.trim() || !providerName.trim()}>
              <SaveIcon size={16} />
              {isAdding ? t('settings.provider.dialog.confirmAdd') : t('settings.provider.dialog.saveChanges')}
            </button>
          </div>
        </div>
      </div>
    </BaseDialog>
  );
}
