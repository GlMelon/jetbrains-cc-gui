import { beforeEach, describe, expect, it, vi } from 'vitest';
// 注意:bridgeHub 为模块单例。测试用 reset()/clear() 在用例间复位状态。
// 总线语义:透明字符串管道 —— 订阅者收到原始 payloadJson 字符串(逐字节等价于旧 window.xxx(json))。
import { bridgeHub, installBridge } from '../../../src/bridge/hub';
import { bridgeState } from '../../../src/bridge/store';
import {
  registerLegacyAlias,
  unregisterLegacyAlias,
} from '../../../src/bridge/compat';

describe('bridgeHub — broadcast subscribe/dispatch (raw-string conduit)', () => {
  beforeEach(() => {
    bridgeHub.reset();
    bridgeHub.markReady();
  });

  it('delivers the raw payloadJson string verbatim (no parsing)', () => {
    const spy = vi.fn();
    bridgeHub.subscribe('usage.update', spy);
    bridgeHub.dispatch('usage.update', JSON.stringify({ a: 42 }));
    expect(spy).toHaveBeenCalledTimes(1);
    // 收到的是原始字符串,不是解析后的对象
    expect(spy).toHaveBeenCalledWith('{"a":42}');
  });

  it('delivers raw non-JSON strings unchanged (legacy raw-arg callbacks)', () => {
    // 模拟 onModeReceived('plan'):后端传裸字符串,总线不解析,订阅者原样收到。
    const spy = vi.fn();
    bridgeHub.subscribe('mode.received', spy);
    bridgeHub.dispatch('mode.received', 'plan');
    expect(spy).toHaveBeenCalledWith('plan');
  });

  it('supports multiple subscribers on the same type', () => {
    const a = vi.fn();
    const b = vi.fn();
    const un = bridgeHub.subscribe('mode.changed', a);
    bridgeHub.subscribe('mode.changed', b);
    bridgeHub.dispatch('mode.changed', '"plan"');
    expect(a).toHaveBeenCalledWith('"plan"');
    expect(b).toHaveBeenCalledWith('"plan"');
    un();
    bridgeHub.dispatch('mode.changed', '"default"');
    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledTimes(2);
  });

  it('unsubscribe removes the listener', () => {
    const spy = vi.fn();
    const un = bridgeHub.subscribe('x', spy);
    un();
    bridgeHub.dispatch('x', '1');
    expect(spy).not.toHaveBeenCalled();
    expect(bridgeHub.listenerCount('x')).toBe(0);
  });

  it('passes empty string and undefined verbatim (raw conduit)', () => {
    const spy = vi.fn();
    bridgeHub.subscribe('ping', spy);
    bridgeHub.dispatch('ping', '');
    bridgeHub.dispatch('ping', undefined);
    expect(spy).toHaveBeenNthCalledWith(1, '');
    expect(spy).toHaveBeenNthCalledWith(2, undefined);
  });

  it('isolates listener errors from siblings', () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const boom = () => { throw new Error('boom'); };
    const ok = vi.fn();
    bridgeHub.subscribe('e', boom);
    bridgeHub.subscribe('e', ok);
    expect(() => bridgeHub.dispatch('e', '1')).not.toThrow();
    expect(ok).toHaveBeenCalledTimes(1);
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('silently drops dispatches with no listeners', () => {
    expect(() => bridgeHub.dispatch('nobody.listening', '1')).not.toThrow();
  });
});

describe('bridgeHub — passthrough (streaming) mode', () => {
  beforeEach(() => {
    bridgeHub.reset();
    bridgeHub.markReady();
  });

  it('delivers raw string directly without broadcast overhead', () => {
    const spy = vi.fn();
    bridgeHub.subscribePassthrough('stream.content_delta', spy);
    bridgeHub.dispatch('stream.content_delta', '"hi"');
    expect(spy).toHaveBeenCalledWith('"hi"');
  });

  it('rejects broadcast subscribe on a passthrough type', () => {
    bridgeHub.subscribePassthrough('stream.x', vi.fn());
    const spy = vi.fn();
    bridgeHub.subscribe('stream.x', spy);
    bridgeHub.dispatch('stream.x', '"v"');
    expect(spy).not.toHaveBeenCalled();
  });
});

describe('bridgeHub — buffering until markReady (pending replacement)', () => {
  beforeEach(() => {
    bridgeHub.reset();
    // 故意不 markReady:模拟前端未就绪
  });

  it('buffers dispatches before ready and replays in order on markReady', () => {
    const spy = vi.fn();
    bridgeHub.subscribe('cfg', spy);
    bridgeHub.dispatch('cfg', '"first"');
    bridgeHub.dispatch('cfg', '"second"');
    expect(spy).not.toHaveBeenCalled();
    expect(bridgeHub.bufferedCount()).toBe(2);

    bridgeHub.markReady();
    expect(spy).toHaveBeenNthCalledWith(1, '"first"');
    expect(spy).toHaveBeenNthCalledWith(2, '"second"');
    expect(bridgeHub.bufferedCount()).toBe(0);
  });

  it('delivers immediately after ready (no buffering)', () => {
    bridgeHub.markReady();
    const spy = vi.fn();
    bridgeHub.subscribe('late', spy);
    bridgeHub.dispatch('late', '"v"');
    expect(spy).toHaveBeenCalledWith('"v"');
  });

  it('reset clears buffer', () => {
    bridgeHub.dispatch('cfg', '"x"');
    bridgeHub.reset();
    expect(bridgeHub.bufferedCount()).toBe(0);
  });
});

describe('bridgeHub — RPC request/response', () => {
  beforeEach(() => {
    bridgeHub.reset();
    bridgeHub.markReady();
    window.sendToJava = vi.fn();
  });

  it('rejects on timeout when no response arrives', async () => {
    await expect(
      bridgeHub.request('file_path.resolve', { path: '/x' }, { timeoutMs: 20 }),
    ).rejects.toThrow(/timeout/);
  });

  it('resolves with parsed RPC payload when a matching response is dispatched', async () => {
    window.sendToJava = vi.fn((msg: string) => {
      const env = JSON.parse(msg);
      const content = JSON.parse(env.content);
      const requestId = content.__requestId;
      // 模拟后端回包:type = '<req>.resolved',payload 带 __requestId
      setTimeout(() => {
        bridgeHub.dispatch('file_path.resolve.resolved', JSON.stringify({ __requestId: requestId, resolvedPath: '/resolved' }));
      }, 0);
    });

    const result = await bridgeHub.request<{ resolvedPath: string }>(
      'file_path.resolve',
      { path: '/x' },
      { timeoutMs: 500 },
    );
    // RPC 回包恒为 JSON,hub 内部解析后 resolve(parsed)
    expect(result.resolvedPath).toBe('/resolved');
  });
});

describe('installBridge — window.__bridge entry', () => {
  it('installs a dispatch entry on window (idempotent)', () => {
    delete (window as unknown as Record<string, unknown>).__bridge;
    installBridge();
    expect(typeof window.__bridge?.dispatch).toBe('function');
    const first = window.__bridge;
    installBridge(); // 再次安装应幂等
    expect(window.__bridge).toBe(first);
  });

  it('window.__bridge.dispatch forwards to hub (raw)', () => {
    bridgeHub.reset();
    bridgeHub.markReady();
    installBridge();
    const spy = vi.fn();
    bridgeHub.subscribe('toast.show', spy);
    window.__bridge!.dispatch('toast.show', '"hi"');
    expect(spy).toHaveBeenCalledWith('"hi"');
  });
});

describe('compat — legacy alias forwarding', () => {
  beforeEach(() => {
    bridgeHub.reset();
    bridgeHub.markReady();
  });

  it('legacy window.<name>(json) forwards to dispatch(type) with raw json', () => {
    const spy = vi.fn();
    bridgeHub.subscribe('toast.show', spy);
    registerLegacyAlias('addToast', 'toast.show');
    (window as unknown as { addToast: (j: string) => void }).addToast('"hi"');
    expect(spy).toHaveBeenCalledWith('"hi"');
    unregisterLegacyAlias('addToast');
  });
});

describe('BridgeStateStore — synchronous mutable flags', () => {
  beforeEach(() => {
    bridgeState.clear();
  });

  it('get/set/remove are synchronous', () => {
    expect(bridgeState.get('k')).toBeUndefined();
    bridgeState.set('k', 1);
    expect(bridgeState.get('k')).toBe(1);
    expect(bridgeState.has('k')).toBe(true);
    bridgeState.remove('k');
    expect(bridgeState.has('k')).toBe(false);
  });

  it('set returns the stored value', () => {
    expect(bridgeState.set('flag', true)).toBe(true);
  });
});
