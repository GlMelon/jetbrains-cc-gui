/**
 * Model ID to vendor icon mapping utility.
 *
 * Resolves a model ID string (e.g. "qwen3.5-plus", "deepseek-v3.2") to a
 * vendor key that can be used to select the appropriate icon from @lobehub/icons.
 */

/**
 * Vendor keys recognised by the icon system.
 * Each key maps to a named export from @lobehub/icons.
 */
export type ModelVendor =
  | 'claude'
  | 'openai'
  | 'opencode'
  | 'gemini'
  | 'qwen'
  | 'deepseek'
  | 'kimi'
  | 'moonshot'
  | 'zhipu'
  | 'minimax'
  | 'xiaomi'
  | 'doubao'
  | 'spark'
  | 'hunyuan'
  | 'baichuan'
  | 'mistral'
  | 'meta'
  | 'cohere'
  | 'grok'
  | 'openrouter'
  | 'yi';

/**
 * Pattern rules for matching model IDs to vendors.
 * Order matters: first match wins. More specific patterns should come first.
 */
const MODEL_VENDOR_PATTERNS: ReadonlyArray<readonly [RegExp, ModelVendor]> = [
  // Chinese model vendors
  [/qwen/i, 'qwen'],
  [/deepseek/i, 'deepseek'],
  [/kimi/i, 'kimi'],
  [/moonshot/i, 'moonshot'],
  [/glm|chatglm/i, 'zhipu'],
  [/zhipu/i, 'zhipu'],
  [/minimax/i, 'minimax'],
  [/xiaomi|mimo/i, 'xiaomi'],
  [/doubao/i, 'doubao'],
  [/^spark(?:[-\s]|$)/i, 'spark'],
  [/hunyuan/i, 'hunyuan'],
  [/baichuan/i, 'baichuan'],
  [/yi-|^yi\b/i, 'yi'],

  // International model vendors
  [/claude|anthropic/i, 'claude'],
  [/gpt[-\s]|^gpt\d|^o[134]-|^o[134]\b|openai/i, 'openai'],
  [/gemini/i, 'gemini'],
  [/mistral|mixtral|codestral|pixtral/i, 'mistral'],
  [/llama|meta[-/]/i, 'meta'],
  [/cohere|command[-\s]?[ra]/i, 'cohere'],
  [/grok/i, 'grok'],
];

/**
 * Provider ID to vendor mapping.
 * Used when provider preset ID is known (e.g. from PROVIDER_PRESETS).
 */
const PROVIDER_TO_VENDOR: Record<string, ModelVendor> = {
  claude: 'claude',
  codex: 'openai',
  opencode: 'opencode',
  gemini: 'gemini',
  qwen: 'qwen',
  deepseek: 'deepseek',
  kimi: 'kimi',
  zhipu: 'zhipu',
  minimax: 'minimax',
  xiaomi: 'xiaomi',
  openrouter: 'openrouter',
};

/**
 * Resolve the best vendor for icon display.
 * Priority: modelId match > providerId match > null
 *
 * @param providerId - The provider type (e.g. "claude", "codex")
 * @param modelId - Optional model ID for more specific matching
 * @returns The best-matched vendor key, or 'claude' as default
 */
export function resolveIconVendor(providerId?: string, modelId?: string): ModelVendor {
  // Try model ID first (most specific)
  if (modelId) {
    for (const [pattern, vendor] of MODEL_VENDOR_PATTERNS) {
      if (pattern.test(modelId)) return vendor;
    }
  }
  // Fall back to provider ID
  if (providerId) {
    const providerVendor = PROVIDER_TO_VENDOR[providerId];
    if (providerVendor) return providerVendor;
  }
  // Default
  return 'claude';
}
