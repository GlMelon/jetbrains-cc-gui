import type { ProviderType } from './provider';

// C2/C9:AiFeatureProvider 值域由后端 protocol.ProviderType 枚举 SSOT 生成派生,不再手写。
export type AiFeatureProvider = ProviderType;
export type AiFeatureResolutionSource = 'manual' | 'auto' | 'unavailable';

export const DEFAULT_AI_FEATURE_MODELS = {
  claude: 'claude-role-sonnet',
  codex: '',
} as const;

export interface AiFeatureConfig {
  provider: AiFeatureProvider | null;
  effectiveProvider: AiFeatureProvider | null;
  resolutionSource: AiFeatureResolutionSource;
  models: {
    claude: string;
    codex: string;
  };
  availability: {
    claude: boolean;
    codex: boolean;
  };
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
  },
  availability: {
    claude: false,
    codex: false,
  },
};
