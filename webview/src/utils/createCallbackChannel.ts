/**
 * 通用回调通道工厂。
 *
 * 消除 *Capabilities.ts 文件中的重复样板：
 * - Set<Listener> 管理
 * - emit（带 try/catch + 日志前缀）
 * - subscribe(listener) => unsubscribe
 * - 可选的 installWindowDispatcher + ensureInstalled
 */

export interface CallbackChannel<T> {
  /** 添加监听器，返回取消订阅函数 */
  subscribe: (listener: (value: T) => void) => () => void;
  /** 通知所有监听器 */
  emit: (value: T) => void;
  /** 确保 dispatcher 已安装（如果配置了的话） */
  ensureInstalled: () => void;
}

export interface CallbackChannelOptions {
  /** 日志前缀，用于 console.error */
  name: string;
  /** 可选的 window dispatcher 安装函数 */
  installDispatcher?: () => void;
  /** 可选的检查 dispatcher 是否已安装的函数 */
  isInstalled?: () => boolean;
}

/**
 * 创建一个回调通道。
 *
 * @example
 * ```ts
 * const channel = createCallbackChannel<string>({
 *   name: 'myFeature',
 *   installDispatcher: () => {
 *     window.myCallback = (json: string) => channel.emit(json);
 *   },
 *   isInstalled: () => typeof window !== 'undefined' && !!window.myCallback,
 * });
 *
 * // 订阅
 * const unsubscribe = channel.subscribe((value) => console.log(value));
 *
 * // 发送
 * channel.emit('hello');
 *
 * // 取消订阅
 * unsubscribe();
 * ```
 */
export function createCallbackChannel<T>(
  options: CallbackChannelOptions
): CallbackChannel<T> {
  const { name, installDispatcher, isInstalled } = options;
  const listeners = new Set<(value: T) => void>();

  const emit = (value: T): void => {
    // Snapshot to avoid mutation during iteration
    Array.from(listeners).forEach((listener) => {
      try {
        listener(value);
      } catch (error) {
        console.error(`[${name}] Listener threw:`, error);
      }
    });
  };

  const subscribe = (listener: (value: T) => void): (() => void) => {
    ensureInstalled();
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  };

  const ensureInstalled = (): void => {
    if (typeof window === 'undefined') return;
    if (isInstalled && isInstalled()) return;
    installDispatcher?.();
  };

  return { subscribe, emit, ensureInstalled };
}
