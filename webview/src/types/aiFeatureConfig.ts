import type { ProviderType } from './provider';

// C2/C9:AiFeatureProvider 值域由后端 protocol.ProviderType 枚举 SSOT 生成派生,不再手写。
export type AiFeatureProvider = ProviderType;
export const DEFAULT_AI_FEATURE_MODELS = {
  claude: 'claude-role-sonnet',
  codex: '',
  opencode: '',
} as const;

export interface AiFeatureConfig {
  provider: AiFeatureProvider | null;
  effectiveProvider: AiFeatureProvider | null;
  resolutionSource: 'manual' | 'auto' | 'unavailable';
  // models/availability 仅对 SDK-capable provider(claude/codex/opencode)登记;
  // grok/kimi/pi 等纯 CLI provider 不参与 AI 辅助功能(commit/prompt-enhancer),
  // 故无条目(索引得 undefined,各访问点已以 truthiness 容错)。
  models: Partial<Record<ProviderType, string>>;
  availability: Partial<Record<ProviderType, boolean>>;
}

export type CommitAiProvider = AiFeatureProvider;
export type CommitAiConfig = AiFeatureConfig;

export const DEFAULT_COMMIT_AI_CONFIG: CommitAiConfig = {
  provider: null,
  effectiveProvider: 'codex',
  resolutionSource: 'auto',
  models: {
    claude: DEFAULT_AI_FEATURE_MODELS.claude,
    codex: DEFAULT_AI_FEATURE_MODELS.codex,
    opencode: DEFAULT_AI_FEATURE_MODELS.opencode,
  },
  availability: {
    claude: false,
    codex: false,
    opencode: false,
  },
};
