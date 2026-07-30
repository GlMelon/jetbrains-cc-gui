/**
 * bridge/types.ts
 *
 * 下行总线(Java → 前端)的核心类型契约。
 *
 * 背景:原架构中后端通过 executeJavaScript 直接调用约 75 个 window.xxx 具名回调,
 * 无统一契约。本次归一化收敛为单一入口 window.__bridge.dispatch(type, payloadJson),
 * 业务模块通过 bridgeHub.subscribe(type, handler) 自取所需。
 *
 * 详见 plan: typed-booping-newt.md
 */





/**
 * hub.subscribe / subscribePassthrough 的处理器签名。
 *
 * 收到的 value 是**原始 payloadJson 字符串**(逐字节等价于旧 window.xxx(json) 的入参),
 * 总线不做 JSON 解析(透明字符串管道)。原因:现有回调 payload 约定不统一(多数 JSON,
 * 少数裸字符串/多参数),自动解析会破坏裸字符串回调的功能等价性。订阅者按各自约定解析。
 * 无 payload 时为 undefined。
 */
export type BridgeListener<P = unknown> = (value: P) => void;

/**
 * hub.subscribe 返回的取消订阅函数。
 */
export type Unsubscribe = () => void;




