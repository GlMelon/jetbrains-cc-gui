import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { BaseDialog, DialogHeader, DialogBody, DialogFooter } from '../../shared/BaseDialog';
import { Switch } from '../../shared/Switch';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import { EditIcon, PlusIcon } from '../../Icons';
import { ClickSpark } from '../../react-bits';
import type { ModelRegistryItem } from '../../../utils/modelRegistry';
import { DEFAULT_CONTEXT_WINDOW, ONE_MILLION_CONTEXT_WINDOW } from '../../../components/ChatInputBox/types';

// 与 ModelRegistrySection 内 EMPTY_MODEL 保持同构的弹窗默认表单(解耦,各自维护)
type Provider = 'claude' | 'codex' | 'opencode' | 'grok' | 'kimi' | 'pi';
type Role = NonNullable<ModelRegistryItem['role']>;

const PROVIDERS: Provider[] = ['claude', 'codex', 'opencode', 'grok', 'kimi', 'pi'];
const ROLES: Role[] = ['sonnet', 'opus', 'fable', 'haiku'];

const EMPTY_FORM: ModelRegistryItem = {
  provider: 'claude',
  id: '',
  role: 'sonnet',
  label: '',
  actualModel: '',
  description: '',
  contextWindow: DEFAULT_CONTEXT_WINDOW,
  supports1MContext: false,
  enabled: true,
  readOnly: false,
};

interface ModelEditDialogProps {
  isOpen: boolean;
  editing: ModelRegistryItem | null;
  editingOriginalKey: string | null;
  onClose: () => void;
  onSubmit: (normalized: ModelRegistryItem, originalKey: string | null) => void;
}

/**
 * 方案 C:provider 分段控件 + 实时预览的新增/编辑模型弹窗。
 * 职责边界:本组件只负责表单状态与规范化(按下拉同样的 id/actualModel 规则),
 * 去重判定与持久化交给父组件(需要完整 registry),通过 onSubmit 回传。
 */
export default function ModelEditDialog({ isOpen, editing, editingOriginalKey, onClose, onSubmit }: ModelEditDialogProps) {
  const { t } = useTranslation();
  const isEditing = editingOriginalKey !== null;
  const [form, setForm] = useState<ModelRegistryItem>(EMPTY_FORM);

  // 打开/编辑目标变化时回填(取消态 isOpen=false 不回填,保留旧值无副作用)
  useEffect(() => {
    if (isOpen) {
      setForm(editing ? { ...editing } : EMPTY_FORM);
    }
  }, [isOpen, editing]);

  const provider = form.provider;
  const useClaudeFormat = provider === 'claude' || provider === 'opencode';
  const primaryKey = useClaudeFormat ? (form.actualModel ?? '').trim() : form.id.trim();
  const canSubmit = primaryKey.length > 0;

  const update = (patch: Partial<ModelRegistryItem>) => setForm((prev) => ({ ...prev, ...patch }));

  const handleSubmit = () => {
    if (!canSubmit) return;
    const actualModel = useClaudeFormat ? (form.actualModel ?? '').trim() : '';
    // FIX: When editing, preserve the original id to avoid key change that triggers
    // "delete old + add new" treatment and causes selectedModel to silently revert.
    // For new models, use actualModel as id for claude format.
    const id = isEditing && editingOriginalKey
      ? editingOriginalKey.split(':')[1] || actualModel
      : useClaudeFormat ? actualModel : form.id.trim();
    const normalized: ModelRegistryItem = {
      ...form,
      id,
      role: provider === 'claude' ? (form.role ?? 'sonnet') : undefined,
      actualModel: useClaudeFormat ? actualModel : undefined,
      label: form.label.trim() || actualModel || id,
      description: form.description?.trim() || undefined,
      contextWindow: Number(form.contextWindow),
      enabled: form.enabled !== false,
    };
    onSubmit(normalized, editingOriginalKey);
  };

  const previewId = form.label.trim() || (useClaudeFormat ? (form.actualModel ?? '').trim() : form.id.trim());
  const titleKey = isEditing ? 'settings.models.dialog.editTitle' : 'settings.models.dialog.addTitle';
  const confirmKey = isEditing ? 'settings.models.dialog.saveChanges' : 'settings.models.dialog.confirmAdd';
  const titleText = t(titleKey, isEditing ? '编辑模型' : '新增模型');

  return (
    <BaseDialog isOpen={isOpen} onClose={onClose} className="model-edit-dialog-wrapper" ariaLabel={titleText} animation="pop">
      <DialogHeader title={titleText} icon={isEditing ? <EditIcon size={16} /> : <PlusIcon size={16} />} onClose={onClose} />
      <DialogBody>
        <div className="form-group">
          <label>{t('settings.models.dialog.provider', '供应商')}</label>
          <div className="model-segmented">
            {PROVIDERS.map((p) => (
              <button
                key={p}
                type="button"
                className={`seg ${provider === p ? 'active' : ''}`}
                onClick={() => update({ provider: p })}
              >
                <ProviderModelIcon providerId={p} size={14} colored />
                <span>{t(`providers.${p}.label`, p)}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="model-edit-fields">
          {provider === 'claude' && (
            <div className="form-group">
              <label>{t('settings.models.dialog.role', '角色')}</label>
              <select
                className="form-input"
                value={form.role ?? 'sonnet'}
                onChange={(e) => update({ role: e.target.value as Role })}
              >
                {ROLES.map((r) => (
                  <option key={r} value={r}>{r.charAt(0).toUpperCase() + r.slice(1)}</option>
                ))}
              </select>
            </div>
          )}

          {provider !== 'claude' && (
            <div className="form-group">
              <label>
                {t('settings.models.dialog.modelId', '模型 ID')}
                <span style={{ color: 'var(--error)', marginLeft: 4 }}>*</span>
              </label>
              <input
                className="form-input"
                placeholder={t('settings.models.dialog.modelIdPlaceholder', '例如 gpt-5.5')}
                value={form.id}
                onChange={(e) => update({ id: e.target.value })}
              />
              <small className="form-hint">{t('settings.models.dialog.modelIdHint', '该供应商下唯一的模型标识')}</small>
            </div>
          )}

          <div className="form-group">
            <label>{t('settings.models.dialog.label', '显示名称')}</label>
            <input
              className="form-input"
              placeholder={t('settings.models.dialog.labelPlaceholder', '为空时使用实际请求模型')}
              value={form.label}
              onChange={(e) => update({ label: e.target.value })}
            />
          </div>

          {useClaudeFormat && (
            <div className="form-group">
              <label>
                {t('settings.models.dialog.actualModel', '实际请求模型')}
                <span style={{ color: 'var(--error)', marginLeft: 4 }}>*</span>
              </label>
              <input
                className="form-input"
                placeholder={t('settings.models.dialog.actualModelPlaceholder', '例如 claude-sonnet-4-6')}
                value={form.actualModel ?? ''}
                onChange={(e) => update({ actualModel: e.target.value })}
              />
              <small className="form-hint">{t('settings.models.dialog.actualModelHint', '同时作为模型唯一标识 (id) 与发往上游的模型名')}</small>
            </div>
          )}

          <div className="form-group full">
            <label>{t('settings.models.dialog.description', '描述')}</label>
            <input
              className="form-input"
              placeholder={t('settings.models.dialog.descriptionPlaceholder', '一句话备注')}
              value={form.description ?? ''}
              onChange={(e) => update({ description: e.target.value })}
            />
          </div>
        </div>

        <div className="model-preview-card">
          <div className="preview-label">{t('settings.models.dialog.previewLabel', '实时预览 · 聊天下拉显示效果')}</div>
          <span className="preview-chip">
            <ProviderModelIcon providerId={provider} modelId={(form.actualModel || form.id) || undefined} size={14} colored />
            <span className={`preview-id ${previewId ? '' : 'empty'}`}>
              {previewId || t('settings.models.dialog.previewEmpty', '(未填写)')}
            </span>
            <span className="preview-provider">{t(`providers.${provider}.label`, provider)}</span>
            {form.supports1MContext && <span className="preview-badge badge-1m">1M</span>}
            <span className={`preview-badge ${form.enabled !== false ? 'badge-enabled' : 'badge-disabled'}`}>
              {form.enabled !== false
                ? t('settings.models.enabledStatus', '已启用')
                : t('settings.models.disabled', '已禁用')}
            </span>
          </span>
        </div>

        <div className="model-switches">
          <div className="model-switch-line">
            <Switch checked={form.enabled !== false} onChange={(c: boolean) => update({ enabled: c })} />
            <div className="switch-text">
              <span className="switch-title">{t('settings.models.dialog.enabled', '启用')}</span>
              <span className="switch-hint">{t('settings.models.dialog.enabledHint', '关闭后不在聊天下拉中显示')}</span>
            </div>
          </div>
          <div className="model-switch-line">
            <Switch
              checked={form.supports1MContext === true}
              onChange={(c: boolean) => update({
                supports1MContext: c,
                contextWindow: c ? ONE_MILLION_CONTEXT_WINDOW : DEFAULT_CONTEXT_WINDOW,
              })}
            />
            <div className="switch-text">
              <span className="switch-title">{t('settings.models.dialog.supports1M', '支持 1M 上下文')}</span>
              <span className="switch-hint">{t('settings.models.dialog.supports1MHint', '开启后上下文窗口设为 1M,关闭则为 200k')}</span>
            </div>
          </div>
        </div>
      </DialogBody>
      <DialogFooter>
        <button className="btn btn-secondary" onClick={onClose}>
          {t('common.cancel', '取消')}
        </button>
        <ClickSpark>
          <button className="btn btn-primary" onClick={handleSubmit} disabled={!canSubmit}>
            {t(confirmKey, isEditing ? '保存修改' : '确定')}
          </button>
        </ClickSpark>
      </DialogFooter>
    </BaseDialog>
  );
}
