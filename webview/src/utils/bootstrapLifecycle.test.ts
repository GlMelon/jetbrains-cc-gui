import {
  BOOTSTRAP_SCOPE,
  BootstrapLifecycleController,
} from './bootstrapLifecycle';

describe('BootstrapLifecycleController', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('replaces an existing scope before scheduling the next instance', () => {
    const lifecycle = new BootstrapLifecycleController();
    const staleCleanup = vi.fn();
    const staleCallback = vi.fn();
    const currentCallback = vi.fn();

    lifecycle.start(BOOTSTRAP_SCOPE.IDE_THEME, staleCleanup);
    lifecycle.schedule(BOOTSTRAP_SCOPE.IDE_THEME, staleCallback, 100);
    lifecycle.start(BOOTSTRAP_SCOPE.IDE_THEME);
    lifecycle.schedule(BOOTSTRAP_SCOPE.IDE_THEME, currentCallback, 100);
    vi.advanceTimersByTime(100);

    expect(staleCleanup).toHaveBeenCalledTimes(1);
    expect(staleCallback).not.toHaveBeenCalled();
    expect(currentCallback).toHaveBeenCalledTimes(1);
    lifecycle.dispose();
  });

  it('cancels every active scope on page teardown', () => {
    const lifecycle = new BootstrapLifecycleController();
    const bridgeCleanup = vi.fn();
    const themeCleanup = vi.fn();
    const bridgeCallback = vi.fn();
    const themeCallback = vi.fn();
    lifecycle.bindToWindow();

    lifecycle.start(BOOTSTRAP_SCOPE.BRIDGE_READY, bridgeCleanup);
    lifecycle.schedule(BOOTSTRAP_SCOPE.BRIDGE_READY, bridgeCallback, 100);
    lifecycle.start(BOOTSTRAP_SCOPE.IDE_THEME, themeCleanup);
    lifecycle.schedule(BOOTSTRAP_SCOPE.IDE_THEME, themeCallback, 100);

    window.dispatchEvent(new Event('beforeunload'));
    vi.runOnlyPendingTimers();

    expect(bridgeCleanup).toHaveBeenCalledTimes(1);
    expect(themeCleanup).toHaveBeenCalledTimes(1);
    expect(bridgeCallback).not.toHaveBeenCalled();
    expect(themeCallback).not.toHaveBeenCalled();
    expect(lifecycle.isActive(BOOTSTRAP_SCOPE.BRIDGE_READY)).toBe(false);
    expect(lifecycle.isActive(BOOTSTRAP_SCOPE.IDE_THEME)).toBe(false);
  });
});