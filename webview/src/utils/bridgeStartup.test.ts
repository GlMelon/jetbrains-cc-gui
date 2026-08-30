import { BootstrapLifecycleController } from './bootstrapLifecycle';
import { waitForBridge } from './bridgeStartup';

describe('bridge startup recovery', () => {
  let lifecycle: BootstrapLifecycleController;

  beforeEach(() => {
    vi.useFakeTimers();
    delete window.sendToJava;
    delete window.__ccgOnBridgeReady;
    lifecycle = new BootstrapLifecycleController();
    lifecycle.bindToWindow();
  });

  afterEach(() => {
    lifecycle.dispose();
    delete window.sendToJava;
    delete window.__ccgOnBridgeReady;
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('bootstraps once when the bridge appears after the fast retry window', () => {
    const callback = vi.fn();
    waitForBridge(callback, lifecycle);
    const bridgeReady = window.__ccgOnBridgeReady;

    vi.advanceTimersByTime(6000);
    expect(callback).not.toHaveBeenCalled();

    window.sendToJava = vi.fn();
    bridgeReady?.();
    bridgeReady?.();
    vi.runOnlyPendingTimers();

    expect(callback).toHaveBeenCalledTimes(1);
    expect(window.__ccgOnBridgeReady).toBeUndefined();
  });

  it('keeps only the latest bridge bootstrap active', () => {
    const staleCallback = vi.fn();
    const currentCallback = vi.fn();

    waitForBridge(staleCallback, lifecycle);
    waitForBridge(currentCallback, lifecycle);
    window.sendToJava = vi.fn();
    window.__ccgOnBridgeReady?.();
    vi.runOnlyPendingTimers();

    expect(staleCallback).not.toHaveBeenCalled();
    expect(currentCallback).toHaveBeenCalledTimes(1);
  });

  it('does not let a stale cancellation stop the replacement bootstrap', () => {
    const staleCallback = vi.fn();
    const currentCallback = vi.fn();

    const cancelStale = waitForBridge(staleCallback, lifecycle);
    waitForBridge(currentCallback, lifecycle);
    cancelStale();
    window.sendToJava = vi.fn();
    window.__ccgOnBridgeReady?.();

    expect(staleCallback).not.toHaveBeenCalled();
    expect(currentCallback).toHaveBeenCalledTimes(1);
  });
  it('cancels bridge polling when the page is hidden', () => {
    const callback = vi.fn();
    waitForBridge(callback, lifecycle);

    window.dispatchEvent(new Event('pagehide'));
    window.sendToJava = vi.fn();
    vi.runOnlyPendingTimers();

    expect(callback).not.toHaveBeenCalled();
    expect(window.__ccgOnBridgeReady).toBeUndefined();
  });
});
