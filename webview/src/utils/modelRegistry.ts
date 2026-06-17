import { bridgeHub } from '../bridge';
import { sendBridgeEvent } from './bridge';
import type { ModelInfo } from '../components/ChatInputBox/types';
import { CLAUDE_MODELS, CODEX_MODELS, strip1MContextSuffix } from '../components/ChatInputBox/types';

export interface ModelRegistryItem extends ModelInfo {
  provider: 'claude' | 'codex';
  supports1MContext?: boolean;
  enabled?: boolean;
}

export interface ModelRegistryPayload {
  items: ModelRegistryItem[];
}

const modelRegistryListeners = new Set<() => void>();

let currentRegistry: ModelRegistryPayload = {
  items: [
    ...CLAUDE_MODELS.map((model) => ({
      ...model,
      provider: 'claude' as const,
      supports1MContext: !model.id.toLowerCase().includes('haiku'),
      enabled: true,
    })),
    ...CODEX_MODELS.map((model) => ({
      ...model,
      provider: 'codex' as const,
      supports1MContext: (model.contextWindow ?? 0) >= 1_000_000,
      enabled: true,
    })),
  ],
};

let subscribed = false;

function publishModelRegistry(registry: ModelRegistryPayload): void {
  currentRegistry = registry;
  modelRegistryListeners.forEach((listener) => listener());
}

export function ensureModelRegistrySubscription(): void {
  if (subscribed || typeof window === 'undefined') {
    return;
  }
  subscribed = true;
  bridgeHub.subscribe('model_registry', (json) => {
    const parsed = parseModelRegistryPayload(json);
    if (!parsed) {
      return;
    }
    publishModelRegistry(parsed);
  });
  bridgeHub.subscribe('model_registry_updated', (json) => {
    try {
      const data = typeof json === 'string' ? JSON.parse(json) : json;
      if (!data || typeof data !== 'object' || (data as { success?: boolean }).success !== true) {
        return;
      }
      const parsed = parseModelRegistryPayload((data as { registry?: unknown }).registry);
      if (!parsed) {
        return;
      }
      publishModelRegistry(parsed);
    } catch {
      // Ignore malformed update events; callers still receive backend errors separately.
    }
  });
}

export function requestModelRegistry(): void {
  ensureModelRegistrySubscription();
  sendBridgeEvent('get_model_registry');
}

export function subscribeModelRegistry(listener: () => void): () => void {
  ensureModelRegistrySubscription();
  modelRegistryListeners.add(listener);
  return () => {
    modelRegistryListeners.delete(listener);
  };
}

export function getModelRegistrySnapshot(): ModelRegistryPayload {
  ensureModelRegistrySubscription();
  return currentRegistry;
}

export function __setModelRegistryForTests(registry: ModelRegistryPayload): void {
  publishModelRegistry(registry);
}

export function getModelsForProvider(provider: string): ModelInfo[] {
  const normalizedProvider = provider === 'codex' ? 'codex' : 'claude';
  return currentRegistry.items
    .filter((model) => model.provider === normalizedProvider && model.enabled !== false)
    .map((model) => ({
      id: strip1MContextSuffix(model.id),
      label: model.label || strip1MContextSuffix(model.id),
      description: model.description,
      contextWindow: model.contextWindow,
      supports1MContext: model.supports1MContext,
    }));
}

export function parseModelRegistryPayload(raw: unknown): ModelRegistryPayload | null {
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!parsed || typeof parsed !== 'object' || !Array.isArray((parsed as { items?: unknown }).items)) {
      return null;
    }
    const items: ModelRegistryItem[] = [];
    for (const item of (parsed as { items: unknown[] }).items) {
      if (!item || typeof item !== 'object') {
        continue;
      }
      const obj = item as Record<string, unknown>;
      const id = typeof obj.id === 'string' ? obj.id.trim() : '';
      const provider = obj.provider === 'codex' ? 'codex' : obj.provider === 'claude' ? 'claude' : null;
      const contextWindow = typeof obj.contextWindow === 'number' ? obj.contextWindow : undefined;
      if (!id || !provider || !contextWindow || contextWindow <= 0) {
        continue;
      }
      const label = typeof obj.label === 'string' && obj.label.trim() ? obj.label.trim() : id;
      items.push({
        id,
        provider,
        label,
        description: typeof obj.description === 'string' ? obj.description : undefined,
        contextWindow,
        supports1MContext: obj.supports1MContext === true,
        enabled: obj.enabled !== false,
      });
    }
    return items.length > 0 ? { items } : null;
  } catch {
    return null;
  }
}
