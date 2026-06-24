import { act, renderHook } from '@testing-library/react';
import { useScrollBehavior } from './useScrollBehavior';
import type { ClaudeMessage } from '../types';

interface HookProps {
  currentView: 'chat' | 'history' | 'settings';
  messages: ClaudeMessage[];
  loading: boolean;
  streamingActive: boolean;
}

const INITIAL_PROPS: HookProps = {
  currentView: 'history',
  messages: [] as ClaudeMessage[],
  loading: false,
  streamingActive: false,
};

let resizeObserverCallback: ResizeObserverCallback | null = null;

class ResizeObserverMock {
  constructor(callback: ResizeObserverCallback) {
    resizeObserverCallback = callback;
  }

  observe() {}

  disconnect() {}
}

function createScrollableContainer() {
  const container = document.createElement('div');
  let scrollHeightValue = 1000;
  const clientHeightValue = 400;
  let scrollTopValue = 600;

  Object.defineProperty(container, 'clientHeight', {
    configurable: true,
    get: () => clientHeightValue,
  });

  Object.defineProperty(container, 'scrollHeight', {
    configurable: true,
    get: () => scrollHeightValue,
  });

  Object.defineProperty(container, 'scrollTop', {
    configurable: true,
    get: () => scrollTopValue,
    set: (value: number) => {
      const maxScrollTop = Math.max(0, scrollHeightValue - clientHeightValue);
      scrollTopValue = Math.min(Math.max(0, value), maxScrollTop);
    },
  });

  return {
    container,
    getScrollTop: () => scrollTopValue,
    setScrollTop: (value: number) => {
      const maxScrollTop = Math.max(0, scrollHeightValue - clientHeightValue);
      scrollTopValue = Math.min(Math.max(0, value), maxScrollTop);
    },
    setScrollHeight: (value: number) => {
      scrollHeightValue = value;
    },
  };
}

describe('useScrollBehavior', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      // Defer via the fake-timer scheduler so the rAF callback runs on
      // runAllTimers(), matching the real browser's ASYNC rAF semantics.
      // Synchronous execution broke scheduleScrollToBottom's scheduledScrollRafRef
      // guard: `ref = rAF(cb)` would overwrite cb's `ref = null` because the mock
      // ran cb before the assignment completed, pinning ref to the return id and
      // permanently blocking subsequent scheduleScrollToBottom calls.
      return setTimeout(() => callback(0), 0) as unknown as number;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    vi.stubGlobal('ResizeObserver', ResizeObserverMock);
    resizeObserverCallback = null;
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('enables browser scroll anchoring after the user pauses auto-scroll with wheel up', () => {
    const { container } = createScrollableContainer();
    const { result, rerender } = renderHook((props: HookProps) => useScrollBehavior(props), {
      initialProps: INITIAL_PROPS,
    });

    act(() => {
      result.current.messagesContainerRef.current = container;
    });

    rerender({ ...INITIAL_PROPS, currentView: 'chat' });

    act(() => {
      vi.runAllTimers();
    });

    act(() => {
      container.dispatchEvent(new WheelEvent('wheel', { deltaY: -40 }));
    });

    expect(result.current.userPausedRef.current).toBe(true);
    expect(result.current.isUserAtBottomRef.current).toBe(false);
    expect(container.classList.contains('scroll-anchor-enabled')).toBe(true);
  });

  it('disables browser scroll anchoring once the user returns to the bottom', () => {
    const { container, setScrollTop, getScrollTop } = createScrollableContainer();
    const { result, rerender } = renderHook((props: HookProps) => useScrollBehavior(props), {
      initialProps: INITIAL_PROPS,
    });

    act(() => {
      result.current.messagesContainerRef.current = container;
    });

    rerender({ ...INITIAL_PROPS, currentView: 'chat' });

    act(() => {
      vi.runAllTimers();
      container.dispatchEvent(new WheelEvent('wheel', { deltaY: -20 }));
    });

    expect(container.classList.contains('scroll-anchor-enabled')).toBe(true);

    act(() => {
      setScrollTop(600);
      container.dispatchEvent(new WheelEvent('wheel', { deltaY: 20 }));
      vi.runAllTimers();
    });

    expect(result.current.userPausedRef.current).toBe(false);
    expect(result.current.isUserAtBottomRef.current).toBe(true);
    expect(container.classList.contains('scroll-anchor-enabled')).toBe(false);
    expect(getScrollTop()).toBe(600);
  });

  it('keeps following the bottom when content grows inside the last message without changing messages', () => {
    const { container, getScrollTop, setScrollTop, setScrollHeight } = createScrollableContainer();
    const root = document.createElement('div');
    const end = document.createElement('div');
    root.appendChild(end);
    container.appendChild(root);

    const { result, rerender } = renderHook((props: HookProps) => useScrollBehavior(props), {
      initialProps: INITIAL_PROPS,
    });

    act(() => {
      result.current.messagesContainerRef.current = container;
      result.current.messagesEndRef.current = end;
    });

    rerender({ ...INITIAL_PROPS, currentView: 'chat', messages: [{ type: 'assistant', content: 'task', timestamp: '2026-04-27T00:00:00.000Z' }] });

    act(() => {
      vi.runAllTimers();
    });

    // Initial: scrollHeight 1000, clientHeight 400 → max scrollTop 600.
    expect(getScrollTop()).toBe(600);

    act(() => {
      setScrollTop(600);
      // Simulate the real browser contract: ResizeObserver fires AFTER the
      // observed element has grown, so scrollHeight is already the new value
      // when the callback runs. The previous mock tied growth to a
      // getBoundingClientRect read that the layout-thrash refactor removed
      // (see useScrollBehavior.ts scrollToBottom comment), so growth is now
      // modelled here directly instead of as a side effect of geometry reads.
      setScrollHeight(1400);
      resizeObserverCallback?.([], {} as ResizeObserver);
      vi.runAllTimers();
    });

    // scrollHeight 1400, clientHeight 400 → max scrollTop 1000; auto-scroll pins it.
    expect(getScrollTop()).toBe(1000);
    expect(container.classList.contains('scroll-anchor-enabled')).toBe(false);
  });
});