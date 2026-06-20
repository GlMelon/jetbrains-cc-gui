/**
 * Provider configuration type definitions
 */

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
} as const;

/**
 * 基础 provider id 常量(与后端 CommonConstants.PROVIDER_CLAUDE/PROVIDER_CODEX 对齐)。
 * 运行时比较 provider 时统一引用,避免散落的 'claude'/'codex' 字面量。
 * 注:前端历史代码中 'claude'/'codex' 字面量较多,统一化重构逐步收敛到本常量。
 */
export const PROVIDER_IDS = {
  CLAUDE: 'claude',
  CODEX: 'codex',
} as const;

/**
 * Check if a provider ID is a special pseudo provider
 * @param id - Provider ID to check
 * @returns Whether this is a special pseudo provider that cannot be updated via update_provider
 */
export function isSpecialProviderId(id: string): boolean {
  return (
    id === SPECIAL_PROVIDER_IDS.DISABLED ||
    id === SPECIAL_PROVIDER_IDS.LOCAL_SETTINGS ||
    id === SPECIAL_PROVIDER_IDS.CLI_LOGIN ||
    id === SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN
  );
}

/**
 * localStorage keys for provider-related data
 */
export const STORAGE_KEYS = {
  /** Claude model mapping configuration */
  CLAUDE_MODEL_MAPPING: 'claude-model-mapping',
} as const;

/**
 * Claude provider env keys that affect runtime model resolution.
 */
export const CLAUDE_MODEL_MAPPING_ENV_KEYS = [
  'ANTHROPIC_MODEL',
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
 * Validate whether a model ID format is valid.
 *
 * NOTE: Model ID format is intentionally NOT restricted by regex.
 * Third-party providers use diverse model ID formats that cannot be
 * predicted (e.g., slashes, brackets, CJK characters). Only basic
 * sanity checks (non-empty, length limit) are applied.
 * Do NOT re-add MODEL_ID_PATTERN validation here.
 *
 * @param id - Model ID
 * @returns Whether the ID is valid
 */
export function isValidModelId(id: string): boolean {
  if (!id || typeof id !== 'string') return false;
  const trimmed = id.trim();
  if (trimmed.length === 0 || trimmed.length > 256) return false;
  return true;
}

/**
 * Validate whether a CodexCustomModel object is valid
 * @param model - Object to validate
 * @returns Whether it is a valid CodexCustomModel
 */
function isValidCodexCustomModel(model: unknown): model is CodexCustomModel {
  if (!model || typeof model !== 'object') return false;
  const obj = model as Record<string, unknown>;

  // id must be a valid model ID
  if (typeof obj.id !== 'string' || !isValidModelId(obj.id)) return false;

  // label must be a string
  if (typeof obj.label !== 'string' || obj.label.trim().length === 0) return false;

  // description is optional, but must be a string if present
  if (obj.description !== undefined && typeof obj.description !== 'string') return false;

  return true;
}

/**
 * Validate and filter a CodexCustomModel array
 * @param models - Array to validate
 * @returns Array of valid CodexCustomModel entries
 */
export function validateCodexCustomModels(models: unknown): CodexCustomModel[] {
  if (!Array.isArray(models)) return [];
  return models.filter(isValidCodexCustomModel);
}

// ============ Types ============

/**
 * Provider category
 */
type ProviderCategory =
  | 'official'      // Official
  | 'cn_official'   // Chinese official
  | 'aggregator'    // Aggregator service
  | 'third_party'   // Third-party
  | 'custom';       // Custom

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
      ANTHROPIC_DEFAULT_SONNET_MODEL?: string;
      ANTHROPIC_DEFAULT_OPUS_MODEL?: string;
      ANTHROPIC_DEFAULT_HAIKU_MODEL?: string;
      ANTHROPIC_DEFAULT_FABLE_MODEL?: string;
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
 */
const CODEX_PROTECTED_ENV_KEYS: ReadonlySet<string> = new Set([
  'CODEX_USE_STDIN',
  'CODEX_MODEL',
  'CODEX_SANDBOX_MODE',
  'CODEX_SANDBOX',
  'CODEX_APPROVAL_POLICY',
  'CODEX_CI',
  'CODEX_SANDBOX_NETWORK_DISABLED',
  'CODEX_HOME',
  'CLAUDE_SESSION_ID',
  'CLAUDE_PERMISSION_DIR',
  'HOME',
  'PATH',
  'TMPDIR',
  'TEMP',
  'TMP',
  'IDEA_PROJECT_PATH',
  'PROJECT_PATH',
  'CLAUDE_USE_STDIN',
]);

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
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'glm-4.7',
    },
  },
  {
    id: 'kimi',
    nameKey: 'settings.provider.presets.kimi',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.moonshot.cn/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'kimi-k2.5',
    },
  },
  {
    id: 'deepseek',
    nameKey: 'settings.provider.presets.deepseek',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.deepseek.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'deepseek-v4-flash',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'deepseek-v4-pro[1m]',
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
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'MiniMax-M2.1',
    },
  },
  {
    id: 'xiaomi',
    nameKey: 'settings.provider.presets.xiaomi',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.xiaomimimo.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'mimo-v2.5-pro',
    },
  },
  {
    id: 'xiaomi-plan',
    nameKey: 'settings.provider.presets.xiaomiPlan',
    env: {
      ANTHROPIC_BASE_URL: 'https://token-plan-cn.xiaomimimo.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'mimo-v2.5-pro',
    },
  },
  {
    id: 'qwen',
    nameKey: 'settings.provider.presets.qwen',
    env: {
      ANTHROPIC_BASE_URL: 'https://dashscope.aliyuncs.com/apps/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'qwen3-max',
    },
  },
  {
    id: 'openrouter',
    nameKey: 'settings.provider.presets.openrouter',
    env: {
      ANTHROPIC_BASE_URL: 'https://openrouter.ai/api',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'anthropic/claude-haiku-4.5',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'anthropic/claude-sonnet-4.5',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'anthropic/claude-opus-4.5',
      ANTHROPIC_DEFAULT_FABLE_MODEL: 'anthropic/claude-opus-4.5',
    },
  },
];
