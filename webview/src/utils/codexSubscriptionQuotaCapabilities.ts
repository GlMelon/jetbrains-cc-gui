import { sendBridgeEvent } from './bridge';
import { createCallbackChannel } from './createCallbackChannel';

export interface CodexSubscriptionQuotaWindow {
  windowLabel: string;
  windowHours: number;
  usedPercent?: number | null;
  remainingPercent?: number | null;
  resetsAt?: number | null;
  usedTokens: number;
  limitTokens?: number | null;
  remainingTokens?: number | null;
  usedCost?: number | null;
  sessionCount?: number;
  lastUpdated?: number;
  source?: string;
}

export interface CodexSubscriptionQuotaSnapshot {
  status: 'ok' | 'unavailable' | 'error';
  fetchedAt: number;
  source?: string;
  error?: string;
  /** Machine-readable reason for unavailability, e.g. 'api_key_mode'. */
  reasonCode?: string;
  windows: {
    fiveHour: CodexSubscriptionQuotaWindow;
    weekly: CodexSubscriptionQuotaWindow;
  };
}

type QuotaListener = (snapshot: CodexSubscriptionQuotaSnapshot) => void;

function safeParseSnapshot(json: string): CodexSubscriptionQuotaSnapshot | null {
  try {
    const parsed = JSON.parse(json) as CodexSubscriptionQuotaSnapshot;
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    if (!parsed.windows?.fiveHour || !parsed.windows?.weekly) {
      return null;
    }
    return parsed;
  } catch (error) {
    console.error('[codexSubscriptionQuotaCapabilities] Failed to parse snapshot:', error);
    return null;
  }
}

// 创建回调通道
const quotaChannel = createCallbackChannel<CodexSubscriptionQuotaSnapshot>({
  name: 'codexSubscriptionQuota',
});

export function installCodexSubscriptionQuotaDispatchers(): void {
  window.updateCodexSubscriptionQuota = (json: string) => {
    const snapshot = safeParseSnapshot(json);
    if (snapshot) quotaChannel.emit(snapshot);
  };
}

const ensureInstalled = (): void => {
  if (typeof window === 'undefined') return;
  if (window.updateCodexSubscriptionQuota) return;
  installCodexSubscriptionQuotaDispatchers();
};

// 自动安装 dispatchers
ensureInstalled();

export function subscribeCodexSubscriptionQuota(listener: QuotaListener): () => void {
  return quotaChannel.subscribe(listener);
}

export function fetchCodexSubscriptionQuota(): void {
  sendBridgeEvent('get_codex_subscription_quota');
}
