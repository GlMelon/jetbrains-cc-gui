import { sendAction, subscribeEvent } from '../../../bridge/typed';
import { UPSTREAM, DOWNSTREAM } from '../../../generated/protocol';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
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

// SDK + CLI 两种运行时;用于计算「已限制」徽标计数
const FULL_RUNTIME_COUNT = 2;

export interface RuntimePolicySectionProps {
  isActive?: boolean;
  // 上方「调用模式」的当前值,用于在同一视野内联动展示降级预览
  invocationMode?: 'sdk' | 'cli';
}

const RuntimePolicySection = ({
  isActive = true,
  invocationMode = 'sdk',
}: RuntimePolicySectionProps) => {
  const {t} = useTranslation();
  const [policy, setPolicy] = useState<Record<ProviderKey, ProviderPolicy>>(DEFAULT_POLICY);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [collapsed, setCollapsed] = useState(true);
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
      subscribeEvent(DOWNSTREAM.RUNTIME_POLICY, onPolicy),
      subscribeEvent(DOWNSTREAM.RUNTIME_POLICY_UPDATED, onUpdate),
      subscribeEvent(DOWNSTREAM.RUNTIME_POLICY_ERROR, (raw) => {
        setSaving(false);
        setErrors([String(raw ?? t('settings.basic.runtimePolicy.loadFailed'))]);
      }),
    ];
    sendAction(UPSTREAM.GET_RUNTIME_POLICY);
    sendAction(UPSTREAM.GET_RUNTIME_POLICY_SCHEMA);
    return () => {
      unsubs.forEach((unsubscribe) => unsubscribe());
    };
  }, [t]);

  const serialized = useMemo(() => JSON.stringify({providers: policy}, null, 2), [policy]);

  const updateEnabled = (provider: ProviderKey, enabled: boolean) => {
    setPolicy((current) => ({
      ...current,
      [provider]: {...current[provider], enabled},
    }));
  };

  const toggleRuntime = (provider: ProviderKey, runtime: RuntimeType) => {
    setPolicy((current) => {
      const next = {...current, [provider]: {...current[provider]}} as Record<ProviderKey, ProviderPolicy>;
      const supported = new Set(next[provider].supported);
      if (supported.has(runtime)) {
        // 至少保留一个运行时,避免该 provider 无任何可用运行时
        if (supported.size <= 1) {
          return current;
        }
        supported.delete(runtime);
      } else {
        supported.add(runtime);
      }
      next[provider].supported = Array.from(supported);
      // default 不再由 UI 选择,自动跟随 supported 维护为合法值(降级目标)
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
    sendAction(UPSTREAM.SET_RUNTIME_POLICY, serialized);
  };

  const handleReset = () => {
    setSaving(true);
    setMessage('');
    setErrors([]);
    sendAction(UPSTREAM.RESET_RUNTIME_POLICY);
  };

  // 当前调用模式对应的运行时:用于配置面板内的降级预览(与发送时后端 EffectiveRuntimeResolver 的判定一致)
  const requestedRuntime: RuntimeType = invocationMode === 'cli' ? 'CLI' : 'SDK';
  // 被限制的运行时总数(每个 provider 相对 SDK+CLI 的缺失数之和)
  const restrictedCount = (['claude', 'codex'] as ProviderKey[]).reduce(
    (sum, provider) => sum + Math.max(0, FULL_RUNTIME_COUNT - policy[provider].supported.length),
    0,
  );

  if (!isActive) {
    return null;
  }

  return (
    <section className={styles.streamingSection}>
      <button
        type="button"
        className={`${styles.advancedToggle} ${collapsed ? '' : styles.open}`}
        onClick={() => setCollapsed((value) => !value)}
        aria-expanded={!collapsed}
      >
        <span className="codicon codicon-settings" />
        <span className={styles.fieldLabel}>{t('settings.basic.runtimePolicy.advancedLabel')}</span>
        <span className={`${styles.statusBadge} ${restrictedCount > 0 ? styles.restricted : ''}`}>
          {restrictedCount > 0
            ? t('settings.basic.runtimePolicy.restricted', {count: restrictedCount})
            : t('settings.basic.runtimePolicy.allAllowed')}
        </span>
        <span className={`codicon codicon-chevron-right ${styles.advancedChevron}`} />
      </button>

      {!collapsed && (
        <>
          <p className={styles.defaultRemovedHint}>
            <span className="codicon codicon-info" />
            <span>{t('settings.basic.runtimePolicy.defaultRemovedHint')}</span>
          </p>
          <div className={styles.runtimePolicyGrid}>
            {(['claude', 'codex'] as ProviderKey[]).map((provider) => {
              const item = policy[provider];
              const degraded = !item.supported.includes(requestedRuntime);
              const fallback = item.supported[0] ?? item.default;
              return (
                <div key={provider} className={styles.runtimePolicyCard}>
                  <div className={styles.runtimePolicyCardTitle}>
                    {t(`settings.basic.runtimePolicy.providers.${provider}`)}
                  </div>
                  <label className={styles.toggleWrapper}>
                    <input
                      type="checkbox"
                      className={styles.toggleInput}
                      checked={item.enabled}
                      onChange={(e) => updateEnabled(provider, e.target.checked)}
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
                  {degraded && (
                    <p className={styles.degradeNotice}>
                      <span className="codicon codicon-warning" />
                      <span>
                        {t('settings.basic.runtimePolicy.degradePreview', {
                          mode: requestedRuntime,
                          provider: t(`settings.basic.runtimePolicy.providers.${provider}`),
                          fallback,
                        })}
                      </span>
                    </p>
                  )}
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
        </>
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
