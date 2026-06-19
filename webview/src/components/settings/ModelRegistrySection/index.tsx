import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { bridgeHub } from '../../../bridge';
import { sendBridgeEvent } from '../../../utils/bridge';
import type { ModelRegistryItem, ModelRegistryPayload } from '../../../utils/modelRegistry';
import { getModelRegistrySnapshot, parseModelRegistryPayload, requestModelRegistry } from '../../../utils/modelRegistry';
import styles from './style.module.less';

interface ModelRegistrySectionProps {
  addToast: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
}

const EMPTY_MODEL: ModelRegistryItem = {
  provider: 'claude',
  id: '',
  role: 'sonnet',
  label: '',
  actualModel: '',
  description: '',
  contextWindow: 200_000,
  supports1MContext: false,
  enabled: true,
};

export default function ModelRegistrySection({ addToast }: ModelRegistrySectionProps) {
  const { t } = useTranslation();
  const [registry, setRegistry] = useState<ModelRegistryPayload>(() => getModelRegistrySnapshot());
  const [providerFilter, setProviderFilter] = useState<'all' | 'claude' | 'codex'>('all');
  const [editing, setEditing] = useState<ModelRegistryItem | null>(null);
  const [editingOriginalKey, setEditingOriginalKey] = useState<string | null>(null);

  useEffect(() => {
    requestModelRegistry();
    const unsubscribeRegistry = bridgeHub.subscribe('model_registry', (json) => {
      const parsed = parseModelRegistryPayload(json);
      if (parsed) {
        setRegistry(parsed);
      }
    });
    const unsubscribeUpdated = bridgeHub.subscribe('model_registry_updated', (json) => {
      try {
        const data = JSON.parse(json as string) as {
          success?: boolean;
          registry?: ModelRegistryPayload;
          errors?: string[];
        };
        if (data.success) {
          const parsed = parseModelRegistryPayload(data.registry);
          if (parsed) {
            setRegistry(parsed);
          }
          addToast(t('settings.models.saveSuccess', 'Model configuration saved'), 'success');
        } else {
          addToast((data.errors || []).join('\n') || t('settings.models.saveFailed', 'Model configuration rejected'), 'error');
        }
      } catch {
        addToast(t('settings.models.saveFailed', 'Model configuration rejected'), 'error');
      }
    });
    return () => {
      unsubscribeRegistry();
      unsubscribeUpdated();
    };
  }, [addToast, t]);

  const visibleModels = useMemo(() => {
    if (providerFilter === 'all') {
      return registry.items;
    }
    return registry.items.filter((model) => model.provider === providerFilter);
  }, [providerFilter, registry.items]);

  const startAdd = useCallback((provider: 'claude' | 'codex' = 'claude') => {
    setEditing(provider === 'claude'
      ? { ...EMPTY_MODEL, provider, id: '', role: 'sonnet', actualModel: '' }
      : { ...EMPTY_MODEL, provider, id: '', role: undefined, actualModel: '' });
    setEditingOriginalKey(null);
  }, []);

  const startEdit = useCallback((model: ModelRegistryItem) => {
    setEditing({ ...model });
    setEditingOriginalKey(toKey(model));
  }, []);

  const persistRegistry = useCallback((nextRegistry: ModelRegistryPayload) => {
    const userOnly = { items: nextRegistry.items.filter((item) => !item.readOnly) };
    setRegistry(nextRegistry);
    sendBridgeEvent('set_model_registry', JSON.stringify(userOnly));
  }, []);

  const removeModel = useCallback((model: ModelRegistryItem) => {
    const nextRegistry = { items: registry.items.filter((item) => toKey(item) !== toKey(model)) };
    persistRegistry(nextRegistry);
  }, [persistRegistry, registry.items]);

  const saveEditing = useCallback(() => {
    if (!editing) {
      return;
    }
    const isClaude = editing.provider === 'claude';
    const role = isClaude ? (editing.role ?? 'sonnet') : undefined;
    const actualModel = isClaude ? (editing.actualModel ?? '').trim() : '';
    const id = isClaude ? actualModel : editing.id.trim();
    if (isClaude && !actualModel) {
      addToast(t('settings.models.actualModelRequired', 'Actual request model is required'), 'error');
      return;
    }
    const normalized: ModelRegistryItem = {
      ...editing,
      id,
      role,
      actualModel: isClaude ? actualModel : undefined,
      label: editing.label.trim() || actualModel || id,
      description: editing.description?.trim() || undefined,
      contextWindow: Number(editing.contextWindow),
      enabled: editing.enabled !== false,
    };

    const normalizedKey = toKey(normalized);

    const duplicateExists = registry.items.some(
      (item) => toKey(item) === normalizedKey && toKey(item) !== editingOriginalKey
    );

    if (duplicateExists) {
      addToast(t('settings.models.duplicateModel', 'A model with this ID already exists'), 'error');
      return;
    }

    const withoutOld = editingOriginalKey
      ? registry.items.filter((item) => toKey(item) !== editingOriginalKey)
      : registry.items;
    persistRegistry({ items: [normalized, ...withoutOld] });
    setEditing(null);
    setEditingOriginalKey(null);
  }, [editing, editingOriginalKey, persistRegistry, registry.items, addToast, t]);

  const reset = useCallback(() => {
    sendBridgeEvent('reset_model_registry');
  }, []);

  return (
    <section className={styles.section}>
      <div className={styles.header}>
        <div>
          <h3 className={styles.title}>{t('settings.models.title', 'Models')}</h3>
          <p className={styles.description}>
            {t('settings.models.description', 'Manage Claude and Codex model IDs, context windows, and 1M support.')}
          </p>
        </div>
        <div className={styles.actions}>
          <button className="btn btn-secondary btn-sm" onClick={() => startAdd(providerFilter === 'codex' ? 'codex' : 'claude')}>
            <span className="codicon codicon-add" aria-hidden="true" />
            {t('common.add', 'Add')}
          </button>
          <button className="btn btn-secondary btn-sm" onClick={reset}>
            {t('common.reset', 'Reset')}
          </button>
        </div>
      </div>

      <div className={styles.filters}>
        {(['all', 'claude', 'codex'] as const).map((provider) => (
          <button
            key={provider}
            className={`${styles.filterButton} ${providerFilter === provider ? styles.active : ''}`}
            onClick={() => setProviderFilter(provider)}
          >
            {provider === 'all' ? t('common.all', 'All') : provider}
          </button>
        ))}
      </div>

      {editing && (
        <div className={styles.editor}>
          <select
            className={`form-input ${styles.providerSelect}`}
            value={editing.provider}
            onChange={(event) => setEditing({ ...editing, provider: event.target.value as 'claude' | 'codex' })}
          >
            <option value="claude">claude</option>
            <option value="codex">codex</option>
          </select>
          {editing.provider === 'claude' && (
            <select
              className="form-input"
              value={editing.role ?? 'sonnet'}
              onChange={(event) => {
                const role = event.target.value as NonNullable<ModelRegistryItem['role']>;
                setEditing({ ...editing, role });
              }}
            >
              <option value="sonnet">Sonnet</option>
              <option value="opus">Opus</option>
              <option value="fable">Fable</option>
              <option value="haiku">Haiku</option>
            </select>
          )}
          {editing.provider === 'codex' && (
            <input
              className="form-input"
              placeholder="model id"
              value={editing.id}
              onChange={(event) => setEditing({ ...editing, id: event.target.value })}
            />
          )}
          <input
            className="form-input"
            placeholder="label"
            value={editing.label}
            onChange={(event) => setEditing({ ...editing, label: event.target.value })}
          />
          {editing.provider === 'claude' && (
            <input
              className="form-input"
              placeholder="actual request model"
              value={editing.actualModel ?? ''}
              onChange={(event) => setEditing({ ...editing, actualModel: event.target.value })}
            />
          )}
          <input
            className="form-input"
            placeholder="description"
            value={editing.description ?? ''}
            onChange={(event) => setEditing({ ...editing, description: event.target.value })}
          />
          <label className={styles.checkboxLabel}>
            <input
              type="checkbox"
              checked={editing.supports1MContext === true}
              onChange={(event) => setEditing({
                ...editing,
                supports1MContext: event.target.checked,
                // 如果启用1M，自动设置上下文窗口为1M；否则重置为默认200k
                contextWindow: event.target.checked ? 1_000_000 : 200_000,
              })}
            />
            {t('settings.models.supports1M', 'Supports 1M')}
          </label>
          <label className={styles.checkboxLabel}>
            <input
              type="checkbox"
              checked={editing.enabled !== false}
              onChange={(event) => setEditing({ ...editing, enabled: event.target.checked })}
            />
            {t('settings.models.enabled', 'Enabled')}
          </label>
          <div className={styles.editorActions}>
            <button className="btn btn-secondary btn-sm" onClick={() => setEditing(null)}>
              {t('common.cancel', 'Cancel')}
            </button>
            <button
              className="btn btn-primary btn-sm"
              onClick={saveEditing}
              disabled={editing.provider === 'claude' ? !editing.actualModel?.trim() : !editing.id.trim()}
            >
              {t('common.confirm', 'Confirm')}
            </button>
          </div>
        </div>
      )}

      <div className={styles.table}>
        {visibleModels.map((model) => (
          <div key={toKey(model)} className={styles.row}>
            <div className={styles.mainCell}>
              <span className={styles.modelId}>{model.id}</span>
              <span className={styles.provider}>{model.provider}</span>
              {model.enabled === false && <span className={styles.disabled}>{t('settings.models.disabled', 'Disabled')}</span>}
              {model.enabled !== false && <span className={styles.enabled}>{t('settings.models.enabledStatus', 'Enabled')}</span>}
              <div className={styles.modelLabel}>{model.label}</div>
              {model.description && <div className={styles.modelDescription}>{model.description}</div>}
            </div>
            <div className={styles.metaCell}>
              <span>{formatContext(model.contextWindow ?? 200_000)}</span>
              {model.supports1MContext && <span>1M</span>}
            </div>
            <div className={styles.rowActions}>
              {model.readOnly ? (
                <span className={`${styles.iconButton} codicon codicon-lock`}
                      role="img"
                      aria-label={t('settings.models.readonly', 'Read-only')}
                      title={t('settings.models.readonly', 'Read-only')} />
              ) : (
                <>
                  <button className={styles.iconButton} onClick={() => startEdit(model)} title={t('common.edit', 'Edit')}>
                    <span className="codicon codicon-edit" aria-hidden="true" />
                  </button>
                  <button className={styles.iconButtonDanger} onClick={() => removeModel(model)} title={t('common.delete', 'Delete')}>
                    <span className="codicon codicon-trash" aria-hidden="true" />
                  </button>
                </>
              )}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function toKey(model: ModelRegistryItem): string {
  return `${model.provider}:${model.id}`;
}

function formatContext(tokens?: number): string {
  const value = tokens ?? 200_000;
  if (value >= 1_000_000) {
    return `${value / 1_000_000}M`;
  }
  return `${Math.round(value / 1_000)}K`;
}
