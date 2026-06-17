/**
 * bridge/hub.ts
 *
 * bridgeHub — 下行总线(Java → 前端)的前端核心。
 *
 * 职责:
 *  1. 维护 type → listeners(Set) 的订阅注册表(广播事件)。
 *  2. 维护 streaming 类的 passthrough 单 handler 直通通道(不广播、不拷贝)。
 *  3. 提供 request/response RPC 通道(requestId correlation + 超时)。
 *  4. 内置缓冲队列:前端"未就绪"时入队,"就绪"握手后回放 —— 替代散落的
 *     window.__pendingXxx 槽。
 *  5. 暴露 window.__bridge.dispatch 作为后端 executeJavaScript 的唯一入口。
 *
 * 双轨兼容(迁移期基石):
 *  - compat.registerLegacyCallback(legacyName, type) 把旧 window.<legacyName> 注册为
 *    dispatch(type) 的转发别名,使后端继续调用旧 window.xxx 也能命中新总线。
 *  - 迁移期间新旧路径并存、行为一致,任一回调可单点切换/回退。
 *
 * 性能红线(streaming):
 *  - onContentDelta/onThinkingDelta 等高频回调必须走 passthrough,保持 ref-first +
 *    rAF/startTransition 节流。passthrough 不经 Set 广播、不做 Array.from、不二次 JSON.parse。
 *
 * 底层复用并演进 utils/createCallbackChannel.ts 的 Set<Listener> + try/catch + name 前缀日志模式。
 *
 * 详见 plan: typed-booping-newt.md。
 */

import type {
  BridgeListener,
  Unsubscribe,
} from './types';

type RawListeners = Set<BridgeListener>;

/**
 * 缓冲队列条目。复刻原 pendingSlots 的两种语义:
 *  - 单值覆盖型(last-wins):多次入队只保留最后一条。对应原 __pendingXxx 单值槽。
 *  - 数组累加型(全保留):每次入队都追加,按序消费。对应原 __pending*DialogRequests。
 *
 * 默认 last-wins(绝大多数);dialog 类在 compat 层标注为 accumulate。
 */
interface BufferedEntry {
  type: string;
  payloadJson?: string;
}

class BridgeHub {
  /** type → 监听器集合(广播事件 + passthrough 复用此结构,passthrough 仅允许 1 个监听器) */
  private readonly listenersByType = new Map<string, RawListeners>();
  /** type → passthrough 单 handler(直通模式,与 listenersByType 互斥使用) */
  private readonly passthroughByType = new Map<string, BridgeListener>();
  /** 标记某 type 使用 passthrough 模式(订阅时校验) */
  private readonly passthroughTypes = new Set<string>();

  /**
   * 前端是否就绪。默认 true —— dispatch 同步派发(逐字节等价于旧 window.xxx(json) 的同步行为),
   * 保证迁移期功能等价与既有单测的同步断言。
   *
   * 缓冲(未就绪入队、握手后回放)是 Phase 2 取代 pendingSlots 的可选机制:届时由
   * 显式的「早期捕获」注册将相关 type 切换到缓冲模式。Phase 1 不启用缓冲,保持同步语义。
   */
  private ready = true;
  /** 缓冲队列:就绪前的 dispatch 按到达顺序保留全部(消费时按 type 语义取最后/全部)。 */
  private readonly buffer: BufferedEntry[] = [];

  /** RPC: requestId → cancel 回调(仅供 reset 时批量取消,不参与路由)。 */
  private readonly pendingRequests = new Map<string, () => void>();

  // -------------------------------------------------------------------------
  // 订阅(广播 + passthrough)
  // -------------------------------------------------------------------------

  /**
   * 订阅一个广播事件。同一 type 可多订阅者。handler 收到的是已解析的 payload。
   * 自动 ensureInstalled(若该 type 已注册 legacy 兼容别名)。
   */
  subscribe<P = unknown>(type: string, listener: BridgeListener<P>): Unsubscribe {
    if (this.passthroughTypes.has(type)) {
      console.error(`[bridge] Cannot subscribe (broadcast) to passthrough type: ${type}`);
      return () => {};
    }
    let set = this.listenersByType.get(type);
    if (!set) {
      set = new Set();
      this.listenersByType.set(type, set);
    }
    set.add(listener as BridgeListener);
    return () => {
      const s = this.listenersByType.get(type);
      if (s) {
        s.delete(listener as BridgeListener);
        if (s.size === 0) {
          this.listenersByType.delete(type);
        }
      }
    };
  }

  /**
   * 订阅一个 passthrough(直通)通道,整条 type 仅允许 1 个 handler。
   * 用于高频流式 delta:不广播、不拷贝,dispatch 直接同步调用该 handler。
   */
  subscribePassthrough<P = unknown>(type: string, handler: BridgeListener<P>): Unsubscribe {
    if (this.listenersByType.has(type)) {
      console.error(`[bridge] Cannot register passthrough on type with broadcast listeners: ${type}`);
      return () => {};
    }
    this.passthroughTypes.add(type);
    this.passthroughByType.set(type, handler as BridgeListener);
    return () => {
      this.passthroughByType.delete(type);
      this.passthroughTypes.delete(type);
    };
  }

  // -------------------------------------------------------------------------
  // RPC(request / response)
  // -------------------------------------------------------------------------

  /**
   * 发起一个 RPC 请求并等待对应 response。
   *
   * 约定:
   *  - 请求名 type(如 'file_path.resolve')经上行桥发出,payload 注入 __requestId。
   *  - 后端回包通过 dispatch(responseType, { __requestId, ... }) 传回。
   *  - hub 按 responseType + __requestId 匹配并 resolve。
   *
   * @param responseType 用于匹配回包的 type(默认 `${type}.resolved`)
   * @returns 超时则 reject(Error);bridge reset 则 reject(取消)
   */
  request<P = unknown>(
    type: string,
    payload: unknown,
    options: { timeoutMs?: number; responseType?: string } = {},
  ): Promise<P> {
    const timeoutMs = options.timeoutMs ?? 5000;
    const responseType = options.responseType ?? `${type}.resolved`;
    const requestId = makeRequestId(type);

    return new Promise<P>((resolve, reject) => {
      let settled = false;
      const finish = (action: () => void) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeoutId);
        unsubscribe();
        action();
      };

      // 注册一次性监听器:订阅者收到原始 payloadJson 字符串(RPC 回包恒为 JSON,内部解析)。
      const unsubscribe = this.subscribe(responseType, (raw: unknown) => {
        const parsed = parsePayload(typeof raw === 'string' ? raw : undefined);
        if (extractRequestId(parsed) !== requestId) return;
        finish(() => resolve(parsed as P));
      });

      const timeoutId = setTimeout(() => {
        finish(() => reject(new Error(`[bridge] RPC timeout: ${type} (${requestId}) after ${timeoutMs}ms`)));
      }, timeoutMs);

      // 记录到 pendingRequests 仅供 reset 时批量取消(不参与路由)。
      this.pendingRequests.set(requestId, () => finish(() => reject(new Error(`[bridge] RPC cancelled: ${requestId}`))));

      // 通过上行桥发出请求(请求方向 = 前端 → Java)。envelope {type, content}。
      sendUpstream(type, { ...asRecord(payload), __requestId: requestId });
    });
  }

  // -------------------------------------------------------------------------
  // dispatch(后端唯一入口)
  // -------------------------------------------------------------------------

  /**
   * 后端 executeJavaScript 调用 window.__bridge.dispatch 的实现。
   * 流程:就绪检查 → (未就绪入缓冲)→ 解析 payload → 路由(passthrough/broadcast/rpc-response)。
   *
   * 性能:passthrough 与 broadcast 路径对 payload 只做一次 JSON.parse;
   * RPC response 路径同理。无额外拷贝。
   */
  dispatch(type: string, payloadJson?: string): void {
    if (!this.ready) {
      this.buffer.push({ type, payloadJson });
      return;
    }
    this.deliver(type, payloadJson);
  }

  /** 实际派发(已就绪)。抽出以便就绪后回放复用。 */
  private deliver(type: string, payloadJson?: string): void {
    // 关键:总线是「透明字符串管道」,原样传递 payloadJson,不做 JSON 解析。
    // 原因:现有回调 payload 约定不统一 —— 多数为 JSON 字串(JSON.parse),但
    // onModeChanged/onModelChanged 等接收裸字符串,onModelConfirmed 甚至两参数。
    // 若 dispatch 自动 parse,裸字符串回调会被破坏(功能不等价)。
    // 因此订阅者收到的就是原 window.xxx(json) 中的那个 json 字符串(逐字节等价)。
    // 单次解析优化是后续归一化目标,本次迁移不引入。

    // passthrough 直通(高频流式:单 handler,不经广播集合)
    const passthrough = this.passthroughByType.get(type);
    if (passthrough) {
      invokeListener(type, passthrough, payloadJson);
      return;
    }

    // 广播(含 RPC response:type = responseType,request() 内部已注册一次性监听者)
    const set = this.listenersByType.get(type);
    if (set && set.size > 0) {
      // 快照避免迭代中订阅/取消订阅造成跳过或重复。size>0 才转 Array,降低空集开销。
      const snapshot = Array.from(set);
      for (let i = 0; i < snapshot.length; i++) {
        invokeListener(type, snapshot[i], payloadJson);
      }
    }
    // 无监听者:静默丢弃(对应原 typeof window.xxx !== 'function' 时的行为)。
  }

  // -------------------------------------------------------------------------
  // 握手 / 就绪 / 缓冲回放
  // -------------------------------------------------------------------------

  /**
   * 标记前端就绪并回放缓冲队列。
   * 由 frontend_ready 流程触发(main.tsx 握手)。回放按到达顺序;单值型语义由消费侧
   * handler 自然体现(后到的覆盖先到的状态),数组型(dialog)由 type 在消费侧累积。
   *
   * 注意:缓冲条目里同一 type 可能多次出现。回放时**全部**依次 deliver,与原
   * pendingSlots 的「单值只保留最后、数组保留全部」语义一致 —— 单值 handler
   * 多次调用等同覆盖,数组 handler 多次调用等同追加。
   */
  markReady(): void {
    if (this.ready) return;
    this.ready = true;
    const queued = this.buffer.splice(0);
    for (let i = 0; i < queued.length; i++) {
      const entry = queued[i];
      this.deliver(entry.type, entry.payloadJson);
    }
  }

  /**
   * 重置就绪状态(webview reload/recreate 时)。已有缓冲被清空(新生命周期重新缓冲)。
   * 对应原 setFrontendReady(false)。
   */
  reset(): void {
    this.ready = false;
    this.buffer.length = 0;
    // 待处理 RPC 全部取消(避免泄漏)。cancel 回调内部会 clearTimeout + 拒绝 promise。
    this.pendingRequests.forEach((cancel) => {
      try { cancel(); } catch { /* ignore */ }
    });
    this.pendingRequests.clear();
  }

  /** 是否已就绪(诊断用)。 */
  isReady(): boolean {
    return this.ready;
  }

  // -------------------------------------------------------------------------
  // 测试 / 诊断
  // -------------------------------------------------------------------------

  /** 获取某 type 当前广播监听者数量(诊断/测试)。 */
  listenerCount(type: string): number {
    return this.listenersByType.get(type)?.size ?? 0;
  }

  /** 当前缓冲队列长度(诊断/测试)。 */
  bufferedCount(): number {
    return this.buffer.length;
  }
}

/**
 * 单例。main.tsx 在 React 挂载前安装到 window.__bridge。
 */
export const bridgeHub = new BridgeHub();

// ===========================================================================
// window.__bridge 安装
// ===========================================================================

/**
 * 在 window 上安装 __bridge.dispatch 入口。必须在 React 挂载之前调用,
 * 以便后端早期推送(React 挂载前)能被缓冲。
 * 幂等。
 */
export function installBridge(): void {
  if (typeof window === 'undefined') return;
  if (window.__bridge) return;
  window.__bridge = {
    dispatch: (type: string, payloadJson?: string) => bridgeHub.dispatch(type, payloadJson),
  };
}

// ===========================================================================
// 内部工具
// ===========================================================================

/** 单监听器调用,带 try/catch 与 type 前缀日志(沿用 createCallbackChannel 模式)。 */
function invokeListener(type: string, listener: BridgeListener, value: unknown): void {
  try {
    listener(value);
  } catch (error) {
    console.error(`[bridge:${type}] Listener threw:`, error);
  }
}

/** 解析 payload JSON。空/undefined → undefined。失败 → undefined(兼容旧行为,静默)。 */
function parsePayload(payloadJson?: string): unknown {
  if (payloadJson === undefined || payloadJson === '') return undefined;
  try {
    return JSON.parse(payloadJson);
  } catch {
    return undefined;
  }
}

/** 从已解析 payload 取 RPC requestId(若存在)。 */
function extractRequestId(parsed: unknown): string | undefined {
  if (parsed && typeof parsed === 'object') {
    const rid = (parsed as { __requestId?: unknown }).__requestId;
    if (typeof rid === 'string' && rid.length > 0) return rid;
  }
  return undefined;
}

/** 生成 RPC requestId。 */
function makeRequestId(type: string): string {
  // Math.random 不可用于工作流脚本,但本模块运行在 webview 浏览器主线程,不受此限。
  // 用 type 前缀 + 随机串,便于日志关联。
  const rand = Math.random().toString(36).slice(2, 10);
  const stamp = Date.now().toString(36);
  return `${type}:${stamp}:${rand}`;
}

/** 把任意 payload 规整为 Record(上行桥需要对象)。 */
function asRecord(payload: unknown): Record<string, unknown> {
  if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
    return payload as Record<string, unknown>;
  }
  return { value: payload };
}

/**
 * 上行发送(RPC 请求方向)。上行桥(sendToJava)不在本次重构范围,
 * 这里通过动态 import 引用 utils/bridge 的 sendToJava 以保持解耦、避免循环依赖。
 * 若 sendToJava 尚未就绪,降级为静默丢弃(与原 resolveFilePath 行为一致,由调用方超时兜底)。
 *
 * 注意:Vite/ESM 环境无 require(),必须用动态 import()。RPC 为异步语义,fire-and-forget 上行
 * 配合 request() 的超时兜底,行为可预测。
 */
function sendUpstream(type: string, payload: Record<string, unknown>): void {
  if (typeof window === 'undefined' || !window.sendToJava) {
    // 上行桥不可用:RPC 将由 request() 的超时拒绝。
    return;
  }
  // 直接调用 window.sendToJava(envelope {type, content}),避免引入 utils/bridge 形成静态循环依赖。
  // payload 序列化方式与 utils/bridge.sendToJava 一致(JSON.stringify)。
  try {
    window.sendToJava(JSON.stringify({ type, content: JSON.stringify(payload) }));
  } catch {
    // 序列化失败:静默,由 request() 超时兜底。
  }
}
