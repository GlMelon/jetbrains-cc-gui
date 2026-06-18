import {useEffect, useMemo, useState} from 'react';
import {useTranslation} from 'react-i18next';
import {bridgeHub} from '../../../bridge';
import {sendBridgeEvent} from '../../../utils/bridge';
import styles from './style.module.less';

type RuntimeType = 'SDK' | 'CLI';
type ProviderKey = 'claude' | 'codex';

type ProviderPolicy = {
  enabled: boolean;
  supported: RuntimeType[];
  default: RuntimeType;
};

type RuntimePolicyPayload = {
  providers?: Record<ProviderKey, ProviderPolicy>;
  success?: boolean;
  reset?: boolean;
  errors?: string[];
};

const DEFAULT_POLICY: Record<ProviderKey, ProviderPolicy> = {
  claude: { enabled: true, supported: ['SDK', 'CLI'], default: 'SDK' },
  codex: { enabled: true, supported: ['SDK', 'CLI'], default: 'SDK' },
};

export interface RuntimePolicySectionProps {
  isActive?: boolean;
}

const RuntimePolicySection = ({isActive = true}: RuntimePolicySectionProps) => {
  const {t} = useTranslation();
  const [policy, setPolicy] = useState<Record<ProviderKey, ProviderPolicy>>(DEFAULT_POLICY);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [errors, setErrors] = useState<string[]>([]);

  useEffect(() => {
    const onPolicy = (raw: unknown) => {
      try {
        const payload = JSON.parse(String(raw ?? '{}')) as RuntimePolicyPayload;
        if (payload.providers) {
          setPolicy({
            claude: normalizeProviderPolicy(payload.providers.claude, DEFAULT_POLICY.claude),
            codex: normalizeProviderPolicy(payload.providers.codex, DEFAULT_POLICY.codex),
          });
        }
        setLoaded(true);
        setSaving(false);
        setErrors([]);
        setMessage(t('settings.basic.runtimePolicy.loaded'));
      } catch {
        setMessage(t('settings.basic.runtimePolicy.loadFailed'));
        setSaving(false);
      }
    };

    const onUpdate = (raw: unknown) => {
      try {
        const payload = JSON.parse(String(raw ?? '{}')) as RuntimePolicyPayload;
        setSaving(false);
        if (payload.success) {
          if (payload.providers) {
            setPolicy({
              claude: normalizeProviderPolicy(payload.providers.claude, DEFAULT_POLICY.claude),
              codex: normalizeProviderPolicy(payload.providers.codex, DEFAULT_POLICY.codex),
            });
          }
          setErrors([]);
          setMessage(payload.reset
            ? t('settings.basic.runtimePolicy.resetSuccess')
            : t('settings.basic.runtimePolicy.saveSuccess'));
          setLoaded(true);
          return;
        }
        setErrors(payload.errors ?? [t('settings.basic.runtimePolicy.saveFailed')]);
        setMessage('');
      } catch {
        setSaving(false);
        setErrors([t('settings.basic.runtimePolicy.saveFailed')]);
      }
    };

    const unsubs = [
      bridgeHub.subscribe('runtime_policy', onPolicy),
      bridgeHub.subscribe('runtime_policy_updated', onUpdate),
      bridgeHub.subscribe('runtime_policy_error', (raw) => {
        setSaving(false);
        setErrors([String(raw ?? t('settings.basic.runtimePolicy.loadFailed'))]);
      }),
    ];
    sendBridgeEvent('get_runtime_policy');
    sendBridgeEvent('get_runtime_policy_schema');
    return () => {
      unsubs.forEach((unsubscribe) => unsubscribe());
    };
  }, [t]);

  const serialized = useMemo(() => JSON.stringify({providers: policy}, null, 2), [policy]);

  const updateProvider = (provider: ProviderKey, key: keyof ProviderPolicy, value: boolean | RuntimeType) => {
    setPolicy((current) => {
      const next = {...current, [provider]: {...current[provider]}} as Record<ProviderKey, ProviderPolicy>;
      if (key === 'enabled') {
        next[provider].enabled = value as boolean;
      } else if (key === 'default') {
        next[provider].default = value as RuntimeType;
      }
      return next;
    });
  };

  const toggleRuntime = (provider: ProviderKey, runtime: RuntimeType) => {
    setPolicy((current) => {
      const next = {...current, [provider]: {...current[provider]}} as Record<ProviderKey, ProviderPolicy>;
      const supported = new Set(next[provider].supported);
      if (supported.has(runtime)) supported.delete(runtime);
      else supported.add(runtime);
      next[provider].supported = Array.from(supported);
      if (!next[provider].supported.includes(next[provider].default)) {
        next[provider].default = next[provider].supported[0] ?? 'CLI';
      }
      return next;
    });
  };

  const handleSave = () => {
    setSaving(true);
    setMessage('');
    setErrors([]);
    sendBridgeEvent('set_runtime_policy', serialized);
  };

  const handleReset = () => {
    setSaving(true);
    setMessage('');
    setErrors([]);
    sendBridgeEvent('reset_runtime_policy');
  };

  if (!isActive) {
    return null;
  }

  return (
    <section className={styles.streamingSection}>
      <div className={styles.fieldHeader}>
        <span className="codicon codicon-settings" />
        <span className={styles.fieldLabel}>{t('settings.basic.runtimePolicy.label')}</span>
      </div>
      <p className={styles.formHint}>
        <span className="codicon codicon-info" />
        <span>{t('settings.basic.runtimePolicy.hint')}</span>
      </p>
      <div className={styles.runtimePolicyGrid}>
        {(['claude', 'codex'] as ProviderKey[]).map((provider) => {
          const item = policy[provider];
          return (
            <div key={provider} className={styles.runtimePolicyCard}>
              <div className={styles.runtimePolicyCardTitle}>{t(`settings.basic.runtimePolicy.providers.${provider}`)}</div>
              <label className={styles.toggleWrapper}>
                <input
                  type="checkbox"
                  className={styles.toggleInput}
                  checked={item.enabled}
                  onChange={(e) => updateProvider(provider, 'enabled', e.target.checked)}
                />
                <span className={styles.toggleSlider} />
                <span className={styles.toggleLabel}>
                  {item.enabled ? t('settings.basic.runtimePolicy.enabled') : t('settings.basic.runtimePolicy.disabled')}
                </span>
              </label>
              <div className={styles.runtimePolicyOptions}>
                {(['SDK', 'CLI'] as RuntimeType[]).map((runtime) => (
                  <button
                    key={runtime}
                    type="button"
                    className={`${styles.runtimePolicyPill} ${item.supported.includes(runtime) ? styles.active : ''}`}
                    onClick={() => toggleRuntime(provider, runtime)}
                  >
                    {runtime}
                  </button>
                ))}
              </div>
              <div className={styles.runtimePolicyDefaultRow}>
                <span>{t('settings.basic.runtimePolicy.default')}</span>
                <select
                  value={item.default}
                  onChange={(e) => updateProvider(provider, 'default', e.target.value as RuntimeType)}
                >
                  {item.supported.map((runtime) => (
                    <option key={runtime} value={runtime}>{runtime}</option>
                  ))}
                </select>
              </div>
            </div>
          );
        })}
      </div>
      <div className={styles.runtimePolicyActions}>
        <button type="button" className={styles.saveBtn} onClick={handleSave} disabled={saving || !loaded}>
          <span className="codicon codicon-save" />
          <span>{t('settings.basic.runtimePolicy.save')}</span>
        </button>
        <button type="button" className={styles.saveBtn} onClick={handleReset} disabled={saving}>
          <span className="codicon codicon-refresh" />
          <span>{t('settings.basic.runtimePolicy.reset')}</span>
        </button>
      </div>
      {message && <p className={styles.formHint}>{message}</p>}
      {errors.length > 0 && (
        <div className={styles.runtimePolicyErrors}>
          {errors.map((error) => (
            <p key={error} className={styles.formHint}>{error}</p>
          ))}
        </div>
      )}
    </section>
  );
};

const normalizeProviderPolicy = (value: ProviderPolicy | undefined, fallback: ProviderPolicy): ProviderPolicy => {
  if (!value) {
    return fallback;
  }
  return {
    enabled: value.enabled,
    supported: value.supported?.length ? value.supported : fallback.supported,
    default: value.default,
  };
};

export default RuntimePolicySection;
