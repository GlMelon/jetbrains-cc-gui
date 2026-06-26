/**
 * Model utilities module.
 * Handles model ID mapping and environment variable configuration.
 *
 * 模型相关字面量（role 名、role modelId、环境变量名、前缀）统一收敛到 MODEL_CONSTANTS，
 * 与后端 Java CommonConstants.ENV_ANTHROPIC_* / DEFAULT_MODEL('claude-role-sonnet') 对齐。
 */

/**
 * 模型相关常量：集中管理 role 名、role→SDK modelId 映射、环境变量名、模型前缀。
 * 与后端 Java CommonConstants.ENV_ANTHROPIC_* / DEFAULT_MODEL('claude-role-sonnet') 对齐。
 */
export const MODEL_CONSTANTS = Object.freeze({
  /** Claude 角色桶名（内部稳定标识，用于 settings/env 查找）。 */
  ROLE: Object.freeze({
    SONNET: 'sonnet',
    OPUS: 'opus',
    HAIKU: 'haiku',
    FABLE: 'fable',
  }),
  /** 角色 → SDK 识别的 role modelId（getClaudeRoleFromModelId 匹配的 role selector）。 */
  ROLE_MODEL_ID: Object.freeze({
    FABLE: 'claude-role-fable',
    OPUS: 'claude-role-opus',
    HAIKU: 'claude-role-haiku',
    SONNET: 'claude-role-sonnet',
  }),
  /** 环境变量名（与 Java CommonConstants.ENV_ANTHROPIC_* 对齐）。 */
  ENV: Object.freeze({
    ANTHROPIC_MODEL: 'ANTHROPIC_MODEL',
    DEFAULT_FABLE_MODEL: 'ANTHROPIC_DEFAULT_FABLE_MODEL',
    DEFAULT_OPUS_MODEL: 'ANTHROPIC_DEFAULT_OPUS_MODEL',
    DEFAULT_HAIKU_MODEL: 'ANTHROPIC_DEFAULT_HAIKU_MODEL',
    DEFAULT_SONNET_MODEL: 'ANTHROPIC_DEFAULT_SONNET_MODEL',
  }),
  /** Anthropic 官方模型前缀（用于 vision 能力判定）。 */
  CLAUDE_PREFIX: 'claude-',
});

const { ROLE, ROLE_MODEL_ID, ENV, CLAUDE_PREFIX } = MODEL_CONSTANTS;

/**
 * Map a full model ID to the short name expected by the Claude SDK.
 * @param {string} modelId - Internal role model ID (e.g. 'claude-role-sonnet')
 * @returns {string} SDK model name (e.g. 'sonnet')
 */
export function mapModelIdToSdkName(modelId) {
  if (!modelId || typeof modelId !== 'string') {
    return ROLE.SONNET; // Default to sonnet
  }

  const role = getClaudeRoleFromModelId(modelId);
  // Mapping rules（基于 role selector，具体模型 ID 不在此识别）:
  // - role 'fable'/'opus' -> 'opus'
  // - role 'haiku' -> 'haiku'
  // - Otherwise（sonnet role 或非 role / 未知）-> 'sonnet'
  if (role === ROLE.OPUS || role === ROLE.FABLE) {
    return ROLE.OPUS;
  }
  if (role === ROLE.HAIKU) {
    return ROLE.HAIKU;
  }
  return ROLE.SONNET;
}

/**
 * Resolve a Claude role selector ID to the stable role bucket used for
 * settings/env lookup. Concrete historical model IDs are intentionally not
 * treated as roles.
 *
 * @param {string} modelId
 * @returns {'sonnet'|'opus'|'fable'|'haiku'|null}
 */
export function getClaudeRoleFromModelId(modelId) {
  if (!modelId || typeof modelId !== 'string') {
    return null;
  }
  const lowerModel = modelId.toLowerCase().replace(/\[1m\]$/i, '');
  if (lowerModel === ROLE_MODEL_ID.FABLE) {
    return ROLE.FABLE;
  }
  if (lowerModel === ROLE_MODEL_ID.OPUS) {
    return ROLE.OPUS;
  }
  if (lowerModel === ROLE_MODEL_ID.HAIKU) {
    return ROLE.HAIKU;
  }
  if (lowerModel === ROLE_MODEL_ID.SONNET) {
    return ROLE.SONNET;
  }
  return null;
}

/**
 * Resolve the actual model name for API calls from user's settings.json.
 * When the user configures a model mapping in their provider config (e.g. sonnet -> "MiniMax-M2.5"),
 * those values are written to ~/.claude/settings.json as ANTHROPIC_DEFAULT_*_MODEL env vars.
 * This function checks those settings and returns the mapped model name if configured.
 *
 * Priority: request actualModel > ANTHROPIC_DEFAULT_*_MODEL > ANTHROPIC_MODEL fallback > original modelId
 *
 * IMPORTANT: The `[1m]` suffix is controlled by the input modelId from the
 * webview, not by stale settings.env mappings.
 * The 1M context window is selected by the Claude Code SDK based on whether the
 * model name ends with `[1m]` (it reads `process.env.ANTHROPIC_DEFAULT_*_MODEL`).
 * If the request enables 1M, preserve or append the suffix on the mapped model.
 * If the request disables 1M, strip any suffix from the mapped value so an old
 * settings.json env value cannot force the 1M context window back on.
 *
 * @param {string} modelId - Internal model ID from frontend (e.g. 'claude-role-sonnet')
 * @param {object} userEnv - The env object from settings.json (settings.env)
 * @param {string} actualModel - Request-owned actual model from Model Registry
 * @returns {string} The resolved model name for API calls, with the `[1m]` suffix preserved
 */
export function resolveModelFromSettings(modelId, userEnv, actualModel = null) {
  if (!modelId) return modelId;

  const role = getClaudeRoleFromModelId(modelId);
  const isClaudeRoleModel = role !== null;
  const requestHas1M = /\[1m\]$/i.test(modelId);
  // The request owns 1M state. Settings mappings may provide the provider's base
  // model ID, but they must not force the context-window suffix.
  const applySuffix = (mapped) => {
    const base = String(mapped).trim().replace(/\[1m\]$/i, '');
    return requestHas1M ? `${base}[1m]` : base;
  };

  if (actualModel && String(actualModel).trim()) {
    return applySuffix(String(actualModel).trim());
  }

  if (!userEnv) return modelId;

  // Check model-specific env vars based on the internal role ID's type.
  if (role === ROLE.FABLE) {
    const mapped = userEnv[ENV.DEFAULT_FABLE_MODEL]
      || userEnv[ENV.DEFAULT_OPUS_MODEL];
    if (mapped && String(mapped).trim()) {
      return applySuffix(String(mapped).trim());
    }
  } else if (role === ROLE.OPUS) {
    const mapped = userEnv[ENV.DEFAULT_OPUS_MODEL];
    if (mapped && String(mapped).trim()) {
      return applySuffix(String(mapped).trim());
    }
  } else if (role === ROLE.HAIKU) {
    const mapped = userEnv[ENV.DEFAULT_HAIKU_MODEL];
    if (mapped && String(mapped).trim()) {
      return applySuffix(String(mapped).trim());
    }
  } else if (role === ROLE.SONNET) {
    // Only apply sonnet mapping when the model ID actually contains 'sonnet'.
    // Non-Anthropic model names (e.g. 'qwen3.5-plus', 'deepseek-v3') should NOT be
    // remapped to the sonnet setting, as they are already the intended model name.
    const mapped = userEnv[ENV.DEFAULT_SONNET_MODEL];
    if (mapped && String(mapped).trim()) {
      return applySuffix(String(mapped).trim());
    }
  }

  // ANTHROPIC_MODEL is a fallback for Claude role selectors. Explicit custom
  // model IDs from the webview already are the intended upstream model and must
  // not be rewritten by stale provider defaults.
  if (isClaudeRoleModel && userEnv[ENV.ANTHROPIC_MODEL] && String(userEnv[ENV.ANTHROPIC_MODEL]).trim()) {
    return applySuffix(String(userEnv[ENV.ANTHROPIC_MODEL]).trim());
  }

  // For non-Anthropic model IDs that don't contain 'opus'/'haiku'/'sonnet',
  // skip mapping and use the original model ID as-is.

  // No mapping configured, use original model ID
  return modelId;
}

/**
 * Resolve the Claude SDK model name to send for prompt enhancement (Bug 3 fix).
 *
 * promptEnhancer(cllaude 路径)下发的模型名必须与 chat/commitAi 同源 —— 优先用
 * registry 解析的 actualModel(具体模型 id,如 glm-5.2),而非仅靠 role→bucket
 * 映射 + settings.json env 间接解析。当 registry actualModel 与 settings.json env
 * (cc-switch 写入)不同步时,旧逻辑(mapModelIdToSdkName → bucket)会用错模型。
 *
 * actualModel 优先 + [1m] 处理语义与 {@link resolveModelFromSettings} 一致,但回退是
 * bucket name(promptEnhancer 的 SDK `model` 参数需要 bucket selector,而非 role id 原值)。
 *
 * @param {string} model - Internal role model ID (e.g. 'claude-role-sonnet'), may carry [1m]
 * @param {string} [actualModel] - registry-resolved concrete model id
 * @returns {string} SDK model name (actualModel[±1m], or bucket name fallback)
 */
export function resolveClaudeEnhanceModelName(model, actualModel) {
  const requestHas1M = /\[1m\]$/i.test(model || '');
  const applySuffix = (value) => {
    const base = String(value).trim().replace(/\[1m\]$/i, '');
    return requestHas1M ? `${base}[1m]` : base;
  };

  if (actualModel && String(actualModel).trim()) {
    return applySuffix(String(actualModel).trim());
  }
  return mapModelIdToSdkName(model);
}

/**
 * Set SDK environment variables based on the model name.
 * The Claude SDK uses short names (opus/sonnet/haiku) as model selectors,
 * while the specific version is determined by ANTHROPIC_DEFAULT_*_MODEL environment variables.
 *
 * NOTE: This function mutates process.env as a side effect, which is required by the
 * Claude SDK's model resolution mechanism. This is safe in the current single-request
 * architecture but should be revisited if concurrent request handling is introduced.
 *
 * @param {string} modelId - The resolved model name to set as env var value (e.g. 'MiniMax-M2.5' or 'claude-opus-4-6')
 * @param {string} [baseModelId] - The original internal model ID used to determine which env var to set.
 *                                  Required when modelId is a custom name that doesn't contain 'opus'/'haiku'/'sonnet'.
 *                                  Falls back to modelId if not provided.
 */
export function setModelEnvironmentVariables(modelId, baseModelId) {
  if (!modelId || typeof modelId !== 'string') {
    return;
  }

  // Use baseModelId to determine model category (which env var to set).
  // This is necessary when modelId is a custom name like 'MiniMax-M2.5'
  // that doesn't contain 'opus'/'haiku'/'sonnet'.
  const role = getClaudeRoleFromModelId(baseModelId || modelId);

  process.env[ENV.ANTHROPIC_MODEL] = modelId;

  // Set the corresponding environment variable based on model type
  // so the SDK knows which specific version to use
  if (role === ROLE.FABLE) {
    process.env[ENV.DEFAULT_FABLE_MODEL] = modelId;
    process.env[ENV.DEFAULT_OPUS_MODEL] = modelId;
    console.log(`[MODEL_ENV] Set ${ENV.DEFAULT_FABLE_MODEL} =`, modelId);
    console.log(`[MODEL_ENV] Set ${ENV.DEFAULT_OPUS_MODEL} =`, modelId);
  } else if (role === ROLE.OPUS) {
    process.env[ENV.DEFAULT_OPUS_MODEL] = modelId;
    console.log(`[MODEL_ENV] Set ${ENV.DEFAULT_OPUS_MODEL} =`, modelId);
  } else if (role === ROLE.HAIKU) {
    process.env[ENV.DEFAULT_HAIKU_MODEL] = modelId;
    console.log(`[MODEL_ENV] Set ${ENV.DEFAULT_HAIKU_MODEL} =`, modelId);
  } else {
    // Covers 'sonnet' and any non-Anthropic model names (e.g. 'qwen3.5-plus', 'deepseek-v3')
    // Since mapModelIdToSdkName() defaults to 'sonnet' for unknown models,
    // the SDK will look up ANTHROPIC_DEFAULT_SONNET_MODEL for the actual model name
    process.env[ENV.DEFAULT_SONNET_MODEL] = modelId;
    console.log(`[MODEL_ENV] Set ${ENV.DEFAULT_SONNET_MODEL} =`, modelId);
  }
}

/**
 * Determine whether the model natively supports Anthropic vision content blocks.
 *
 * Different models have different vision input capabilities:
 * - Claude models (claude-*): Support Anthropic's standard vision format
 *   via {type: "image", source: {type: "base64", media_type, data}}.
 * - Third-party models (mimo, deepseek, qwen, glm, etc.): Many do not properly
 *   handle Anthropic vision content blocks, especially when routed through
 *   third-party Anthropic-compatible proxies. The image blocks may be silently
 *   dropped during proxy translation, causing the model to report "no image attached".
 *
 * For non-Claude models, the caller should fall back to saving images as temp
 * files and referencing them in the message text, mimicking Claude Code CLI
 * behavior which uses the Read tool to load images from disk.
 *
 * @param {string} modelId - The resolved model name actually sent to the API.
 *                            Examples: "claude-sonnet-4-5", "mimo-v2.5-pro", "MiniMax-M2.5"
 * @returns {boolean} True if the model natively supports Anthropic vision blocks.
 */
export function modelSupportsVision(modelId) {
  if (!modelId || typeof modelId !== 'string') {
    return true;
  }
  const lower = modelId.toLowerCase();
  // Anchor to the canonical "claude-" prefix to avoid matching third-party
  // model names that merely contain the substring "claude" (e.g.
  // "claude-compatible-proxy"), which historically yielded false positives
  // and dropped images for proxies that don't speak Anthropic vision blocks.
  return lower.startsWith(CLAUDE_PREFIX);
}

// Note: getClaudeCliPath() has been removed.
// Now using the SDK's built-in cli.js (at node_modules/@anthropic-ai/claude-agent-sdk/cli.js).
// This avoids system CLI path issues on Windows (ENOENT errors) and keeps the version aligned with the SDK.
