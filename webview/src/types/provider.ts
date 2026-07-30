/**
 * Provider configuration type definitions
 */

import { PROVIDER_TYPE, CODEX_PROTECTED_ENV_KEY } from '../generated/protocol';

// C2/C9:ProviderType 类型由后端 protocol.ProviderType 枚举构建时生成器产出
// (webview/src/generated/protocol.ts),消除前端手写第二真相源。
export type { ProviderType } from '../generated/protocol';

// ============ Constants ============

/**
 * Special pseudo provider IDs (not stored in config.json providers list)
 * These represent special operational modes, not actual provider configurations.
 */
export const SPECIAL_PROVIDER_IDS = {
  /** Disabled state - no active provider */
  DISABLED: '__disabled__',
  /** Local ~/.claude/settings.json mode */
  LOCAL_SETTINGS: '__local_settings_json__',
  /** CLI login authentication mode */
  CLI_LOGIN: '__cli_login__',
  /** Codex CLI login authentication mode */
  CODEX_CLI_LOGIN: '__codex_cli_login__',
  /** OpenCode local config (~/.config/opencode/opencode.json authoritative read-only) mode */
  OPENCODE_LOCAL_CONFIG: '__opencode_local_config__',
} as const;

/**
 * 基础 provider id 常量(SSOT:C2/C9,由后端 protocol.ProviderType 枚举构建时生成派生,
 * 不再手写——原与 CommonConstants.PROVIDER_CLAUDE/PROVIDER_CODEX 重复的第二真相源)。
 * 运行时比较 provider 时统一引用,避免散落的 'claude'/'codex' 字面量。
 */
export const PROVIDER_IDS = PROVIDER_TYPE;

/**
 * localStorage keys for provider-related data
 */
export const STORAGE_KEYS = {
  /** Claude model mapping configuration */
  CLAUDE_MODEL_MAPPING: 'claude-model-mapping',
  /** Pricing metadata for Claude configured models */
  CLAUDE_CONFIGURED_MODEL_PRICING: 'claude-configured-model-pricing',
  /** Claude custom models storage key */
  CLAUDE_CUSTOM_MODELS: 'claude-custom-models',
  /** Codex custom models storage key */
  CODEX_CUSTOM_MODELS: 'codex-custom-models',
} as const;

/**
 * Claude provider env keys that affect runtime model resolution.
 */
export const CLAUDE_MODEL_MAPPING_ENV_KEYS = [
  'ANTHROPIC_MODEL',
  'ANTHROPIC_DEFAULT_FABLE_MODEL',
  'ANTHROPIC_DEFAULT_HAIKU_MODEL',
  'ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME',
  'ANTHROPIC_DEFAULT_SONNET_MODEL',
  'ANTHROPIC_DEFAULT_SONNET_MODEL_NAME',
  'ANTHROPIC_DEFAULT_OPUS_MODEL',
  'ANTHROPIC_DEFAULT_OPUS_MODEL_NAME',
  'ANTHROPIC_DEFAULT_FABLE_MODEL',
  'ANTHROPIC_DEFAULT_FABLE_MODEL_NAME',
] as const;

// ============ Validation Helpers ============

/**
 * Validate whether a ModelPricing object is valid.
 * Every field is optional, but if present must be a finite number >= 0.
 */
export function isValidModelPricing(pricing: unknown): boolean {
  if (!pricing || typeof pricing !== 'object') return false;
  const p = pricing as Record<string, unknown>;
  const fields: (keyof ModelPricing)[] = [
    'inputCostPer1M',
    'outputCostPer1M',
    'cacheWriteCostPer1M',
    'cacheReadCostPer1M',
  ];
  for (const f of fields) {
    const v = p[f];
    if (v === undefined) continue;
    if (typeof v !== 'number' || !Number.isFinite(v) || v < 0) return false;
  }
  return true;
}

/**
 * Validate and filter a CodexCustomModel array
 * @param models - Array to validate
 * @returns Array of valid CodexCustomModel entries
 */
export function validateCodexCustomModels(models: unknown): CodexCustomModel[] {
  if (!Array.isArray(models)) return [];
  return models.filter((model): model is CodexCustomModel => {
    if (!model || typeof model !== 'object') return false;
    const obj = model as Record<string, unknown>;
    if (typeof obj.id !== 'string' || !obj.id.trim() || obj.id.trim().length > 256) return false;
    if (typeof obj.label !== 'string' || obj.label.trim().length === 0) return false;
    if (obj.description !== undefined && typeof obj.description !== 'string') return false;
    if (obj.pricing !== undefined && !isValidModelPricing(obj.pricing)) return false;
    return true;
  });
}

// ============ Types ============

/**
 * Provider category
 */
type ProviderCategory =
  | 'official' // Official
  | 'cn_official' // Chinese official
  | 'aggregator' // Aggregator service
  | 'third_party' // Third-party
  | 'custom'; // Custom

/**
 * Provider configuration (simplified, adapted for current project)
 */
export interface ProviderConfig {
  id: string;
  name: string;
  remark?: string;
  websiteUrl?: string;
  category?: ProviderCategory;
  createdAt?: number;
  isActive?: boolean;
  source?: 'cc-switch' | string;
  isLocalProvider?: boolean;
  isCliLoginProvider?: boolean;
  /** Custom model list (displayed before built-in models in the selector) */
  customModels?: CodexCustomModel[];
  settingsConfig?: {
    env?: {
      ANTHROPIC_AUTH_TOKEN?: string;
      ANTHROPIC_BASE_URL?: string;
      ANTHROPIC_MODEL?: string;
      ANTHROPIC_DEFAULT_FABLE_MODEL?: string;
      ANTHROPIC_DEFAULT_SONNET_MODEL?: string;
      ANTHROPIC_DEFAULT_OPUS_MODEL?: string;
      ANTHROPIC_DEFAULT_HAIKU_MODEL?: string;
      [key: string]: any;
    };
    alwaysThinkingEnabled?: boolean;
    permissions?: {
      allow?: string[];
      deny?: string[];
    };
  };
}

/**
 * Codex custom model configuration
 */
export interface CodexCustomModel {
  /** Model ID (unique identifier) */
  id: string;
  /** Model display name */
  label: string;
  /** Model description */
  description?: string;
  /** Base context window size in tokens; undefined = use backend default (200K) */
  contextWindow?: number;
  /** Optional usage pricing supplied by the backend/provider configuration */
  pricing?: ModelPricing;
}

/**
 * Model pricing for usage statistics aggregation.
 * All cost fields are per-million-tokens; each field is optional.
 */
export interface ModelPricing {
  /** Cost per 1M input tokens */
  inputCostPer1M?: number;
  /** Cost per 1M output tokens */
  outputCostPer1M?: number;
  /** Cost per 1M cache-write tokens */
  cacheWriteCostPer1M?: number;
  /** Cost per 1M cache-read tokens */
  cacheReadCostPer1M?: number;
}

/**
 * Single environment variable entry
 */
export interface EnvVarEntry {
  /** Environment variable name */
  key: string;
  /** Environment variable value */
  value: string;
}

/**
 * Maximum length for env var values. Long values risk exceeding the OS
 * ARG_MAX limit when the child process is spawned.
 * Must stay in sync with MAX_ENV_VAR_VALUE_LENGTH in CodexSDKBridge.java.
 */
export const ENV_VAR_VALUE_MAX_LENGTH = 16 * 1024;

/**
 * Validate whether an env var key name is valid.
 * Must start with letter or underscore, followed by letters, digits, or underscores.
 */
export function isValidEnvVarKey(key: string): boolean {
  if (!key || typeof key !== 'string') return false;
  return /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(key);
}

/**
 * Codex protected environment variable names that cannot be overridden by custom env vars.
 *
 * A5 SSOT:由后端 protocol.CodexProtectedEnvKey 枚举经生成链产出
 * (webview/src/generated/protocol.ts#CODEX_PROTECTED_ENV_KEY),消除此处手抄的第二
 * 真相源(与后端 CodexCliCommandUtils/CodexSDKBridge 三处同源)。
 */
const CODEX_PROTECTED_ENV_KEYS: ReadonlySet<string> = new Set(
  Object.values(CODEX_PROTECTED_ENV_KEY),
);

/**
 * Check if an env var key is a protected Codex built-in variable.
 *
 * NOTE: comparison is case-insensitive (key is uppercased before lookup).
 * On Linux/macOS env vars are case-sensitive, but we conservatively reject
 * any case-variant of a protected name to keep behavior consistent across
 * platforms (Windows env vars are case-insensitive).
 */
export function isProtectedEnvVarKey(key: string): boolean {
  return CODEX_PROTECTED_ENV_KEYS.has(key.toUpperCase());
}

export interface EnvVarValidationIssue {
  index: number;
  field: 'key' | 'value';
  reason: 'invalid' | 'protected' | 'duplicate' | 'value_too_long';
  key?: string;
}

/**
 * Validate a list of EnvVarEntry. Returns the first issue per row, if any.
 * Empty keys are skipped (will be filtered before saving).
 */
export function validateEnvVarEntries(entries: EnvVarEntry[]): EnvVarValidationIssue[] {
  const issues: EnvVarValidationIssue[] = [];
  const seenKeys = new Set<string>();

  entries.forEach((entry, index) => {
    if (entry.value.length > ENV_VAR_VALUE_MAX_LENGTH) {
      issues.push({ index, field: 'value', reason: 'value_too_long' });
    }

    const key = entry.key.trim();
    if (!key) return;

    if (!isValidEnvVarKey(key)) {
      issues.push({ index, field: 'key', reason: 'invalid', key });
      return;
    }

    if (isProtectedEnvVarKey(key)) {
      issues.push({ index, field: 'key', reason: 'protected', key });
      return;
    }

    const upperKey = key.toUpperCase();
    if (seenKeys.has(upperKey)) {
      issues.push({ index, field: 'key', reason: 'duplicate', key });
      return;
    }
    seenKeys.add(upperKey);
  });

  return issues;
}

/**
 * Codex provider configuration
 */
export interface CodexProviderConfig {
  /** Unique provider ID */
  id: string;
  /** Provider name */
  name: string;
  /** Remark */
  remark?: string;
  /** Creation timestamp (milliseconds) */
  createdAt?: number;
  /** Whether this is the currently active provider */
  isActive?: boolean;
  /** config.toml content (raw string) */
  configToml?: string;
  /** auth.json content (raw string) */
  authJson?: string;
  /** Custom model list */
  customModels?: CodexCustomModel[];
  /** Provider model catalog used by Codex model selector */
  modelCatalog?: CodexCustomModel[];
  /** Environment variables for sendMessage subprocess */
  messageEnvVars?: EnvVarEntry[];
  /** Environment variables for getMcpServerTools subprocess */
  mcpEnvVars?: EnvVarEntry[];
}

/**
 * OpenCode 单个模型配置(opencode.json 原生 models.<modelKey> 结构,半 schema-less)。
 */
export interface OpenCodeModelConfig {
  /** 模型显示名(缺省用 modelKey) */
  name?: string;
  /** 模型限制 */
  limit?: {
    /** 上下文窗口(tokens) */
    context?: number;
    /** 输出上限(tokens) */
    output?: number;
  };
  [key: string]: any;
}

/**
 * OpenCode provider 配置(对称 {@link CodexProviderConfig},但半 schema-less)。
 *
 * <p>opencode 原生 provider 段结构为 {@code {name, models:{...}, apiKey?, baseURL?, ...}},
 * 插件 SSOT 在此基础上加 {@code id}/{@code isActive}/{@code createdAt}(合并入 opencode.json 时剥离)。
 * 用 index signature 透传任意 opencode 原生字段(对齐项目既有 provider JsonObject 半 schema-less 决策)。
 */
export interface OpenCodeProviderConfig {
  /** Provider 唯一键(= opencode.json provider 段的 key,如 openglm/mimo) */
  id: string;
  /** Provider 显示名 */
  name: string;
  /** 创建时间戳(毫秒,插件专属) */
  createdAt?: number;
  /** 是否当前活跃 */
  isActive?: boolean;
  /** 是否为「从配置文件授权」本地配置虚拟 provider */
  isOpenCodeLocalConfigProvider?: boolean;
  /** API Key(opencode 原生凭据字段) */
  apiKey?: string;
  /** Base URL(opencode 原生字段) */
  baseURL?: string;
  /** Base URL 别名(opencode 部分版本用 apiBase) */
  apiBase?: string;
  /** 模型目录(opencode 原生嵌套结构) */
  models?: Record<string, OpenCodeModelConfig>;
  /** 任意 opencode 原生透传字段(options/npm/etc.) */
  [key: string]: any;
}

// ============ Provider Presets ============

/**
 * 第三方 provider 快捷填充模板(前端 UI 关注点)。
 *
 * 架构定性:PROVIDER_PRESETS 是 ProviderDialog「新建/编辑 provider」时的快捷选项,
 * 仅服务于 UI 表单填充(预置 base_url + 默认 model name)与 base_url 反查匹配。
 * 它不是后端业务数据表:
 *   - 后端不消费(用户最终配置的 env 由后端透传给子进程,preset 仅是默认建议);
 *   - 后端零持有(grep zhipu/kimi/deepseek/minimax/xiaomi/qwen/openrouter 无匹配);
 *   - nameKey 是前端 i18n key,非后端可解释的业务值。
 * 故不下沉后端(下沉为纯形式 SSOT —— useModelProviderState 中 currentProvider
 * ∈ {claude,codex} 永不等于 preset id,即使订阅后端 preset,find 结果仍恒 undefined)。
 *
 * 模型能力(contextWindow/supports1MContext)权威来源是后端 ModelRegistry 下行
 * (MODEL_REGISTRY/MODEL_REGISTRY_UPDATED);前端不得在此预置能力字段(历史曾有
 * defaultContextWindow/supports1MContext 死字段,已删除以符合总则一:前端不做能力判定)。
 */
export interface ProviderPreset {
  /** Unique preset ID */
  id: string;
  /** i18n key for preset name, resolved at render time */
  nameKey: string;
  /** Environment variable configuration */
  env: Record<string, string>;
}

/**
 * Provider preset configuration list, used for quick provider setup in ProviderDialog.
 * nameKey is resolved at render time via t() to the display name for the current language.
 *
 * NOTE:能力字段(defaultContextWindow/supports1MContext)已移除 —— 模型能力权威来源是
 * 后端 ModelRegistry 下行,前端不得预置(见 ProviderPreset 架构注释)。
 */
export const PROVIDER_PRESETS: ProviderPreset[] = [
  {
    id: 'custom',
    nameKey: 'settings.provider.presets.custom',
    env: {},
  },
  {
    id: 'zhipu',
    nameKey: 'settings.provider.presets.zhipu',
    env: {
      ANTHROPIC_BASE_URL: 'https://open.bigmodel.cn/api/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'glm-4.7',
    },
  },
  {
    id: 'kimi',
    nameKey: 'settings.provider.presets.kimi',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.moonshot.cn/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'kimi-k2.5',
    },
  },
  {
    id: 'deepseek',
    nameKey: 'settings.provider.presets.deepseek',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.deepseek.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'deepseek-v4-flash',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'deepseek-v4-pro[1m]',
      CLAUDE_CODE_EFFORT_LEVEL: 'max',
    },
  },
  {
    id: 'minimax',
    nameKey: 'settings.provider.presets.minimax',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.minimaxi.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      // MiniMax models respond slowly; requires 50-minute timeout (3,000,000ms) to avoid truncating long reasoning requests
      API_TIMEOUT_MS: '3000000',
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'MiniMax-M2.1',
    },
  },
  {
    id: 'xiaomi',
    nameKey: 'settings.provider.presets.xiaomi',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.xiaomimimo.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    },
  },
  {
    id: 'xiaomi-plan',
    nameKey: 'settings.provider.presets.xiaomiPlan',
    env: {
      ANTHROPIC_BASE_URL: 'https://token-plan-cn.xiaomimimo.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    },
  },
  {
    id: 'qwen',
    nameKey: 'settings.provider.presets.qwen',
    env: {
      ANTHROPIC_BASE_URL: 'https://dashscope.aliyuncs.com/apps/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'qwen3-max',
    },
  },
  {
    id: 'openrouter',
    nameKey: 'settings.provider.presets.openrouter',
    env: {
      ANTHROPIC_BASE_URL: 'https://openrouter.ai/api',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'anthropic/claude-fable-5',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'anthropic/claude-haiku-4.5',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'anthropic/claude-sonnet-4.5',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'anthropic/claude-opus-4.5',
    },
  },
];
