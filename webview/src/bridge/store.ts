/**
 * bridge/store.ts
 *
 * BridgeStateStore — 跨回调协作"黑板"的归一化容器。
 *
 * 背景:原架构把 streaming/session 协作标志裸挂在 window 上(如
 * window.__sessionTransitioning / __activeStreamScopeKey / __lastStreamActivityAt ...
 * 约 17 个)。这些标志大多是在 React setState updater **之外**被同步赋值的,
 * 刻意规避 React 异步 updater 的时序问题(见 streamingCallbacks.ts 注释)。
 *
 * 因此本 store 的关键约束:
 *  - get/set 必须是**同步**的(不经过 React 调度);
 *  - 不提供"触发 re-render"的订阅能力(那会引入异步时序,破坏现有竞态防护);
 *  - 仅作为跨模块共享可变状态的命名空间,取代裸 window 赋值,便于盘点与文档化。
 *
 * 需要驱动渲染的状态仍应走 React state/Context;本 store 只承接"同步控制标志"。
 *
 * 详见 plan: typed-booping-newt.md(Phase 4 `__` 黑板归一化)。
 */

type Key = string;

class BridgeStateStore {
  private readonly values = new Map<Key, unknown>();

  /**
   * 同步读取。未设置返回 undefined(与原 window.__xxx 行为一致)。
   */
  get<T = unknown>(key: Key): T | undefined {
    return this.values.get(key) as T | undefined;
  }

  /**
   * 同步写入(updater 外调用安全)。返回写入的值,便于链式表达。
   */
  set<T>(key: Key, value: T): T {
    this.values.set(key, value);
    return value;
  }

  /**
   * 删除键。对应原 `delete window.__xxx`。
   */
  remove(key: Key): void {
    this.values.delete(key);
  }

  /**
   * 是否存在。对应原 `typeof window.__xxx !== 'undefined'` / `!!window.__xxx` 的存在性判定。
   * 注意:与 window 不同,本 store 的 has 反映"键是否被 set 过",而非 truthiness。
   */
  has(key: Key): boolean {
    return this.values.has(key);
  }

  /**
   * 调试用:返回当前全部键的快照。仅供诊断/日志,勿用于业务逻辑。
   */
  snapshotKeys(): Key[] {
    return Array.from(this.values.keys());
  }

  /**
   * 清空全部状态(测试用;生产环境不调用)。
   */
  clear(): void {
    this.values.clear();
  }
}

/**
 * 单例。模块加载即创建,main.tsx 安装 bus 时一并挂到 window.__bridge.state。
 */
export const bridgeState = new BridgeStateStore();

/**
 * 状态键常量集中声明处(随迁移逐步填充)。
 * 命名沿用原 window.__xxx 去掉 `__` 前缀,便于追溯。
 */
export const BridgeStateKey = {
  /** 会话切换期间抑制旧 session 的 stale 回调 */
  SESSION_TRANSITIONING: 'sessionTransitioning',
  /** 当前活跃流 scope(provider:tabId:turnId),多 tab/多 turn 隔离 */
  ACTIVE_STREAM_SCOPE_KEY: 'activeStreamScopeKey',
  /** onStreamEnd 幂等守卫 */
  STREAM_END_PROCESSED_TURN_ID: 'streamEndProcessedTurnId',
  /** stall watchdog 心跳时间戳 */
  LAST_STREAM_ACTIVITY_AT: 'lastStreamActivityAt',
  /** watchdog 定时器句柄(防重装叠加) */
  STALL_WATCHDOG_INTERVAL: 'stallWatchdogInterval',
  /** 前端运行时 response 分组 id */
  ACTIVE_STREAMING_RESPONSE_ID: 'activeStreamingResponseId',
  /** 当前 turn 起始时间,算 durationMs */
  TURN_STARTED_AT: 'turnStartedAt',
  /** 诊断 onStreamEnd 来源 */
  LAST_STREAM_END_SOURCE: 'lastStreamEndSource',
} as const;

export type BridgeStateKeyDef = (typeof BridgeStateKey)[keyof typeof BridgeStateKey];
