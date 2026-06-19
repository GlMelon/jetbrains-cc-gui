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
