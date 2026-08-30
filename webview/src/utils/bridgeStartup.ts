import {
  BOOTSTRAP_SCOPE,
  bootstrapLifecycle,
  type BootstrapLifecycleController,
} from './bootstrapLifecycle';

const BRIDGE_FAST_RETRY_ATTEMPTS = 50;
const BRIDGE_FAST_RETRY_INTERVAL_MS = 100;
const BRIDGE_SLOW_RETRY_INTERVAL_MS = 1000;

export function waitForBridge(
  callback: () => void,
  lifecycle: BootstrapLifecycleController = bootstrapLifecycle,
): () => void {
  let attempt = 0;
  let completed = false;

  const clearReadyCallback = () => {
    if (window.__ccgOnBridgeReady === check) {
      delete window.__ccgOnBridgeReady;
    }
  };

  const cancel = () => {
    completed = true;
    clearReadyCallback();
  };

  const token = lifecycle.start(BOOTSTRAP_SCOPE.BRIDGE_READY, cancel);
  if (!token) {
    return () => {};
  }
  const ownerToken = token;

  function check() {
    if (completed || !lifecycle.isActive(BOOTSTRAP_SCOPE.BRIDGE_READY, ownerToken)) {
      return;
    }
    attempt++;
    if (window.sendToJava) {
      completed = true;
      lifecycle.finish(BOOTSTRAP_SCOPE.BRIDGE_READY, ownerToken);
      clearReadyCallback();
      callback();
      return;
    }

    if (attempt === BRIDGE_FAST_RETRY_ATTEMPTS) {
      console.warn('[Main] Bridge startup is delayed; continuing low-frequency retries');
    }
    const retryInterval = attempt < BRIDGE_FAST_RETRY_ATTEMPTS
      ? BRIDGE_FAST_RETRY_INTERVAL_MS
      : BRIDGE_SLOW_RETRY_INTERVAL_MS;
    lifecycle.schedule(BOOTSTRAP_SCOPE.BRIDGE_READY, check, retryInterval, ownerToken);
  }

  window.__ccgOnBridgeReady = check;
  check();
  return () => lifecycle.cancel(BOOTSTRAP_SCOPE.BRIDGE_READY, ownerToken);
}
