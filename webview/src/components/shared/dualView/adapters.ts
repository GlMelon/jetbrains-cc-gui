import type { EnvVarEntry } from '../../../types/provider';
import { validateEnvVarEntries } from '../../../types/provider';

/**
 * DualViewSwitcher 的通用适配器:定义表单状态 S ↔ JSON 文本 的双向转换。
 * 三家 provider 各一个实例(claudeEnvAdapter / codexEnvAdapter / openCodeAdvancedAdapter)。
 *
 * 设计:serialize 必须幂等且确定(同输入同输出);parse 宽容结构异常但严格类型,
 * 非法输入返回 {ok:false,error} 由切换器决定降级(阻止切换 + 留在 JSON 模式,不丢数据)。
 */
export type DualViewParseResult<S> = { ok: true; state: S } | { ok: false; error: string };

export interface DualViewAdapter<S> {
  /** 表单状态 → JSON 文本(JSON 视图显示用)。必须幂等、确定。 */
  serialize: (state: S) => string;
  /** JSON 文本 → 表单状态。非法时返回 {ok:false,error}。 */
  parse: (text: string) => DualViewParseResult<S>;
  /** 表单状态校验(JSON 编辑回填表单前的合法性检查);null 表示通过。 */
  validate?: (state: S) => string | null;
}

// ── Claude(整体 settingsConfig):JSON 视图=完整 jsonConfig,不丢非 env 字段 ──
// Claude 的 jsonConfig 是整个 settingsConfig(env + model + alwaysThinkingEnabled + ccSwitchProviderId + …),
// 故 DualViewSwitcher 的 formState 直接是 settingsConfig 对象(非 env-only),
// JSON 视图显示完整配置,表单视图(EnvRecordEditor)仅编辑 env 自定义字段。

export type ClaudeConfigFormState = Record<string, any>;

export interface ClaudeEnvFormState {
  env: Record<string, any>;
}

export const claudeEnvAdapter: DualViewAdapter<ClaudeEnvFormState> = {
  serialize: (state) => JSON.stringify(state, null, 2),
  parse: (text) => {
    let obj: unknown;
    try {
      obj = JSON.parse(text);
    } catch {
      return { ok: false, error: 'JSON 语法错误' };
    }
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
      return { ok: false, error: '根必须是对象' };
    }
    const root = obj as Record<string, unknown>;
    const env = root.env;
    if (env !== undefined && env !== null && (typeof env !== 'object' || Array.isArray(env))) {
      return { ok: false, error: 'env 必须是对象' };
    }
    return { ok: true, state: { env: (env ?? {}) as Record<string, any> } };
  },
};

export const claudeConfigAdapter: DualViewAdapter<ClaudeConfigFormState> = {
  serialize: (config) => JSON.stringify(config, null, 2),
  parse: (text) => {
    let obj: unknown;
    try {
      obj = JSON.parse(text);
    } catch {
      return { ok: false, error: 'JSON 语法错误' };
    }
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
      return { ok: false, error: '根必须是对象' };
    }
    const root = obj as Record<string, unknown>;
    // env 若存在须是对象(缺失允许 —— config 可不含 env)
    if ('env' in root) {
      const env = root.env;
      if (env !== null && (typeof env !== 'object' || Array.isArray(env))) {
        return { ok: false, error: 'env 必须是对象' };
      }
    }
    return { ok: true, state: root as Record<string, any> };
  },
};



/** 过滤 key 为空(空串或纯空白)的 entry;save 时用,避免把占位空行落盘。 */
function stripEmptyKey(entries: EnvVarEntry[]): EnvVarEntry[] {
  return entries.filter(
    (e) => e && typeof e.key === 'string' && e.key.trim() !== '',
  );
}

function parseEnvVarArray(
  value: unknown,
): { ok: true; v: EnvVarEntry[] } | { ok: false; error: string } {
  if (value === undefined || value === null) {
    return { ok: true, v: [] };
  }
  if (!Array.isArray(value)) {
    return { ok: false, error: '必须是数组' };
  }
  const result: EnvVarEntry[] = [];
  for (const item of value) {
    if (item === null || typeof item !== 'object' || Array.isArray(item)) {
      return { ok: false, error: '数组项必须是 {key, value} 对象' };
    }
    const { key, value: v } = item as { key?: unknown; value?: unknown };
    if (typeof key !== 'string' || typeof v !== 'string') {
      return { ok: false, error: 'key 与 value 必须是字符串' };
    }
    result.push({ key, value: v });
  }
  return { ok: true, v: result };
}

export interface CodexEnvFormState {
  messageEnvVars: EnvVarEntry[];
  mcpEnvVars: EnvVarEntry[];
}

export const codexEnvAdapter: DualViewAdapter<CodexEnvFormState> = {
  serialize: ({ messageEnvVars, mcpEnvVars }) =>
    JSON.stringify(
      {
        messageEnvVars: stripEmptyKey(messageEnvVars),
        mcpEnvVars: stripEmptyKey(mcpEnvVars),
      },
      null,
      2,
    ),
  parse: (text) => {
    let obj: unknown;
    try {
      obj = JSON.parse(text);
    } catch {
      return { ok: false, error: 'JSON 语法错误' };
    }
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
      return { ok: false, error: '根必须是对象' };
    }
    const root = obj as { messageEnvVars?: unknown; mcpEnvVars?: unknown };
    const me = parseEnvVarArray(root.messageEnvVars);
    if (!me.ok) return { ok: false, error: `messageEnvVars ${me.error}` };
    const mc = parseEnvVarArray(root.mcpEnvVars);
    if (!mc.ok) return { ok: false, error: `mcpEnvVars ${mc.error}` };
    return { ok: true, state: { messageEnvVars: me.v, mcpEnvVars: mc.v } };
  },
  validate: (state) => {
    const issues = [
      ...validateEnvVarEntries(state.messageEnvVars),
      ...validateEnvVarEntries(state.mcpEnvVars),
    ];
    return issues.length > 0 ? '存在非法/受保护/重复的环境变量' : null;
  },
};

// ── OpenCode:半 schema-less,raw 透传段(除 id/name/baseURL/apiKey/models 外的原生字段) ──

export interface OpenCodeAdvancedFormState {
  raw: Record<string, any>;
}

export const openCodeAdvancedAdapter: DualViewAdapter<OpenCodeAdvancedFormState> = {
  serialize: ({ raw }) => JSON.stringify(raw, null, 2),
  parse: (text) => {
    let obj: unknown;
    try {
      obj = JSON.parse(text);
    } catch {
      return { ok: false, error: 'JSON 语法错误' };
    }
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
      return { ok: false, error: '根必须是对象' };
    }
    return { ok: true, state: { raw: obj as Record<string, any> } };
  },
};
