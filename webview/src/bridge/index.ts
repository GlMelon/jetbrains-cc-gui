/**
 * bridge/index.ts
 *
 * 下行总线(Java → 前端)归一化入口(barrel)。
 *
 * 使用:
 *   - main.tsx(挂载前):installBridge() 安装 window.__bridge。
 *   - 握手:onFrontendReady → markReady() 回放缓冲。
 *   - 业务模块:bridgeHub.subscribe(type, handler) / subscribePassthrough / request。
 *   - 迁移期:compat.registerLegacyAlias(legacyName, type)。
 *   - streaming/session 黑板:bridgeState.get/set + BridgeStateKey。
 *
 * 详见 plan: typed-booping-newt.md。
 */

export { bridgeHub, installBridge } from './hub';
export type { BridgeStateKeyDef } from './store';
export {
  registerLegacyAlias,
  registerLegacyAliases,
  unregisterLegacyAlias,
  isCompatInstalled,
} from './compat';
export type {
  BridgeEventDef,
  BridgeEventKind,
  BridgeListener,
  Unsubscribe,
  BridgeDispatch,
  WindowBridge,
} from './types';
