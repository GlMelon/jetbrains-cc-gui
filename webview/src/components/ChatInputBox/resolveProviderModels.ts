import type { ModelInfo } from './types';
// A1(2026-06-23):CLAUDE_MODELS/CODEX_MODELS/GROK_MODELS/OMP_MODELS/OMP_ROLE_MODELS 本地静态表
// 已删除(registry 为权威来源)。此 fallback 层仅在 registry 未加载时兜底,静态部分置空。
const CLAUDE_MODELS: ModelInfo[] = [];
const CODEX_MODELS: ModelInfo[] = [];
const GROK_MODELS: ModelInfo[] = [];
const OMP_MODELS: ModelInfo[] = [];
import { buildCodexModelList } from './codexModelList';
import {
  applyClaudeModelMapping,
  type ClaudeModelMapping,
} from '../../utils/claudeModelMapping';

export interface ResolveProviderModelsInput {
  provider: string;
  /** Dynamic catalog from useCliModels (may be static fallback when empty). */
  cliModels: ModelInfo[];
  /**
   * True only when the backend returned real catalog entries.
   * When false, cliModels is the static fallback and must not replace built-ins
   * for Codex (see buildCodexModelList).
   */
  cliCatalogHasEntries?: boolean;
  claudeCustomModels?: ModelInfo[];
  codexCustomModels?: ModelInfo[];
  claudeMapping?: ClaudeModelMapping | null;
}

/**
 * Single source of truth for the model picker list — used by:
 *  - main chat toolbar (ButtonArea)
 *  - Prompt Enhancer settings
 *  - Commit AI settings
 *
 * Keep all three UIs in lockstep so users never see divergent catalogs.
 */
export function resolveProviderModels({
  provider,
  cliModels,
  cliCatalogHasEntries = false,
  claudeCustomModels = [],
  codexCustomModels = [],
  claudeMapping = null,
}: ResolveProviderModelsInput): ModelInfo[] {
  if (provider === 'codex') {
    const catalogModels = cliCatalogHasEntries ? cliModels : [];
    return buildCodexModelList(catalogModels, codexCustomModels, CODEX_MODELS);
  }

  if (provider === 'grok') {
    // Prefer dynamic catalog (config profiles from get_cli_models). When the
    // catalog is empty/unavailable, fall back to the static profile slot.
    if (cliCatalogHasEntries && cliModels.length > 0) {
      return cliModels;
    }
    return cliModels.length > 0 ? cliModels : GROK_MODELS;
  }

  if (provider === 'kimi' || provider === 'opencode' || provider === 'pi' || provider === 'dsh') {
    // Runtime catalog from the CLI/host (static fallback list when offline).
    return cliModels;
  }

  if (provider === 'omp') {
    // 'auto' first, then the dynamic catalog. Model roles (smol/slow/plan/…)
    // are NOT listed here — they live in the mode selector (ModeSelect),
    // which sets the model to the role id as a shortcut.
    // Dedupe by identifier(同名 id 不同 URL 的条目靠 identifier 区分,4324bc09
    // 语义,不可按裸 id 去重);无 identifier 时回退裸 id(静态 fallback 场景
    // cliModels 即 OMP_MODELS,'auto' 会重复,需去重)。
    const merged = [...OMP_MODELS, ...cliModels];
    const seenKeys = new Set<string>();
    return merged.filter((m) => {
      const key = m.identifier ?? m.id;
      if (seenKeys.has(key)) return false;
      seenKeys.add(key);
      return true;
    });
  }

  // Claude (default)
  let builtIns: ModelInfo[] = CLAUDE_MODELS;
  if (claudeMapping && Object.keys(claudeMapping).length > 0) {
    try {
      builtIns = CLAUDE_MODELS.map((m) => applyClaudeModelMapping(m, claudeMapping));
    } catch {
      builtIns = CLAUDE_MODELS;
    }
  }

  if (claudeCustomModels.length === 0) {
    return builtIns;
  }

  // Customs first; collapse duplicate *labels* (several built-in slots mapped
  // to the same real model name) the same way the settings panel used to.
  const merged = [...claudeCustomModels, ...builtIns];
  const seenLabels = new Set<string>();
  const seenIds = new Set<string>();
  return merged.filter((m) => {
    if (seenIds.has(m.id)) return false;
    seenIds.add(m.id);
    const key = m.label.trim().toLowerCase();
    if (key && seenLabels.has(key)) return false;
    if (key) seenLabels.add(key);
    return true;
  });
}
