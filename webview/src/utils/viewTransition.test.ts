import { afterEach, describe, expect, it, vi } from 'vitest';
import { runWithViewTransition, supportsViewTransition } from './viewTransition';

describe('viewTransition', () => {
  afterEach(() => {
    // jsdom/happy-dom 默认无 startViewTransition，恢复该默认
    delete (document as Partial<Document> & { startViewTransition?: unknown }).startViewTransition;
    vi.restoreAllMocks();
  });

  it('VT 不可用时退化为同步执行 update', () => {
    expect(supportsViewTransition()).toBe(false);
    const update = vi.fn();
    runWithViewTransition(update);
    expect(update).toHaveBeenCalledTimes(1);
  });

  it('VT 可用且未要求减少动画时走 startViewTransition，update 在其回调内执行一次', () => {
    const spy = vi.fn((cb: () => void) => {
      cb();
      return { finished: Promise.resolve() };
    });
    Object.defineProperty(document, 'startViewTransition', { configurable: true, value: spy });

    const update = vi.fn();
    runWithViewTransition(update);

    expect(spy).toHaveBeenCalledTimes(1);
    expect(update).toHaveBeenCalledTimes(1);
  });

  it('prefers-reduced-motion 时即便 VT 可用也降级，不触发 startViewTransition', () => {
    const spy = vi.fn(() => ({ finished: Promise.resolve() }));
    Object.defineProperty(document, 'startViewTransition', { configurable: true, value: spy });
    // 强制 matchMedia 上报 reduce
    const matchMediaSpy = vi.fn().mockReturnValue({ matches: true } as MediaQueryList);
    Object.defineProperty(window, 'matchMedia', { configurable: true, value: matchMediaSpy });

    const update = vi.fn();
    runWithViewTransition(update);

    expect(spy).not.toHaveBeenCalled();
    expect(update).toHaveBeenCalledTimes(1);
  });
});
