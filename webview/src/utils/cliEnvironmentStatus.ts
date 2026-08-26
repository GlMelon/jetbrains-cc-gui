import { sendAction, subscribeEvent } from '../bridge/typed';
import { DOWNSTREAM, UPSTREAM } from '../generated/protocol';

/**
 * CLI 环境安装状态的前端共享快照(仿 modelRegistry 的 snapshot+subscribe 模式)。
 *
 * 数据源:后端 {@link DOWNSTREAM.CLI_ENVIRONMENT_STATUS}(CliEnvironmentChecker 检测,
 * 覆盖 claude/codex/opencode/grok/kimi/pi 六个 CLI;omp/dsh 走 ai-bridge/host RPC,
 * 不在检测范围内 → 判定天然放行)。
 *
 * 消费方:ProviderSelect / BlinkingLogo 菜单(未安装禁用+提示)、
 * ModelRegistrySection / ModelEditDialog(筛选与新增分段)、ProviderTabSection(页签)。
 * 判定语义见 {@link isProviderCliNotInstalled}:**未知即放行**——检测结果未到达
 * (启动瞬间)或检测失败时宽松降级,避免闪烁误禁;仅"已确认未安装"才门控。
 */

/** 与后端 CliEnvironmentStatus 序列化契约对齐(仅声明消费方用到的字段)。 */
export interface CliEnvironmentStatusEntry {
  name: string;
  displayName: string;
  installed: boolean;
  currentVersion?: string;
  latestVersion?: string;
  installPath?: string;
  installSource?: string;
  npmPackage?: string;
  hasUpdate?: boolean;
  errorMessage?: string;
}

export interface CliEnvironmentSnapshot {
  /** 检测结果未到达(或解析失败)时为 false,此时所有判定放行。 */
  ready: boolean;
  statuses: Record<string, CliEnvironmentStatusEntry>;
}

const listeners = new Set<() => void>();
let currentSnapshot: CliEnvironmentSnapshot = { ready: false, statuses: {} };
let subscriptionInitialized = false;
let initialRequestSent = false;

function publish(snapshot: CliEnvironmentSnapshot): void {
  currentSnapshot = snapshot;
  listeners.forEach((listener) => listener());
}

function parseStatusPayload(json: unknown): Record<string, CliEnvironmentStatusEntry> | null {
  try {
    const data = typeof json === 'string' ? JSON.parse(json) : json;
    if (!data || typeof data !== 'object' || Array.isArray(data)) {
      return null;
    }
    // 后端错误分支下发 {error, timestamp}(无 per-tool 键),视为"未就绪"而非空表
    if ('error' in (data as Record<string, unknown>) && !('claude' in (data as Record<string, unknown>))) {
      return null;
    }
    return data as Record<string, CliEnvironmentStatusEntry>;
  } catch {
    return null;
  }
}

/** 事件负载解析(导出供测试;运行时仅内部使用)。 */
export const parseCliEnvironmentPayload = parseStatusPayload;

function ensureSubscription(): void {
  if (subscriptionInitialized || typeof window === 'undefined') {
    return;
  }
  subscriptionInitialized = true;
  subscribeEvent(DOWNSTREAM.CLI_ENVIRONMENT_STATUS, (json) => {
    const statuses = parseStatusPayload(json);
    // 解析失败(含后端 error 分支)→ 保持未就绪,判定全放行
    publish(statuses ? { ready: true, statuses } : { ready: false, statuses: {} });
  });
  // 设置页「安装」成功事件:后端带回该工具最新 status,合并进全局快照,
  // 供应商下拉等门控 UI 立即解除禁用(无需手动重新检测)。
  subscribeEvent(DOWNSTREAM.CLI_INSTALL_RESULT, (json) => {
    try {
      const result = typeof json === 'string' ? JSON.parse(json) : json;
      const status = result?.status;
      const key = status?.name ?? result?.toolId;
      if (result?.success && key && status) {
        publish({
          ready: true,
          statuses: { ...currentSnapshot.statuses, [key]: status },
        });
      }
    } catch {
      // Ignore malformed install results.
    }
  });
}

/**
 * 请求一次检测。force=true 绕过后端缓存强制全量重检(手动"重新检测"按钮);
 * 默认走缓存,后端 TTL 内秒回,适合启动时的首次拉取。
 */
export function requestCliEnvironmentCheck(force = false): void {
  ensureSubscription();
  sendAction(UPSTREAM.CHECK_CLI_ENVIRONMENT, force ? { force: true } : '');
}

/**
 * 拉取快照并保证已订阅。首次调用(每 tab 一次)会自动发起一次非强制检测,
 * 让常驻 UI(供应商下拉等)无需各自显式请求。
 */
export function subscribeCliEnvironment(listener: () => void): () => void {
  ensureSubscription();
  listeners.add(listener);
  if (!initialRequestSent) {
    initialRequestSent = true;
    requestCliEnvironmentCheck(false);
  }
  return () => {
    listeners.delete(listener);
  };
}

export function getCliEnvironmentSnapshot(): CliEnvironmentSnapshot {
  ensureSubscription();
  return currentSnapshot;
}

/**
 * 门控判定:provider 是否**已确认未安装**其 CLI。
 * 返回 true 的充要条件:检测结果已就绪 且 该 provider 在检测范围内 且 installed=false。
 * 检测未就绪/不在检测范围(omp/dsh)/已安装 → 一律 false(放行)。
 */
export function isProviderCliNotInstalled(providerId: string): boolean {
  const snapshot = getCliEnvironmentSnapshot();
  if (!snapshot.ready) {
    return false;
  }
  const entry = snapshot.statuses[providerId];
  return entry != null && entry.installed === false;
}

// ── 测试后门(仿 modelRegistry.__setModelRegistryForTests) ──
// 直接设置快照,绕过 bridge 订阅链路。null = 复位为未就绪。
export function __setCliEnvironmentForTests(
  statuses: Record<string, CliEnvironmentStatusEntry> | null,
): void {
  currentSnapshot = statuses ? { ready: true, statuses } : { ready: false, statuses: {} };
  listeners.forEach((listener) => listener());
}
