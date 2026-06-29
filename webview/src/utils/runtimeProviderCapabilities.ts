/**
 * Runtime provider capabilities subscriber registry.
 *
 * The Java bridge invokes a single set of `window.update*` callbacks to deliver
 * provider-list and active-provider updates. Multiple React components
 * (Settings hook, RuntimeProviderSelect, etc.) need to react to those events.
 *
 * [归一化重构] 原各自 createCallbackChannel 局部 dispatcher 已统一到 bridgeHub。
 * 旧 window.updateProviders/updateActiveProvider/updateCodexProviders/updateActiveCodexProvider
 * 经 compat 兼容别名转发到 bridgeHub 的对应 type,所有消费者经 subscribe* 订阅同一总线。
 *
 * 关键:消除原 updateActiveProvider「双写竞争」—— 旧代码中 installRuntimeProviderDispatchers
 * (main.tsx 挂载前)与 usageModeCallbacks(挂载时)各覆盖一次 window.updateActiveProvider,
 * 导致挂载后只有 usageMode 逻辑生效,activeProviderChannel 的订阅者(RuntimeProviderSelect /
 * Settings)收不到更新。归一化后单一 handler 同时完成 usageMode 逻辑与 channel 广播,消除竞争。
 */

import { subscribeEvent } from '../bridge/typed';
import { DOWNSTREAM } from '../generated/protocol';
import { bridgeHub } from '../bridge';

type ProviderListListener = (json: string) => void;
type ActiveProviderListener = (json: string) => void;

/**
 * 安装 compat 兼容别名(旧 window.update* → bridgeHub dispatch)。幂等。
 * provider.active 的 handler 由 usageModeCallbacks 注册(合并 usageMode 逻辑 + 广播);
 * 其余三个在此注册纯转发。必须在 React 挂载前调用(main.tsx),与原 installRuntimeProviderDispatchers 同位。
 */
export function installRuntimeProviderDispatchers(): void {
  // 4 个旧 window.update* 经 compat 别名转发到 bridgeHub。
  // provider.active 的 usageMode 合并逻辑由 usageModeCallbacks 注册为 provider.active 的
  // 另一订阅者;ConfigSelect/Settings 的订阅者也订阅 provider.active —— dispatch 自动广播给全部,
  // 消除原「挂载后 usageMode 覆盖导致 channel 订阅者收不到」的双写竞争。
  registerAliasIfAbsent('updateProviders', 'provider.list');
  registerAliasIfAbsent('updateActiveProvider', 'provider.active');
  registerAliasIfAbsent('updateCodexProviders', 'provider.codex_list');
  registerAliasIfAbsent('updateActiveCodexProvider', 'provider.active_codex');
  registerAliasIfAbsent('updateOpenCodeProviders', 'provider.opencode_list');
  registerAliasIfAbsent('updateActiveOpenCodeProvider', 'provider.active_opencode');
}

function registerAliasIfAbsent(legacyName: string, type: string): void {
  if (typeof window === 'undefined') return;
  // 直接注册兼容别名转发到 bridgeHub(bridgeHub 在 main.tsx 安装,早于本模块导入)。
  const win = window as unknown as Record<string, unknown>;
  if (!win[legacyName]) {
    win[legacyName] = (json: string) => bridgeHub.dispatch(type, json);
  }
}

/**
 * 确保兼容别名已安装(若某 window.update* 缺失则补装)。对应旧 createCallbackChannel
 * 的 ensureInstalled 语义 —— subscribe 时若 window 回调被外部(如单测 beforeEach)清除,
 * 这里重新装上,保证后端 window.update* 调用总能命中 bridgeHub。
 */
function ensureInstalled(): void {
  if (typeof window === 'undefined') return;
  const win = window as unknown as Record<string, unknown>;
  if (!win.updateProviders || !win.updateActiveProvider
      || !win.updateCodexProviders || !win.updateActiveCodexProvider
      || !win.updateOpenCodeProviders || !win.updateActiveOpenCodeProvider) {
    installRuntimeProviderDispatchers();
  }
}

// 模块加载即自动安装兼容别名(与旧实现 ensureInstalled() 语义一致):
// 保证任何导入本模块的环境(含单测)都能直接经 window.update* 触发,无需显式 install。
installRuntimeProviderDispatchers();

export function subscribeProviderList(listener: ProviderListListener): () => void {
  ensureInstalled();
  return subscribeEvent(DOWNSTREAM.PROVIDER_LIST, listener);
}

export function subscribeActiveProvider(listener: ActiveProviderListener): () => void {
  ensureInstalled();
  return subscribeEvent(DOWNSTREAM.PROVIDER_ACTIVE, listener);
}

export function subscribeCodexProviderList(listener: ProviderListListener): () => void {
  ensureInstalled();
  return subscribeEvent(DOWNSTREAM.PROVIDER_CODEX_LIST, listener);
}

export function subscribeActiveCodexProvider(listener: ActiveProviderListener): () => void {
  ensureInstalled();
  return subscribeEvent(DOWNSTREAM.PROVIDER_ACTIVE_CODEX, listener);
}

export function subscribeOpenCodeProviderList(listener: ProviderListListener): () => void {
  ensureInstalled();
  return subscribeEvent(DOWNSTREAM.PROVIDER_OPENCODE_LIST, listener);
}

export function subscribeActiveOpenCodeProvider(listener: ActiveProviderListener): () => void {
  ensureInstalled();
  return subscribeEvent(DOWNSTREAM.PROVIDER_ACTIVE_OPENCODE, listener);
}
