import { STORAGE_KEYS } from '../types/provider';

/**
 * Claude model mapping configuration.
 */
export interface ClaudeModelMapping {
  main?: string;
  haiku?: string;
  sonnet?: string;
  opus?: string;
  [key: string]: string | undefined;
}

/**
 * Read the Claude model mapping.
 */
export function readClaudeModelMapping(): ClaudeModelMapping {
  try {
    const stored = localStorage.getItem(STORAGE_KEYS.CLAUDE_MODEL_MAPPING);
    if (!stored) {
      return {};
    }
    const parsed = JSON.parse(stored);
    return parsed && typeof parsed === 'object' ? parsed as ClaudeModelMapping : {};
  } catch {
    return {};
  }
}

/**
 * Resolve the mapped display name for a Claude role.
 *
 * 单一映射解析入口(D5 收口):ButtonArea 与 ModelSelect 共用,消除两套 key 解析
 * 与 opus_1m 死代码。
 *
 * @param role 后端权威下发的角色短名('sonnet' | 'opus' | 'fable' | 'haiku');
 *             为 undefined(非内置 Claude 模型)时仅回退 main
 * @param mapping readClaudeModelMapping() 的结果
 * @returns 映射名(trim 后)或 undefined(无任何映射)
 */
export function resolveMappedModelName(
  role: string | undefined,
  mapping: ClaudeModelMapping,
): string | undefined {
  if (!role) {
    return mapping.main?.trim() || undefined;
  }
  const mapped = mapping[role] || mapping.main;
  return mapped?.trim() || undefined;
}

/**
 * Check whether the mapping contains at least one valid model value.
 */
function hasMappingValue(mapping: ClaudeModelMapping): boolean {
  return Object.values(mapping).some(value => value && value.trim().length > 0);
}

/**
 * Write the Claude model mapping and proactively notify listeners in the same tab to refresh.
 */
export function writeClaudeModelMapping(mapping: ClaudeModelMapping): void {
  try {
    if (hasMappingValue(mapping)) {
      localStorage.setItem(STORAGE_KEYS.CLAUDE_MODEL_MAPPING, JSON.stringify(mapping));
    } else {
      localStorage.removeItem(STORAGE_KEYS.CLAUDE_MODEL_MAPPING);
    }

    // localStorage writes in the same tab do not trigger the native storage event, so dispatch one manually here.
    window.dispatchEvent(new CustomEvent('localStorageChange', {
      detail: { key: STORAGE_KEYS.CLAUDE_MODEL_MAPPING },
    }));
  } catch {
    // Gracefully degrade when localStorage is unavailable or the write fails
  }
}
