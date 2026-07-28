// @ts-check
/**
 * Claude 运行时注册表:按 sessionId / 签名登记 runtime 句柄,管理生命周期簿记。
 * 纯数据结构 + 纯函数(便于单测);实际的进程销毁由调用方通过 disposeFn 注入。
 */

/**
 * 注册表持有的 runtime 句柄。属性由调用方(claude-session-runtime 等)维护,
 * 这里只做读写字簿;query 字段透传给 registerActiveQueryResult 回调。
 *
 * createdAt / lastUsedAt 标为 required:进入注册表的 runtime 必已初始化这两个数值字段
 * (既有契约);类型注解只在类型层面收窄,不改变原算术运算的运行时行为。
 * @typedef {{
 *   query?: unknown,
 *   sessionId?: string,
 *   runtimeSessionEpoch?: string | null,
 *   activeTurnCount?: number,
 *   closed?: boolean,
 *   lastUsedAt: number,
 *   createdAt: number
 * }} RuntimeEntry
 */

/**
 * 一次请求的上下文片段(注册表只用到 requestedSessionId / runtimeSignature 两个字段)。
 * @typedef {{
 *   requestedSessionId?: string,
 *   runtimeSignature: string
 * }} RequestContext
 */

/** 注册 active query 的回调(透传 query 给调用方)。 */
/** @typedef {(sessionId: string, query: unknown) => void} ActiveQueryRegistrar */
/** 注销 session 的回调。 */
/** @typedef {(sessionId: string) => void} SessionRemover */

/** @type {Map<string, RuntimeEntry>} */
const runtimesBySessionId = new Map();
/** @type {Set<RuntimeEntry>} */
const anonymousRuntimes = new Set();
/** @type {Map<string, RuntimeEntry>} */
const anonymousRuntimesBySignature = new Map();

/** @type {RuntimeEntry | null} */
let activeTurnRuntime = null;

const RUNTIME_MAX_ABSOLUTE_LIFETIME_MS = 6 * 60 * 60 * 1000;
const ANONYMOUS_RUNTIME_MAX_IDLE_MS = 10 * 60 * 1000;
const SESSION_RUNTIME_MAX_IDLE_MS = 30 * 60 * 1000;
const SESSION_CLEANUP_INTERVAL_MS = 5 * 60 * 1000;

export {
  RUNTIME_MAX_ABSOLUTE_LIFETIME_MS,
  ANONYMOUS_RUNTIME_MAX_IDLE_MS,
  SESSION_RUNTIME_MAX_IDLE_MS,
  SESSION_CLEANUP_INTERVAL_MS
};

/**
 * 登记一个 runtime:有 requestedSessionId 则按 session 索引,否则归入匿名集合。
 *
 * @param {RuntimeEntry} runtime                   运行时句柄
 * @param {RequestContext} requestContext          请求上下文
 * @param {ActiveQueryRegistrar} [registerActiveQueryResult] 可选的 active query 注册回调
 * @returns {void}
 */
export function rememberRuntime(runtime, requestContext, registerActiveQueryResult) {
  if (requestContext.requestedSessionId) {
    runtimesBySessionId.set(requestContext.requestedSessionId, runtime);
    registerActiveQueryResult?.(requestContext.requestedSessionId, runtime.query);
    return;
  }

  anonymousRuntimes.add(runtime);
  anonymousRuntimesBySignature.set(requestContext.runtimeSignature, runtime);
}

/**
 * 将一个 runtime 提升为指定 session 的正式句柄:清理其在匿名/旧 session 下的登记。
 *
 * @param {RuntimeEntry} runtime                   运行时句柄
 * @param {string} sessionId                       目标 session ID
 * @param {{ removeSession?: SessionRemover, registerActiveQueryResult?: ActiveQueryRegistrar }} handlers
 * @returns {void}
 */
export function promoteRuntimeToSession(runtime, sessionId, { removeSession, registerActiveQueryResult }) {
  if (!sessionId) return;

  console.log('[LIFECYCLE] registerRuntimeSession sessionId=' + sessionId
    + ' epoch=' + (runtime?.runtimeSessionEpoch || '(none)'));

  for (const [signature, item] of anonymousRuntimesBySignature.entries()) {
    if (item === runtime) {
      anonymousRuntimesBySignature.delete(signature);
    }
  }

  for (const [existingSessionId, existingRuntime] of runtimesBySessionId.entries()) {
    if (existingRuntime === runtime && existingSessionId !== sessionId) {
      runtimesBySessionId.delete(existingSessionId);
      removeSession?.(existingSessionId);
    }
  }

  runtime.sessionId = sessionId;
  runtime.runtimeSessionEpoch = runtime.runtimeSessionEpoch || null;
  runtimesBySessionId.set(sessionId, runtime);
  anonymousRuntimes.delete(runtime);
  registerActiveQueryResult?.(sessionId, runtime.query);
}

/**
 * 从所有索引中移除指定 runtime。
 *
 * @param {RuntimeEntry} runtime   运行时句柄
 * @param {SessionRemover} [removeSession] 可选的 session 注销回调
 * @returns {void}
 */
export function removeRuntime(runtime, removeSession) {
  anonymousRuntimes.delete(runtime);

  for (const [signature, item] of anonymousRuntimesBySignature.entries()) {
    if (item === runtime) {
      anonymousRuntimesBySignature.delete(signature);
    }
  }

  for (const [sessionId, item] of runtimesBySessionId.entries()) {
    if (item === runtime) {
      runtimesBySessionId.delete(sessionId);
      removeSession?.(sessionId);
    }
  }
}

/**
 * 按请求上下文查找已登记的 runtime。
 *
 * @param {RequestContext} requestContext 请求上下文
 * @returns {RuntimeEntry | null}
 */
export function findRuntimeForRequest(requestContext) {
  if (requestContext.requestedSessionId) {
    return runtimesBySessionId.get(requestContext.requestedSessionId) || null;
  }
  return anonymousRuntimesBySignature.get(requestContext.runtimeSignature) || null;
}

/**
 * @param {RuntimeEntry | null | undefined} runtime
 * @returns {void}
 */
export function beginRuntimeTurn(runtime) {
  if (!runtime) return;
  runtime.activeTurnCount = (runtime.activeTurnCount || 0) + 1;
}

/**
 * @param {RuntimeEntry | null | undefined} runtime
 * @returns {void}
 */
export function endRuntimeTurn(runtime) {
  if (!runtime) return;
  runtime.activeTurnCount = Math.max((runtime.activeTurnCount || 0) - 1, 0);
}

/**
 * @param {RuntimeEntry | null | undefined} runtime
 * @returns {void}
 */
export function touchRuntime(runtime) {
  if (!runtime || runtime.closed) return;
  runtime.lastUsedAt = Date.now();
}

/**
 * 判断 runtime 是否可按空闲策略回收(已关闭 / 超绝对寿命 / 空闲超阈值)。
 *
 * @param {RuntimeEntry | null | undefined} runtime
 * @param {number} now       当前时间戳
 * @param {number} maxIdleMs 最大空闲阈值
 * @returns {boolean}
 */
export function canDisposeIdleRuntime(runtime, now, maxIdleMs) {
  if (!runtime || runtime.closed) return false;
  if (now - runtime.createdAt > RUNTIME_MAX_ABSOLUTE_LIFETIME_MS) return true;
  if ((runtime.activeTurnCount || 0) > 0) return false;
  return now - runtime.lastUsedAt > maxIdleMs;
}

/**
 * 清扫过期的匿名 runtime。
 *
 * @param {(runtime: RuntimeEntry) => void | Promise<void>} disposeFn 销毁回调
 * @returns {Promise<void>}
 */
export async function cleanupStaleAnonymousRuntimes(disposeFn) {
  const now = Date.now();
  const snapshot = [...anonymousRuntimes];
  for (const runtime of snapshot) {
    if (runtime.closed) {
      anonymousRuntimes.delete(runtime);
      continue;
    }
    if (canDisposeIdleRuntime(runtime, now, ANONYMOUS_RUNTIME_MAX_IDLE_MS)) {
      console.log(`[DAEMON] Disposing stale anonymous runtime (idle ${Math.round((now - runtime.lastUsedAt) / 1000)}s)`);
      await disposeFn(runtime);
    }
  }
}

/**
 * 清扫过期的 session runtime。
 *
 * @param {(runtime: RuntimeEntry) => void | Promise<void>} disposeFn 销毁回调
 * @returns {Promise<void>}
 */
export async function cleanupStaleSessionRuntimes(disposeFn) {
  const now = Date.now();
  for (const [sessionId, runtime] of runtimesBySessionId.entries()) {
    if (runtime.closed) {
      runtimesBySessionId.delete(sessionId);
      continue;
    }
    if (canDisposeIdleRuntime(runtime, now, SESSION_RUNTIME_MAX_IDLE_MS)) {
      console.log(`[DAEMON] Disposing stale session runtime ${sessionId} (idle ${Math.round((now - runtime.lastUsedAt) / 1000)}s)`);
      await disposeFn(runtime);
    }
  }
}

/**
 * @param {RuntimeEntry | null | undefined} runtime
 * @returns {void}
 */
export function setActiveTurnRuntime(runtime) {
  activeTurnRuntime = runtime || null;
}

/**
 * @returns {RuntimeEntry | null}
 */
export function getActiveTurnRuntime() {
  return activeTurnRuntime;
}

/**
 * @returns {void}
 */
export function clearActiveTurnRuntime() {
  activeTurnRuntime = null;
}

/**
 * @param {RuntimeEntry | null | undefined} runtime
 * @returns {void}
 */
export function clearActiveTurnRuntimeIf(runtime) {
  if (activeTurnRuntime === runtime) {
    activeTurnRuntime = null;
  }
}

/**
 * 汇总所有已登记的 runtime(匿名 + 签名索引 + session 索引 + 当前 turn)。
 *
 * @returns {Set<RuntimeEntry>}
 */
export function getAllRuntimes() {
  // 三个集合/映射的元素均为 RuntimeEntry 对象(不会 falsy);只有 activeTurnRuntime
  // 可能为 null,故显式判断后再加入,等价于原 [...sources, activeTurnRuntime].filter(Boolean)。
  /** @type {Set<RuntimeEntry>} */
  const result = new Set([
    ...anonymousRuntimes,
    ...anonymousRuntimesBySignature.values(),
    ...runtimesBySessionId.values(),
  ]);
  if (activeTurnRuntime) {
    result.add(activeTurnRuntime);
  }
  return result;
}

/**
 * @param {string} sessionId
 * @returns {RuntimeEntry | null}
 */
export function getRuntimeForSession(sessionId) {
  return runtimesBySessionId.get(sessionId) || null;
}

/**
 * @returns {{ anonymousRuntimeCount: number, sessionRuntimeCount: number, activeTurnEpoch: string | null }}
 */
export function getSnapshot() {
  return {
    anonymousRuntimeCount: anonymousRuntimes.size,
    sessionRuntimeCount: runtimesBySessionId.size,
    activeTurnEpoch: activeTurnRuntime?.runtimeSessionEpoch || null
  };
}

/**
 * @returns {void}
 */
export function resetRegistryState() {
  anonymousRuntimes.clear();
  anonymousRuntimesBySignature.clear();
  runtimesBySessionId.clear();
  activeTurnRuntime = null;
}
