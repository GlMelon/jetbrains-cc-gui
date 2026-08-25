import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { sendAction } from '../../../bridge/typed';
import { UPSTREAM } from '../../../generated/protocol';
import styles from './style.module.less';

/** channel-manager.js dsh status 的 payload(宽松解析,字段缺失容错)。 */
interface DshStatusPayload {
  installed?: boolean;
  hostRunning?: boolean;
  version?: string;
  origin?: string;
  error?: string;
  host?: string;
  port?: number;
  autoStart?: boolean;
  settings?: { bin?: string; host?: string; port?: number; autoStart?: boolean };
}

interface DshProviderSectionProps {
  showHeader?: boolean;
}

interface DshFormState {
  bin: string;
  host: string;
  port: string;
  autoStart: boolean;
}

const DEFAULT_FORM: DshFormState = { bin: '', host: '', port: '', autoStart: false };

/**
 * DSH host 连接卡(#3c):host 状态轮询(get_dsh_status→window.updateDshStatus)+
 * 启停(start_dsh_host/stop_dsh_host)+ 配置保存(bin/host/port/autoStart→save_dsh_settings)。
 * 后端 typed handler 见 handler/dsh/DshHostActionHandlers;spawn 见 DshHostRunner。
 */
const DshProviderSection = ({ showHeader = true }: DshProviderSectionProps) => {
  const { t } = useTranslation();
  const [status, setStatus] = useState<DshStatusPayload | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<DshFormState>(DEFAULT_FORM);

  useEffect(() => {
    window.updateDshStatus = (dataOrStr) => {
      try {
        const payload = (typeof dataOrStr === 'string' ? JSON.parse(dataOrStr) : dataOrStr) as DshStatusPayload;
        setStatus(payload);
        // 后端 status 附带持久化的 dsh 设置,同步进表单(仅首次/字段为空时,不覆盖用户编辑)
        if (payload?.settings) {
          setForm((prev) => ({
            bin: prev.bin || payload.settings!.bin || '',
            host: prev.host || payload.settings!.host || '',
            port: prev.port || (payload.settings!.port != null ? String(payload.settings!.port) : ''),
            autoStart: prev.autoStart || payload.settings!.autoStart || false,
          }));
        }
      } catch {
        setStatus({ error: 'Invalid status payload' });
      } finally {
        setBusy(false);
      }
    };
    sendAction(UPSTREAM.GET_DSH_STATUS);
    return () => {
      window.updateDshStatus = undefined;
    };
  }, []);

  const installed = status?.installed === true;
  const running = status?.hostRunning === true;
  const adopted = status?.origin === 'adopted';

  const handleStart = useCallback(() => {
    setBusy(true);
    sendAction(UPSTREAM.START_DSH_HOST);
    // 后端 ensureHost 最长 60s;busy 由下一次 updateDshStatus 解除
    window.setTimeout(() => setBusy(false), 65_000);
  }, []);

  const handleStop = useCallback(() => {
    setBusy(true);
    sendAction(UPSTREAM.STOP_DSH_HOST);
    window.setTimeout(() => setBusy(false), 35_000);
  }, []);

  const handleSave = useCallback(() => {
    setBusy(true);
    const payload = {
      bin: form.bin.trim() || '',
      host: form.host.trim() || '',
      port: form.port.trim() ? Number(form.port.trim()) : 3080,
      autoStart: form.autoStart,
    };
    sendAction(UPSTREAM.SAVE_DSH_SETTINGS, JSON.stringify(payload));
    window.setTimeout(() => setBusy(false), 35_000);
  }, [form]);

  const openWebUi = useCallback(() => {
    const host = form.host.trim() || status?.host || '127.0.0.1';
    const port = form.port.trim() || (status?.port ?? 3080);
    window.open(`http://${host}:${port}`, '_blank');
  }, [form, status]);

  const stateLabel = !status
    ? t('settings.cli.dsh.state.checking', 'Checking…')
    : status.error
      ? status.error
      : !installed
        ? t('settings.cli.dsh.state.notInstalled', 'Not installed')
        : running
          ? t('settings.cli.dsh.state.connected', 'Connected')
          : t('settings.cli.dsh.state.notRunning', 'Not running');

  return (
    <div className={styles.section}>
      {showHeader && (
        <div className={styles.header}>
          <h3 className={styles.sectionTitle}>{t('settings.cli.dsh.cardTitle', 'DeepSeek Harness (DSH)')}</h3>
          <p className={styles.sectionDesc}>
            {t('settings.cli.dsh.hint', 'One persistent local dsh web host serves all sessions.')}
          </p>
        </div>
      )}

      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={`${styles.stateDot} ${running ? styles.on : styles.off}`} />
          <span className={styles.stateText}>{stateLabel}</span>
          {status?.version && <span className={styles.versionBadge}>v{status.version}</span>}
          {adopted && (
            <span className={styles.adoptedBadge} title={t('settings.cli.dsh.adoptedHint', '')}>
              · {t('settings.cli.dsh.adopted', 'adopted')}
            </span>
          )}
        </div>

        <div className={styles.buttonRow}>
          <button type="button" className={styles.btn} disabled={busy || running} onClick={handleStart}>
            {t('settings.cli.dsh.startHost', 'Start host')}
          </button>
          <button type="button" className={styles.btn} disabled={busy || !running || adopted} onClick={handleStop}>
            {t('settings.cli.dsh.stopHost', 'Stop host (plugin-spawned only)')}
          </button>
          <button type="button" className={styles.btn} disabled={!running} onClick={openWebUi}>
            {t('settings.cli.dsh.openWebUi', 'Open DSH Web UI')}
          </button>
        </div>

        <label className={styles.toggleRow}>
          <input
            type="checkbox"
            checked={form.autoStart}
            onChange={(e) => setForm((prev) => ({ ...prev, autoStart: e.target.checked }))}
          />
          <span>{t('settings.cli.dsh.autoStart', 'Auto-start when needed')}</span>
        </label>

        <div className={styles.formGrid}>
          <label className={styles.field}>
            <span>DSH bin</span>
            <input
              type="text"
              value={form.bin}
              placeholder="dsh (PATH lookup)"
              onChange={(e) => setForm((prev) => ({ ...prev, bin: e.target.value }))}
            />
          </label>
          <label className={styles.field}>
            <span>Host</span>
            <input
              type="text"
              value={form.host}
              placeholder="127.0.0.1"
              onChange={(e) => setForm((prev) => ({ ...prev, host: e.target.value }))}
            />
          </label>
          <label className={styles.field}>
            <span>Port</span>
            <input
              type="text"
              value={form.port}
              placeholder="3080"
              onChange={(e) => setForm((prev) => ({ ...prev, port: e.target.value }))}
            />
          </label>
        </div>

        <div className={styles.buttonRow}>
          <button type="button" className={styles.btnPrimary} disabled={busy} onClick={handleSave}>
            {t('settings.common.save', 'Save')}
          </button>
        </div>
      </div>
    </div>
  );
};

export default DshProviderSection;
