/**
 * Runtime provider capabilities subscriber registry.
 *
 * The Java bridge invokes a single set of `window.update*` callbacks to deliver
 * provider-list and active-provider updates. Multiple React components
 * (Settings hook, RuntimeProviderSelect, etc.) need to react to those events.
 *
 * Registering a single dispatcher on `window` and routing events through a
 * subscriber Set keeps behavior deterministic regardless of mount order, and
 * avoids the previous "chain of overridden window callbacks" pattern, which
 * produced non-deterministic teardown when more than one consumer was alive.
 */

import { createCallbackChannel } from './createCallbackChannel';

type ProviderListListener = (json: string) => void;
type ActiveProviderListener = (json: string) => void;

// 创建 4 个回调通道
const providerListChannel = createCallbackChannel<string>({
  name: 'runtimeProvider:providerList',
});

const activeProviderChannel = createCallbackChannel<string>({
  name: 'runtimeProvider:activeProvider',
});

const codexProviderListChannel = createCallbackChannel<string>({
  name: 'runtimeProvider:codexProviderList',
});

const activeCodexProviderChannel = createCallbackChannel<string>({
  name: 'runtimeProvider:activeCodexProvider',
});

/**
 * Installs (or re-installs) the single dispatcher on `window`. Safe to call
 * multiple times — calling it during a test reset, for example, simply
 * re-attaches the dispatcher.
 */
export function installRuntimeProviderDispatchers(): void {
  window.updateProviders = (json: string) => {
    providerListChannel.emit(json);
  };

  window.updateActiveProvider = (json: string) => {
    activeProviderChannel.emit(json);
  };

  window.updateCodexProviders = (json: string) => {
    codexProviderListChannel.emit(json);
  };

  window.updateActiveCodexProvider = (json: string) => {
    activeCodexProviderChannel.emit(json);
  };
}

const ensureInstalled = (): void => {
  if (typeof window === 'undefined') return;
  if (window.updateProviders && window.updateActiveProvider
      && window.updateCodexProviders && window.updateActiveCodexProvider) {
    return;
  }
  installRuntimeProviderDispatchers();
};

// 自动安装 dispatchers
ensureInstalled();

export function subscribeProviderList(listener: ProviderListListener): () => void {
  return providerListChannel.subscribe(listener);
}

export function subscribeActiveProvider(listener: ActiveProviderListener): () => void {
  return activeProviderChannel.subscribe(listener);
}

export function subscribeCodexProviderList(listener: ProviderListListener): () => void {
  return codexProviderListChannel.subscribe(listener);
}

export function subscribeActiveCodexProvider(listener: ActiveProviderListener): () => void {
  return activeCodexProviderChannel.subscribe(listener);
}
