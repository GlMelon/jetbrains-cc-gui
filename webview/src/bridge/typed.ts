import { bridgeHub } from './hub';
import type { Unsubscribe } from './types';
import type { DownstreamEvent, UpstreamAction } from '../generated/protocol';

export function sendAction(action: UpstreamAction, payload: unknown = ''): boolean {
  if (typeof window === 'undefined' || !window.sendToJava) {
    return false;
  }
  const content = typeof payload === 'string' ? payload : JSON.stringify(payload);
  try {
    window.sendToJava(JSON.stringify({ type: action, content }));
    return true;
  } catch {
    return false;
  }
}

export function subscribeEvent<T = unknown>(
  event: DownstreamEvent,
  listener: (payload: T) => void,
): Unsubscribe {
  return bridgeHub.subscribe(event, listener as (payload: unknown) => void);
}

/**
 * 订阅一个 passthrough(直通)通道,整条 type 仅允许 1 个 handler。
 * 用于高频流式 delta(stream.*):不广播、不拷贝,dispatch 直接同步调用 handler。
 * subscribePassthrough 的类型化薄包装(语义见 bridge/hub.ts)。
 */
export function subscribePassthroughEvent<T = unknown>(
  event: DownstreamEvent,
  listener: (payload: T) => void,
): Unsubscribe {
  return bridgeHub.subscribePassthrough(event, listener as (payload: unknown) => void);
}
