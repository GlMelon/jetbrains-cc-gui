import { DOWNSTREAM, UPSTREAM } from '../generated/protocol';
import type { ModelRegistryPayloadWire } from '../generated/protocol';
import { sendAction, subscribeEvent } from '../bridge/typed';
import type { ModelInfo, ReasoningEffort } from '../components/ChatInputBox/types';
import { ONE_MILLION_CONTEXT_WINDOW, REASONING_LEVELS, strip1MContextSuffix, getDefaultContextWindowForProvider } from '../components/ChatInputBox/types';
import type { CodexCustomModel, CodexProviderConfig, ProviderType } from '../types/provider';

/**
 * C1:字段结构来自后端 SSOT 生成的 {@link ModelRegistryPayloadWire}(wire 字段集 == 后端
 * serialize 实际产出,见 ModelRegistryPayloadField 后端守门)。此处仅收窄 3 个字段的业务类型
 * (provider→ProviderType / role→角色联合 / supportedReasoningLevels→ReasoningEffort),
 * 不再手写 id/label/contextWindow/actualModel/supports1MContext/enabled/readOnly
 * (统一来自 wire)。
 */
export interface ModelRegistryItem extends Omit<ModelRegistryPayloadWire, 'identifier'> {
  /** Absent only on unsaved local settings drafts; backend registry entries always provide it. */
  identifier?: string;
  provider: ProviderType;
  role?: 'sonnet' | 'opus' | 'fable' | 'haiku';
  /**
   * 后端权威下发的支持 reasoning 级别(有序;按 provider + 模型前缀规则派生)。
   * 所有有 reasoning 能力的 provider 的条目都带此字段;字段缺失 = 后端判定该模型
   * 无 reasoning 能力(dsh/minimax 的条目不下发)。
   */
  supportedReasoningLevels?: readonly ReasoningEffort[];
}

export interface ModelRegistryPayload {
  items: ModelRegistryItem[];
  /**
   * root 级 provider 默认 reasoning 档位(后端下发,含 codex/grok/kimi/pi/omp/opencode
   * 六家;不含 claude/dsh/minimax)。模型条目未命中 registry 时的回退档位来源。
   */
  providerDefaults?: Record<string, readonly ReasoningEffort[]>;
}

const modelRegistryListeners = new Set<() => void>();

// A1(2026-06-23):DEFAULT_MODEL_REGISTRY 本地表已删除——模型真相源唯一为后端
// MODEL_REGISTRY 下发(ReadOnlyDefaultModels → ModelRegistryService.serialize)。
// currentRegistry 初始为空,空态由消费方显示 loading,绝不回退本地表(总则一·禁止前端 fallback)。
let currentRegistry: ModelRegistryPayload = { items: [] };

let subscriptionInitialized = false;

function publishModelRegistry(registry: ModelRegistryPayload): void {
  currentRegistry = registry;
  modelRegistryListeners.forEach((listener) => listener());
}

function ensureModelRegistrySubscription(): void {
  if (subscriptionInitialized || typeof window === 'undefined') {
    return;
  }
  subscriptionInitialized = true;
  // 85ccb80e "简化 bridge 类型" 时误删此订阅接线,致共享 currentRegistry 永不填充
  // → 聊天模型列表恒空 "No model configured"(设置弹窗因有独立订阅仍正常)。此处还原。
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
  if (raw === 'codex') return 'codex';
  if (raw === 'opencode') return 'opencode';
  if (raw === 'grok') return 'grok';
  if (raw === 'kimi') return 'kimi';
  if (raw === 'pi') return 'pi';
  // omp/dsh:v0.5.4 合并新增的 CLI-only provider,漏白名单会被错标 claude——
  // MODEL_SELECTION 载荷带 omp 模型回灌时前端 setCurrentProvider('claude') 把
  // 跨供应商模型写进 claude 槽位(与后端 DefaultModelCapabilityResolver 同类缺陷)。
  if (raw === 'omp') return 'omp';
  if (raw === 'dsh') return 'dsh';
  return 'claude';
}



/**
 * 读取模型条目后端权威下发的支持 reasoning 级别。
 *
 * 返回 null 表示条目不在 registry 中 / registry 未加载 / 条目未下发该字段(无 reasoning
 * 能力)。需要区分「条目在但无字段(已知无能力)」与「条目不在(未知)」的调用方,
 * 请用 {@link resolveReasoningLevels}。
 *
 * @param provider 传入时按 provider 过滤条目;省略时不限 provider 按 id 命中。
 */
export function getModelSupportedReasoningLevels(
  modelId: string | undefined | null,
  provider?: string,
): readonly ReasoningEffort[] | null {
  const item = findEnabledRegistryItem(modelId, provider);
  return item?.supportedReasoningLevels ?? null;
}

/**
 * 读取 root 级 providerDefaults 中某 provider 的默认 reasoning 档位。
 * 返回 null 表示该 provider 无默认档位下发(provider 无 reasoning 能力 / registry 未加载)。
 */
export function getProviderDefaultReasoningLevels(
  provider: string | undefined | null,
): readonly ReasoningEffort[] | null {
  if (!provider) {
    return null;
  }
  return currentRegistry.providerDefaults?.[provider] ?? null;
}

/**
 * 解析当前选中模型的 reasoning 档位(后端权威,三态):
 * 1. registry 条目命中 → 条目的 supportedReasoningLevels;条目在但字段未下发 =
 *    后端判定该模型无 reasoning 能力,返回空数组(已知无档位);
 * 2. 条目未命中 → providerDefaults[provider](provider 级默认档位);
 * 3. 均无 → null(档位未知:registry 未加载 / 模型与 provider 均无下发)。
 *    未知不等于无权——调用方不得据此钳制或改写持久化值。
 */
export function resolveReasoningLevels(
  provider: string | undefined,
  modelId: string | undefined | null,
): readonly ReasoningEffort[] | null {
  const item = findEnabledRegistryItem(modelId, provider);
  if (item) {
    return item.supportedReasoningLevels ?? [];
  }
  return getProviderDefaultReasoningLevels(provider);
}

function findEnabledRegistryItem(
  modelId: string | undefined | null,
  provider?: string,
): ModelRegistryItem | undefined {
  const stripped = strip1MContextSuffix(modelId);
  if (!stripped) {
    return undefined;
  }
  return currentRegistry.items.find(
    (model) => model.enabled !== false && model.id === stripped
      && (!provider || model.provider === provider),
  );
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
    .filter((model) => model.provider === normalizedProvider && model.enabled !== false && Boolean(model.identifier))
    .map((model) => ({
      id: strip1MContextSuffix(model.id),
      identifier: model.identifier as string,
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
    contextWindow: getDefaultContextWindowForProvider('codex'),
  })];
}

type LocalCatalogModel = Omit<ModelInfo, 'identifier'>;

function normalizeCodexCatalog(catalog: CodexCustomModel[] | undefined): LocalCatalogModel[] {
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
        : getDefaultContextWindowForProvider('codex'),
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

function toCodexRegistryItem(model: LocalCatalogModel): ModelRegistryItem {
  const contextWindow = model.contextWindow ?? getDefaultContextWindowForProvider('codex');
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
      const identifier = typeof obj.identifier === 'string' ? obj.identifier.trim() : '';
      const provider = obj.provider === 'codex' ? 'codex' : obj.provider === 'claude' ? 'claude' : obj.provider === 'opencode' ? 'opencode' : obj.provider === 'grok' ? 'grok' : obj.provider === 'kimi' ? 'kimi' : obj.provider === 'pi' ? 'pi' : null;
      const rawContextWindow = typeof obj.contextWindow === 'number' ? obj.contextWindow : undefined;
      if (!id || !identifier || !provider) {
        continue;
      }
      const contextWindow = rawContextWindow !== undefined && rawContextWindow > 0
        ? rawContextWindow
        : getDefaultContextWindowForProvider(provider);
      const label = typeof obj.label === 'string' && obj.label.trim() ? obj.label.trim() : id;
      const role = parseClaudeRole(obj.role);
      const actualModel = typeof obj.actualModel === 'string' && obj.actualModel.trim()
        ? obj.actualModel.trim()
        : undefined;
      items.push({
        id,
        identifier,
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
    const providerDefaults = parseProviderDefaults(
      (parsed as { providerDefaults?: unknown }).providerDefaults,
    );
    if (items.length === 0) {
      return null;
    }
    return providerDefaults ? { items, providerDefaults } : { items };
  } catch {
    return null;
  }
}

/**
 * 解析 root 级 providerDefaults 对象({ "<provider>": ["low",...] }),过滤非法档位值。
 * 空值 / 非对象 / 全部条目非法时返回 undefined(payload 据此省略该字段)。
 */
function parseProviderDefaults(
  value: unknown,
): Record<string, readonly ReasoningEffort[]> | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return undefined;
  }
  const result: Record<string, readonly ReasoningEffort[]> = {};
  for (const [provider, levels] of Object.entries(value as Record<string, unknown>)) {
    if (!provider) {
      continue;
    }
    const parsed = parseReasoningLevels(levels);
    if (parsed) {
      result[provider] = parsed;
    }
  }
  return Object.keys(result).length > 0 ? result : undefined;
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
