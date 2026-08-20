/**
 * bridge/compat.ts
 *
 * 双轨兼容层(迁移期基石)。
 *
 * 作用:在「前端已迁到 bridgeHub.subscribe(type),但后端仍调用旧 window.<legacyName>」的
 * 过渡阶段,把旧 window.<legacyName> 注册为转发别名 —— 命中后等价于 bridgeHub.dispatch(type)。
 *
 * 这样后端可在后续阶段逐个把 callJavaScript("window.xxx") 切到 dispatchEvent(type),
 * 任一阶段新旧路径并存、行为一致,可单点回退。
 *
 * 注意:Phase 0 仅建机制,不登记任何条目。各 Phase 迁移具体回调时,在 events/ 目录
 * 登记 type 并调用 compat.registerLegacyAlias()。
 *
 * 详见 plan: typed-booping-newt.md(迁移引擎:双轨兼容)。
 */

import { bridgeHub } from './hub';
import type { Unsubscribe } from './types';

/**
 * 旧 window 回调名(去 window. 前缀) → 总线 type 的映射。
 */
const legacyToType = new Map<string, string>();
let installed = false;

/**
 * 登记「旧 window 回调名 → 总线 type」的兼容别名,并安装转发函数到 window。
 * 幂等:重复登记同一 legacyName 仅保留最后映射。
 *
 * 安装后,window.<legacyName>(json) 等价于 window.__bridge.dispatch(<type>, json)。
 */
export function registerLegacyAlias(legacyName: string, type: string): Unsubscribe {
  if (typeof window === 'undefined') return () => {};
  const win = window as unknown as Record<string, unknown>;
  installed = true;
  const previousType = legacyToType.get(legacyName);
  const hadPreviousValue = Object.prototype.hasOwnProperty.call(win, legacyName);
  const previousValue = win[legacyName];
  const installedAlias = (payloadJson?: string) => {
    bridgeHub.dispatch(type, payloadJson);
  };
  legacyToType.set(legacyName, type);
  win[legacyName] = installedAlias;

  return bridgeHub.trackCleanup(() => {
    // Do not remove a newer registration that replaced this alias.
    if (win[legacyName] !== installedAlias || legacyToType.get(legacyName) !== type) return;
    if (previousType !== undefined) {
      legacyToType.set(legacyName, previousType);
    } else {
      legacyToType.delete(legacyName);
    }
    if (hadPreviousValue) {
      win[legacyName] = previousValue;
    } else {
      try { delete win[legacyName]; } catch { /* ignore */ }
    }
  });
}

/**
 * 批量登记别名。便捷入口。
 * @example registerLegacyAliases({ onUsageUpdate: 'usage.update', addToast: 'toast.show' })
 */
export function registerLegacyAliases(mapping: Record<string, string>): void {
  for (const [legacyName, type] of Object.entries(mapping)) {
    registerLegacyAlias(legacyName, type);
  }
}

/**
 * 移除某个兼容别名(迁移完成、后端已切到 dispatchEvent 后调用)。
 */
export function unregisterLegacyAlias(legacyName: string): void {
  if (typeof window === 'undefined') return;
  legacyToType.delete(legacyName);
  try {
    delete (window as unknown as Record<string, unknown>)[legacyName];
  } catch {
    // ignore
  }
}

/**
 * 标记兼容层已就绪(诊断用)。
 */
export function isCompatInstalled(): boolean {
  return installed;
}


