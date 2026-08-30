export const BOOTSTRAP_SCOPE = {
  BRIDGE_READY: 'bridge-ready',
  IDE_THEME: 'ide-theme',
  VCONSOLE_POSITION: 'vconsole-position',
} as const;

export type BootstrapScope = (typeof BOOTSTRAP_SCOPE)[keyof typeof BOOTSTRAP_SCOPE];
export type BootstrapLifecycleToken = symbol;

type ScopeCleanup = () => void;
type ScopeRegistration = {
  token: BootstrapLifecycleToken;
  cleanup: ScopeCleanup;
};
type ScopeTimer = {
  token: BootstrapLifecycleToken;
  timer: ReturnType<typeof window.setTimeout>;
};

/**
 * Owns startup retries and delayed bootstrap work by logical scope.
 * Starting the same scope replaces its previous timer and cleanup, so every
 * bootstrap category has at most one active instance.
 */
export class BootstrapLifecycleController {
  private readonly timers = new Map<BootstrapScope, ScopeTimer>();
  private readonly registrations = new Map<BootstrapScope, ScopeRegistration>();
  private disposed = false;
  private teardownWindow: Window | null = null;

  start(scope: BootstrapScope, cleanup: ScopeCleanup = () => {}): BootstrapLifecycleToken | null {
    if (this.disposed) {
      return null;
    }
    this.cancel(scope);
    const token = Symbol(scope);
    this.registrations.set(scope, { token, cleanup });
    return token;
  }

  isActive(scope: BootstrapScope, token?: BootstrapLifecycleToken): boolean {
    if (this.disposed) {
      return false;
    }
    const registration = this.registrations.get(scope);
    return registration !== undefined && (token === undefined || registration.token === token);
  }

  schedule(
    scope: BootstrapScope,
    callback: () => void,
    delayMs: number,
    token?: BootstrapLifecycleToken,
  ): void {
    const registration = this.registrations.get(scope);
    if (!registration || (token !== undefined && registration.token !== token)) {
      return;
    }
    const ownerToken = registration.token;
    this.clearTimer(scope, ownerToken);
    const timer = window.setTimeout(() => {
      const currentTimer = this.timers.get(scope);
      if (currentTimer?.token === ownerToken) {
        this.timers.delete(scope);
      }
      if (this.isActive(scope, ownerToken)) {
        callback();
      }
    }, delayMs);
    this.timers.set(scope, { token: ownerToken, timer });
  }

  finish(scope: BootstrapScope, token?: BootstrapLifecycleToken): void {
    if (!this.isActive(scope, token)) {
      return;
    }
    const ownerToken = this.registrations.get(scope)?.token;
    if (!ownerToken) {
      return;
    }
    this.clearTimer(scope, ownerToken);
    this.registrations.delete(scope);
  }

  cancel(scope: BootstrapScope, token?: BootstrapLifecycleToken): void {
    const registration = this.registrations.get(scope);
    if (!registration || (token !== undefined && registration.token !== token)) {
      return;
    }
    this.clearTimer(scope, registration.token);
    this.registrations.delete(scope);
    registration.cleanup();
  }

  bindToWindow(target: Window = window): void {
    if (this.disposed || this.teardownWindow === target) {
      return;
    }
    this.unbindWindowTeardown();
    this.teardownWindow = target;
    target.addEventListener('beforeunload', this.handleWindowTeardown, { once: true });
    target.addEventListener('pagehide', this.handleWindowTeardown, { once: true });
  }

  dispose(): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    for (const scope of [...this.registrations.keys()]) {
      this.cancel(scope);
    }
    this.timers.forEach(({ timer }) => window.clearTimeout(timer));
    this.timers.clear();
    this.unbindWindowTeardown();
  }

  private readonly handleWindowTeardown = () => {
    this.dispose();
  };

  private clearTimer(scope: BootstrapScope, token?: BootstrapLifecycleToken): void {
    const scopeTimer = this.timers.get(scope);
    if (scopeTimer && (token === undefined || scopeTimer.token === token)) {
      window.clearTimeout(scopeTimer.timer);
      this.timers.delete(scope);
    }
  }

  private unbindWindowTeardown(): void {
    if (!this.teardownWindow) {
      return;
    }
    this.teardownWindow.removeEventListener('beforeunload', this.handleWindowTeardown);
    this.teardownWindow.removeEventListener('pagehide', this.handleWindowTeardown);
    this.teardownWindow = null;
  }
}

export const bootstrapLifecycle = new BootstrapLifecycleController();

export function installBootstrapLifecycle(): void {
  bootstrapLifecycle.bindToWindow();
  if (import.meta.hot) {
    import.meta.hot.dispose(() => bootstrapLifecycle.dispose());
  }
}
