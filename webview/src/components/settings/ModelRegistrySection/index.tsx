import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DOWNSTREAM, UPSTREAM } from '../../../generated/protocol';
import { sendAction, subscribeEvent } from '../../../bridge/typed';
import type { ModelRegistryItem, ModelRegistryPayload } from '../../../utils/modelRegistry';
import { getModelRegistrySnapshot, parseModelRegistryPayload, requestModelRegistry } from '../../../utils/modelRegistry';
import { DEFAULT_CONTEXT_WINDOW } from '../../../components/ChatInputBox/types';
import { formatCapacity } from '../../../utils/formatNumber';
import { ProviderModelIcon } from '../../../components/shared/ProviderModelIcon';
import styles from './style.module.less';
import { EditIcon, LockIcon, PlusIcon, TrashIcon } from '../../Icons';
import ModelEditDialog from './ModelEditDialog';

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
  contextWindow: DEFAULT_CONTEXT_WINDOW,
  supports1MContext: false,
  enabled: true,
  readOnly: false,
};

export default function ModelRegistrySection({ addToast }: ModelRegistrySectionProps) {
  const { t } = useTranslation();
  const [registry, setRegistry] = useState<ModelRegistryPayload>(() => getModelRegistrySnapshot());
  const [providerFilter, setProviderFilter] = useState<'all' | 'claude' | 'codex' | 'opencode'>('all');
  const [editing, setEditing] = useState<ModelRegistryItem | null>(null);
  const [editingOriginalKey, setEditingOriginalKey] = useState<string | null>(null);

  useEffect(() => {
    requestModelRegistry();
    const unsubscribeRegistry = subscribeEvent(DOWNSTREAM.MODEL_REGISTRY, (json) => {
      const parsed = parseModelRegistryPayload(json);
      if (parsed) {
        setRegistry(parsed);
      }
    });
    const unsubscribeUpdated = subscribeEvent(DOWNSTREAM.MODEL_REGISTRY_UPDATED, (json) => {
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
          // FIX: On failure, request fresh registry from backend to roll back optimistic update
          // This ensures SettingsPanel and ChatScreen show consistent data
          requestModelRegistry();
          addToast((data.errors || []).join('\n') || t('settings.models.saveFailed', 'Model configuration rejected'), 'error');
        }
      } catch {
        // FIX: On parse failure, also request fresh registry to roll back
        requestModelRegistry();
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

  const startAdd = useCallback((provider: 'claude' | 'codex' | 'opencode' = 'claude') => {
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
    // Optimistically update local state
    setRegistry(nextRegistry);
    sendAction(UPSTREAM.SET_MODEL_REGISTRY, userOnly);
    // Note: Rollback on failure is handled by MODEL_REGISTRY_UPDATED event handler
    // which will set the registry to the backend's authoritative value on failure
  }, [registry]);

  const removeModel = useCallback((model: ModelRegistryItem) => {
    const nextRegistry = { items: registry.items.filter((item) => toKey(item) !== toKey(model)) };
    persistRegistry(nextRegistry);
  }, [persistRegistry, registry.items]);

  // 规范化已下沉到 ModelEditDialog,这里只负责去重判定与持久化(需要完整 registry)
  const handleSubmitDialog = useCallback((normalized: ModelRegistryItem, originalKey: string | null) => {
    const normalizedKey = toKey(normalized);
    const duplicateExists = registry.items.some(
      (item) => toKey(item) === normalizedKey && toKey(item) !== originalKey,
    );
    if (duplicateExists) {
      addToast(t('settings.models.duplicateModel', 'A model with this ID already exists'), 'error');
      return;
    }
    const withoutOld = originalKey
      ? registry.items.filter((item) => toKey(item) !== originalKey)
      : registry.items;
    persistRegistry({ items: [normalized, ...withoutOld] });
    setEditing(null);
    setEditingOriginalKey(null);
  }, [persistRegistry, registry.items, addToast, t]);

  return (
    <section className={styles.section}>
      <div className={styles.header}>
        <div>
          <h3 className={styles.title}>{t('settings.models.title', 'Models')}</h3>
          <p className={styles.description}>
            {t('settings.models.description', 'Manage Claude and Codex model IDs, context windows, and 1M support.')}
          </p>
        </div>
      </div>

      <div className={styles.toolbar}>
        <div className={styles.filters}>
          {(['all', 'claude', 'codex', 'opencode'] as const).map((provider) => (
            <button
              key={provider}
              className={`${styles.filterButton} ${providerFilter === provider ? styles.active : ''}`}
              onClick={() => setProviderFilter(provider)}
            >
              {provider !== 'all' && <ProviderModelIcon providerId={provider} size={14} colored />}
              {provider === 'all' ? t('common.all', 'All') : provider}
            </button>
          ))}
        </div>
        <div className={styles.actions}>
          <button className="btn btn-secondary btn-sm" onClick={() => startAdd(providerFilter === 'codex' ? 'codex' : providerFilter === 'opencode' ? 'opencode' : 'claude')}>
            <PlusIcon size={16} aria-hidden="true" />
            {t('common.add', 'Add')}
          </button>
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => {
              requestModelRegistry();
              addToast(t('settings.models.reloadStarted', 'Reloading model configuration...'), 'info');
            }}
          >
            {t('settings.models.reloadConfig', 'Reload Config')}
          </button>
        </div>
      </div>

      <ModelEditDialog
        isOpen={editing !== null}
        editing={editing}
        editingOriginalKey={editingOriginalKey}
        onClose={() => {
          setEditing(null);
          setEditingOriginalKey(null);
        }}
        onSubmit={handleSubmitDialog}
      />

      <div className={styles.table}>
        {visibleModels.map((model, index) => (
          <div key={toKey(model)} className={styles.row} style={{ animation: 'fadeIn 0.3s ease-out both', animationDelay: `${index * 50}ms` }}>
            <div className={styles.mainCell}>
              <span className={styles.modelId}>{model.actualModel || model.id}</span>
              <span className={styles.provider}>
                <ProviderModelIcon providerId={model.provider} size={14} colored />
                {model.provider}
              </span>
              {model.enabled === false && <span className={styles.disabled}>{t('settings.models.disabled', 'Disabled')}</span>}
              {model.enabled !== false && <span className={styles.enabled}>{t('settings.models.enabledStatus', 'Enabled')}</span>}
              <div className={styles.modelLabel}>{model.label}</div>
              {model.description && <div className={styles.modelDescription}>{model.description}</div>}
            </div>
            <div className={styles.metaCell}>
              <span>{formatCapacity(model.contextWindow, DEFAULT_CONTEXT_WINDOW)}</span>
              {model.supports1MContext && <span>1M</span>}
            </div>
            <div className={styles.rowActions}>
              {model.readOnly ? (
                <span
                      className={styles.iconButton}
                      role="img"
                      aria-label={t('settings.models.readonly', 'Read-only')}
                      title={t('settings.models.readonly', 'Read-only')}>
                  <LockIcon size={16} />
                </span>
              ) : (
                <>
                  <button className={styles.iconButton} onClick={() => startEdit(model)} title={t('common.edit', 'Edit')}>
                    <EditIcon size={16} aria-hidden="true" />
                  </button>
                  <button className={styles.iconButtonDanger} onClick={() => removeModel(model)} title={t('common.delete', 'Delete')}>
                    <TrashIcon size={16} aria-hidden="true" />
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
