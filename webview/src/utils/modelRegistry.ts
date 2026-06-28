import { DOWNSTREAM, UPSTREAM } from '../generated/protocol';
import type { ModelRegistryPayloadWire } from '../generated/protocol';
import { sendAction, subscribeEvent } from '../bridge/typed';
import type { ModelInfo, ReasoningEffort } from '../components/ChatInputBox/types';
import { DEFAULT_CONTEXT_WINDOW, ONE_MILLION_CONTEXT_WINDOW, REASONING_LEVELS, strip1MContextSuffix } from '../components/ChatInputBox/types';
import type { CodexCustomModel, CodexProviderConfig, ProviderType } from '../types/provider';

/**
 * C1:字段结构来自后端 SSOT 生成的 {@link ModelRegistryPayloadWire}(wire 字段集 == 后端
 * serialize 实际产出,见 ModelRegistryPayloadField 后端守门)。此处仅收窄 3 个字段的业务类型
 * (provider→ProviderType / role→角色联合 / supportedReasoningLevels→ReasoningEffort),
 * 不再手写 id/label/contextWindow/actualModel/supports1MContext/enabled/readOnly
 * (统一来自 wire)。
 */
export interface ModelRegistryItem extends ModelRegistryPayloadWire {
  provider: ProviderType;
  role?: 'sonnet' | 'opus' | 'fable' | 'haiku';
  /** A2:后端权威下发的支持 reasoning 级别(派生自 role;仅 claude + role 已知时下发)。 */
  supportedReasoningLevels?: readonly ReasoningEffort[];
}

export interface ModelRegistryPayload {
  items: ModelRegistryItem[];
}

const modelRegistryListeners = new Set<() => void>();

// A1(2026-06-23):DEFAULT_MODEL_REGISTRY 本地表已删除——模型真相源唯一为后端
// MODEL_REGISTRY 下发(ReadOnlyDefaultModels → ModelRegistryService.serialize)。
// currentRegistry 初始为空,空态由消费方显示 loading,绝不回退本地表(总则一·禁止前端 fallback)。
let currentRegistry: ModelRegistryPayload = { items: [] };

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
 * 将外部 model id 规整为前端 selectedClaudeModel 应存的稳定 ID。
 *
 * A3(2026-06-23):不再前端归一化。原逻辑在 registry 未命中时调用
 * `normalizeClaudeModelId` 把任意 id 归一为 `claude-role-sonnet`(业务归一化),
 * 已移除——归一化下沉,前端只透传。现仅剥离 [1m] 容量后缀:registry 命中的模型
 * id 本就是后端权威下发的 role id;未命中的 id 原样保留,由后端 session 下发的
 * role id 经 MODEL_SELECTION 回填纠正(见 useModelProviderState 订阅)。
 *
 * 调用点:`model.confirmed` / `model.changed` / `session.runtime_state` 等后端回调,
 * 以及 `useModelStatePersistence` 的持久化恢复。
 */
export function resolveClaudeModelId(modelId: string | undefined | null): string {
  return strip1MContextSuffix(modelId);
}

/**
 * 三 provider(Claude/Codex/OpenCode)归一化 SSOT。
 *
 * 收敛前端多处 inline `provider === 'codex' ? 'codex' : 'claude'` 逻辑——该写法把
 * opencode 误归一为 claude(session.runtime_state / model.confirmed 下行时 opencode 被前端
 * 当 claude 处理,模型 setter 走 setSelectedClaudeModel 而非 setSelectedOpenCodeModel)。
 * 统一未知/缺失值回退 claude(与既有 inline 行为对齐;parseModelRegistryPayload 的"未知→null
 * 过滤"语义不同,不复用本函数)。
 */
export function normalizeProvider(raw: string | null | undefined): ProviderType {
  if (raw === 'codex') {
    return 'codex';
  }
  if (raw === 'opencode') {
    return 'opencode';
  }
  return 'claude';
}

/**
 * 解析模型对应的 Claude role,用于按 role 统一判断能力(如 reasoning effort)。
 *
 * A3(2026-06-23):纯读 registry 的 role 字段(后端 ModelConfig.role 权威下发,
 * 内置 claude-role-* 与自定义模型统一处理)。不再从前端 id 字符串离线推导 role
 * (原 getClaudeRoleFromModelId builtin 分支移除)——registry 加载前返回 null,
 * ReasoningSelect 据此显示空态,registry 下发后回填。
 *
 * 返回 null 表示不在 registry 中或 registry 未加载(无法判定 role)。
 */
export function resolveClaudeRoleForModel(
  modelId: string | undefined | null,
): 'sonnet' | 'opus' | 'fable' | 'haiku' | null {
  const stripped = strip1MContextSuffix(modelId);
  if (!stripped) {
    return null;
  }
  const item = currentRegistry.items.find(
    (model) => model.provider === 'claude' && model.enabled !== false && model.id === stripped,
  );
  return item?.role ?? null;
}

/**
 * A2:读取模型后端权威下发的支持 reasoning 级别(派生自 role,仅 claude + role 已知时下发)。
 *
 * ReasoningSelect 据此渲染可选项,不再在前端按 role 硬编码级别规则。
 * 返回 null 表示不在 registry 中 / registry 未加载 / 该模型无 reasoning 能力(Codex 等)。
 */
export function getModelSupportedReasoningLevels(
  modelId: string | undefined | null,
): readonly ReasoningEffort[] | null {
  const stripped = strip1MContextSuffix(modelId);
  if (!stripped) {
    return null;
  }
  const item = currentRegistry.items.find(
    (model) => model.provider === 'claude' && model.enabled !== false && model.id === stripped,
  );
  return item?.supportedReasoningLevels ?? null;
}

export function __setModelRegistryForTests(registry: ModelRegistryPayload): void {
  publishModelRegistry(registry);
}

export function resetModelRegistryForTests(): void {
  publishModelRegistry({ items: [] });
}

export function getModelsForProvider(provider: string): ModelInfo[] {
  const normalizedProvider = normalizeProvider(provider);
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
    readOnly: false,
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
      const provider = obj.provider === 'codex' ? 'codex' : obj.provider === 'claude' ? 'claude' : obj.provider === 'opencode' ? 'opencode' : null;
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
        supportedReasoningLevels: parseReasoningLevels(obj.supportedReasoningLevels),
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

/** 合法 reasoning 级别 id 集合(派生自 REASONING_LEVELS SSOT,过滤后端下发值)。 */
const VALID_REASONING_LEVEL_IDS = new Set<string>(REASONING_LEVELS.map((level) => level.id));

/**
 * 解析后端下发的 supportedReasoningLevels 数组,过滤非法值。
 * 返回 undefined 表示未下发 / 为空(parseModelRegistryPayload 据此省略该能力)。
 */
function parseReasoningLevels(value: unknown): ReasoningEffort[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }
  const levels = value.filter(
    (entry): entry is ReasoningEffort => typeof entry === 'string' && VALID_REASONING_LEVEL_IDS.has(entry),
  );
  return levels.length > 0 ? levels : undefined;
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
