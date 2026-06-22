import { DOWNSTREAM, UPSTREAM } from '../generated/protocol';
import { sendAction, subscribeEvent } from '../bridge/typed';
import type { ModelInfo } from '../components/ChatInputBox/types';
import { CLAUDE_MODELS, CODEX_MODELS, DEFAULT_CONTEXT_WINDOW, ONE_MILLION_CONTEXT_WINDOW, getClaudeRoleFromModelId, normalizeClaudeModelId, strip1MContextSuffix } from '../components/ChatInputBox/types';
import type { CodexCustomModel, CodexProviderConfig, ProviderType } from '../types/provider';

export interface ModelRegistryItem extends ModelInfo {
  provider: ProviderType;
  role?: 'sonnet' | 'opus' | 'fable' | 'haiku';
  actualModel?: string;
  supports1MContext?: boolean;
  enabled?: boolean;
  readOnly?: boolean;
}

export interface ModelRegistryPayload {
  items: ModelRegistryItem[];
}

const modelRegistryListeners = new Set<() => void>();

const DEFAULT_MODEL_REGISTRY: ModelRegistryPayload = {
  items: [
    ...CLAUDE_MODELS.map((model) => ({
      ...model,
      provider: 'claude' as const,
      supports1MContext: !model.id.toLowerCase().includes('haiku'),
      enabled: true,
    })),
    ...CODEX_MODELS.map((model) => toCodexRegistryItem(model)),
  ],
};

let currentRegistry: ModelRegistryPayload = DEFAULT_MODEL_REGISTRY;

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
  subscribeEvent(DOWNSTREAM.MODEL_REGISTRY, (json) => {
    const parsed = parseModelRegistryPayload(json);
    if (!parsed) {
      return;
    }
    publishModelRegistry(parsed);
  });
  subscribeEvent(DOWNSTREAM.MODEL_REGISTRY_UPDATED, (json) => {
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
  sendAction(UPSTREAM.GET_MODEL_REGISTRY);
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

/**
 * 将 Claude 模型 ID 解析为最终用于 selectedClaudeModel 的稳定 ID。
 *
 * 自定义模型(如 mimo-v2.5)的 id 不是 `claude-role-*` 形式,
 * `normalizeClaudeModelId` 会错误地把它归一化为 `claude-role-sonnet`,从而吞掉
 * 合法的自定义模型——下拉框点不中、刷新后丢失。这里先查当前 registry:
 * 若 registry 中存在该 id(provider=claude 且启用),则保留原始 id;否则才回退到
 * 归一化(兼容旧 role 体系,以及后端在 registry 加载前推送的真实模型名)。
 *
 * 调用点:`model.confirmed` / `model.changed` / `session.runtime_state` 等后端回调,
 * 以及 `useModelStatePersistence` 的持久化恢复。
 */
export function resolveClaudeModelId(modelId: string | undefined | null): string {
  const stripped = strip1MContextSuffix(modelId);
  const inRegistry = currentRegistry.items.some(
    (model) => model.provider === 'claude' && model.enabled !== false && model.id === stripped,
  );
  if (inRegistry) {
    return stripped;
  }
  return normalizeClaudeModelId(stripped);
}

/**
 * 解析模型对应的 Claude role,用于按 role 统一判断能力(如 reasoning effort)。
 *
 * 内置 `claude-role-*` 模型直接由 id 推导 role;自定义模型(如 mimo-v2.5)
 * 则读取当前 registry 中的 role 字段——即用户在“新增模型”时为该自定义模型
 * 选择的角色(sonnet/opus/fable/haiku)。这样自定义模型与内置模型走同一套
 * 能力判定逻辑(ReasoningSelect 显示/级别)。
 *
 * 返回 null 表示既非内置 role 模型、也不在 registry 中(无法判定 role)。
 */
export function resolveClaudeRoleForModel(
  modelId: string | undefined | null,
): 'sonnet' | 'opus' | 'fable' | 'haiku' | null {
  const stripped = strip1MContextSuffix(modelId);
  if (!stripped) {
    return null;
  }
  const builtinRole = getClaudeRoleFromModelId(stripped);
  if (builtinRole) {
    return builtinRole;
  }
  const item = currentRegistry.items.find(
    (model) => model.provider === 'claude' && model.enabled !== false && model.id === stripped,
  );
  return item?.role ?? null;
}

export function __setModelRegistryForTests(registry: ModelRegistryPayload): void {
  publishModelRegistry(registry);
}

export function resetModelRegistryForTests(): void {
  publishModelRegistry({
    items: DEFAULT_MODEL_REGISTRY.items.map((item) => ({ ...item })),
  });
}

export function getModelsForProvider(provider: string): ModelInfo[] {
  const normalizedProvider = provider === 'codex' ? 'codex' : 'claude';
  return currentRegistry.items
    .filter((model) => model.provider === normalizedProvider && model.enabled !== false)
    .map((model) => ({
      id: strip1MContextSuffix(model.id),
      label: model.label || strip1MContextSuffix(model.id),
      description: formatRegistryDescription(model),
      contextWindow: model.contextWindow,
      supports1MContext: model.supports1MContext,
    }));
}

export function createCodexCatalogModels(
  provider: Pick<CodexProviderConfig, 'customModels' | 'modelCatalog' | 'configToml'> | null | undefined,
): ModelRegistryItem[] {
  if (!provider) {
    return [];
  }

  const catalog = normalizeCodexCatalog(provider.modelCatalog ?? provider.customModels);
  if (catalog.length > 0) {
    return catalog.map(toCodexRegistryItem);
  }

  const currentModel = extractCodexCurrentModel(provider.configToml);
  if (!currentModel) {
    return [];
  }

  return [toCodexRegistryItem({
    id: currentModel,
    label: currentModel,
    contextWindow: DEFAULT_CONTEXT_WINDOW,
  })];
}

function normalizeCodexCatalog(catalog: CodexCustomModel[] | undefined): ModelInfo[] {
  if (!Array.isArray(catalog)) {
    return [];
  }
  return catalog
    .map((model) => ({
      id: typeof model.id === 'string' ? model.id.trim() : '',
      label: typeof model.label === 'string' && model.label.trim() ? model.label.trim() : model.id?.trim(),
      description: typeof model.description === 'string' ? model.description : undefined,
      contextWindow: typeof model.contextWindow === 'number' && model.contextWindow > 0
        ? model.contextWindow
        : DEFAULT_CONTEXT_WINDOW,
    }))
    .filter((model) => model.id);
}

function extractCodexCurrentModel(configToml: string | undefined): string {
  if (typeof configToml !== 'string') {
    return '';
  }
  const match = configToml.match(/^\s*model\s*=\s*["']([^"']+)["']\s*$/m);
  return match?.[1]?.trim() ?? '';
}

function toCodexRegistryItem(model: ModelInfo): ModelRegistryItem {
  const contextWindow = model.contextWindow ?? DEFAULT_CONTEXT_WINDOW;
  return {
    ...model,
    provider: 'codex',
    contextWindow,
    supports1MContext: model.supports1MContext ?? contextWindow >= ONE_MILLION_CONTEXT_WINDOW,
    enabled: true,
  };
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
      const rawContextWindow = typeof obj.contextWindow === 'number' ? obj.contextWindow : undefined;
      if (!id || !provider) {
        continue;
      }
      const contextWindow = rawContextWindow !== undefined && rawContextWindow > 0
        ? rawContextWindow
        : DEFAULT_CONTEXT_WINDOW;
      const label = typeof obj.label === 'string' && obj.label.trim() ? obj.label.trim() : id;
      const role = parseClaudeRole(obj.role);
      const actualModel = typeof obj.actualModel === 'string' && obj.actualModel.trim()
        ? obj.actualModel.trim()
        : undefined;
      items.push({
        id,
        provider,
        role,
        label,
        actualModel,
        description: typeof obj.description === 'string' ? obj.description : undefined,
        contextWindow,
        supports1MContext: obj.supports1MContext === true,
        enabled: obj.enabled !== false,
        readOnly: obj.readOnly === true,
      });
    }
    return items.length > 0 ? { items } : null;
  } catch {
    return null;
  }
}

function parseClaudeRole(value: unknown): ModelRegistryItem['role'] | undefined {
  if (value === 'sonnet' || value === 'opus' || value === 'fable' || value === 'haiku') {
    return value;
  }
  return undefined;
}

function formatRegistryDescription(model: ModelRegistryItem): string | undefined {
  if (model.provider === 'claude') {
    const parts = [
      model.role ? capitalize(model.role) : undefined,
      model.actualModel,
    ].filter(Boolean);
    if (parts.length > 0) {
      return parts.join(' · ');
    }
  }
  return model.description;
}

function capitalize(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}
