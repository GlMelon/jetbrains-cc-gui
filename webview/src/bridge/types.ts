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
 * 下行事件的语义类别,决定 hub 内部的承载与传递方式。
 * - event:       标准广播(usage/mode/provider/...),走 Set<Listener>,可多订阅者。
 * - rpc:         请求-响应(onFilePathResolved),需 requestId correlation + 超时。
 * - streaming:   高频流式增量(onContentDelta 等),走 passthrough 直通单 handler,
 *                不广播、不拷贝,保持 ref-first + rAF/startTransition 节流(性能红线)。
 * - bootstrap:   启动期一次性 / DOM 副作用(font/theme/language),多为非 React 状态。
 */
export type BridgeEventKind = 'event' | 'rpc' | 'streaming' | 'bootstrap';

/**
 * 事件目录条目。新增下行事件时在 events/ 目录登记一行即可。
 */
export interface BridgeEventDef<P = unknown> {
  /** 事件 type 字符串,后端 dispatchEvent 与前端 subscribe 共用 */
  type: string;
  /** 语义类别 */
  kind: BridgeEventKind;
  /** payload 类型(订阅者拿到的已解析对象) */
  payload?: P;
}

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

/**
 * window 上安装的总线入口对象。
 *
 * dispatch 由后端 executeJavaScript 调用:
 *   window.__bridge.dispatch('usage.update', '{"percentage":42}')
 *
 * 内部负责:type 路由 → 已注册 handler 调用;未就绪时入缓冲队列,握手后回放。
 */
export interface BridgeDispatch {
  /**
   * 后端下行唯一入口。
   * @param type        事件 type(见 events/ 目录)
   * @param payloadJson payload 的 JSON 字符串;空字符串/undefined 视为无 payload(undefined)
   */
  dispatch(type: string, payloadJson?: string): void;
}

/**
 * 完整的 window.__bridge 形态:对外暴露 dispatch(供后端),内部状态由 bridgeHub 持有。
 */
export type WindowBridge = BridgeDispatch;
