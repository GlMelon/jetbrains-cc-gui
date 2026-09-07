import type { ModelInfo, PermissionMode } from '../../components/ChatInputBox/types';
import { PROVIDER_TYPE, type ProviderType } from '../../generated/protocol';

/**
 * Provider 分类注册表 —— 前端 provider 行为分组的唯一数据源。
 *
 * SSOT 链路:Java `ProviderType` 枚举 → `scripts/generate-protocol-types.mjs` →
 * `generated/protocol.ts` 的 `PROVIDER_TYPE` → 本表(`Record<ProviderType, …>` 穷尽键)。
 *
 * ⚠️ 穷尽守卫:后端新增 provider 而本表未归类时,`Record<ProviderType, …>` 的键缺失
 * 会直接编译报错 —— 不允许再出现「后端加枚举、前端 N 处名单漏改」。
 * 新 provider 接入步骤:改 Java 枚举 → 跑 generate-protocol-types.mjs → 在本表补一行
 * 分类 → 各消费点按维度自动生效,无需再改散落的硬编码名单。
 */
export interface ProviderClassification {
  /**
   * 模型目录动态下发(`get_cli_models` / channel listModels)。
   * claude 走后端 MODEL_REGISTRY 不在此列;codex 虽有后端管理但目录来自 config.toml 故为 true。
   */
  dynamicModels: boolean;
  /**
   * 无后端 provider 管理协议:供应商下拉只显示「原生 CLI 配置」固定项。
   * (omp/dsh 虽无专属管理界面,但走 claude 体系 GET_PROVIDERS 协议,故为 false。)
   */
  nativeConfigOnly: boolean;
  /** 有原生自动审批('auto' 直通);对齐后端 SessionSendService.resolveEffectivePermissionMode 降级白名单。 */
  nativeAutoApproval: boolean;
  /** OpenCode 内核家族:无 permission flag,权限由 opencode.json(allow/ask/deny)原生管控。 */
  opencodePermissionFamily: boolean;
  /** 参与 AI 辅助功能(commit AI / prompt enhancer;SDK-capable)。 */
  aiFeatureCapable: boolean;
  /** 默认上下文窗口(token;与后端 CommonConstants.getDefaultContextWindowForProvider 对齐)。 */
  defaultContextWindow: number;
}

export const PROVIDER_CLASSIFICATION: Record<ProviderType, ProviderClassification> = {
  [PROVIDER_TYPE.CLAUDE]: {
    dynamicModels: false,
    nativeConfigOnly: false,
    nativeAutoApproval: true,
    opencodePermissionFamily: false,
    aiFeatureCapable: true,
    defaultContextWindow: 200_000,
  },
  [PROVIDER_TYPE.CODEX]: {
    dynamicModels: true,
    nativeConfigOnly: false,
    nativeAutoApproval: true,
    opencodePermissionFamily: false,
    aiFeatureCapable: true,
    defaultContextWindow: 200_000,
  },
  [PROVIDER_TYPE.OPENCODE]: {
    dynamicModels: true,
    nativeConfigOnly: false,
    nativeAutoApproval: false,
    opencodePermissionFamily: true,
    aiFeatureCapable: true,
    defaultContextWindow: 200_000,
  },
  [PROVIDER_TYPE.GROK]: {
    dynamicModels: true,
    nativeConfigOnly: true,
    nativeAutoApproval: true,
    opencodePermissionFamily: true,
    aiFeatureCapable: false,
    defaultContextWindow: 256_000,
  },
  [PROVIDER_TYPE.KIMI]: {
    dynamicModels: true,
    nativeConfigOnly: true,
    nativeAutoApproval: false,
    opencodePermissionFamily: true,
    aiFeatureCapable: false,
    defaultContextWindow: 256_000,
  },
  [PROVIDER_TYPE.PI]: {
    dynamicModels: true,
    nativeConfigOnly: true,
    nativeAutoApproval: false,
    opencodePermissionFamily: true,
    aiFeatureCapable: false,
    defaultContextWindow: 200_000,
  },
  [PROVIDER_TYPE.OMP]: {
    dynamicModels: true,
    nativeConfigOnly: false,
    nativeAutoApproval: false,
    opencodePermissionFamily: false,
    aiFeatureCapable: false,
    defaultContextWindow: 200_000,
  },
  [PROVIDER_TYPE.DSH]: {
    dynamicModels: true,
    nativeConfigOnly: false,
    nativeAutoApproval: false,
    opencodePermissionFamily: false,
    aiFeatureCapable: false,
    defaultContextWindow: 128_000,
  },
  [PROVIDER_TYPE.MINIMAX]: {
    dynamicModels: true,
    nativeConfigOnly: true,
    nativeAutoApproval: false,
    opencodePermissionFamily: false,
    aiFeatureCapable: false,
    defaultContextWindow: 200_000,
  },
};

/** 全量 provider id(SSOT 派生,顺序 = Java 枚举声明序)。 */
export const ALL_PROVIDER_IDS: readonly ProviderType[] = Object.values(PROVIDER_TYPE);

function providersWhere(predicate: (c: ProviderClassification) => boolean): ReadonlySet<string> {
  return new Set(ALL_PROVIDER_IDS.filter((id) => predicate(PROVIDER_CLASSIFICATION[id])));
}

/**
 * Headless CLI providers that share Grok-style marker streaming (no npm SDK)
 * —— 即「无后端 provider 管理界面」的全量补集(派生自分类表)。
 */
export const CLI_ONLY_PROVIDERS: ReadonlySet<string> = providersWhere((c) => !c.aiFeatureCapable);

export function isCliOnlyProvider(providerId: string | null | undefined): boolean {
  return !!providerId && CLI_ONLY_PROVIDERS.has(providerId);
}

/**
 * 模型目录动态下发的 provider(消费 get_cli_models)。
 * claude 不在(catalog 走 MODEL_REGISTRY);codex 在(config.toml + model_catalog_json)。
 */
export const DYNAMIC_MODEL_PROVIDERS: ReadonlySet<string> = providersWhere((c) => c.dynamicModels);

/** RuntimeProviderSelect 供应商下拉只显示「原生 CLI 配置」固定项的 provider。 */
export const NATIVE_CONFIG_ONLY_PROVIDERS: ReadonlySet<string> = providersWhere((c) => c.nativeConfigOnly);

/**
 * Providers with a native auto-approval reviewer ('auto' mode). Aligned with the
 * backend degrade rule in SessionSendService.resolveEffectivePermissionMode:
 * auto downgrades to default everywhere except claude/codex/grok.
 */
export const NATIVE_AUTO_APPROVAL_PROVIDERS: ReadonlySet<string> = providersWhere((c) => c.nativeAutoApproval);

/** OpenCode 内核家族(无 permission flag,权限由 opencode.json 原生管控)。 */
export const OPENCODE_PERMISSION_FAMILY: ReadonlySet<string> = providersWhere((c) => c.opencodePermissionFamily);

/** 参与 AI 辅助功能(commit AI / prompt enhancer)的 provider。 */
export const AI_FEATURE_CAPABLE_PROVIDERS: ReadonlySet<string> = providersWhere((c) => c.aiFeatureCapable);

/** Provider 默认上下文窗口(与后端 CommonConstants 对齐;穷尽表)。 */
export const PROVIDER_DEFAULT_CONTEXT_WINDOW: Record<ProviderType, number> = Object.fromEntries(
  ALL_PROVIDER_IDS.map((id) => [id, PROVIDER_CLASSIFICATION[id].defaultContextWindow]),
) as Record<ProviderType, number>;

/**
 * Static OMP model roles — used only to reconcile snapshots persisted before
 * roles became dynamic. The live role list comes from useOmpRoles() (dynamic
 * listModels roles, falling back to these same three when unloaded).
 */
export const OMP_ROLE_MODEL_IDS: ReadonlySet<string> = new Set(['smol', 'slow', 'plan']);

/**
 * Maps an omp model id to its mode: an id present in `roles` maps to the
 * same-named mode, everything else ('auto' or any catalog model) maps to
 * 'default'. Pass useOmpRoles() — it already falls back to the static
 * smol/slow/plan entries when no dynamic roles have loaded.
 */
export function ompModeForModelId(modelId: string, roles: ModelInfo[]): PermissionMode {
  return roles.some((role) => role.id === modelId) ? (modelId as PermissionMode) : 'default';
}

/**
 * Plan mode and provider-native auto review are not exposed for headless CLI providers,
 * so they are coerced to default. The legacy autoEdit alias is migrated to acceptEdits
 * (or default for OMP), while OMP preserves model-role ids (default / smol / slow / plan).
 * Grok is the exception among CLI providers: its ACP harness has a native auto-approve
 * alias, so 'auto' passes through (see NATIVE_AUTO_APPROVAL_PROVIDERS).
 */
export function normalizeCliPermissionMode(mode: PermissionMode, provider?: string | null): PermissionMode {
  if (provider === PROVIDER_TYPE.OMP) {
    return mode === 'auto' || mode === 'autoEdit' ? 'default' : mode;
  }
  if (mode === 'autoEdit') {
    return 'acceptEdits';
  }
  if (mode === 'auto') {
    return provider && NATIVE_AUTO_APPROVAL_PROVIDERS.has(provider) ? mode : 'default';
  }
  return mode === 'plan' ? 'default' : mode;
}
