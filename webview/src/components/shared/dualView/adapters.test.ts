import { describe, it, expect } from 'vitest';
import {
  claudeEnvAdapter,
  claudeConfigAdapter,
  codexEnvAdapter,
  openCodeAdvancedAdapter,
  type ClaudeEnvFormState,
  type CodexEnvFormState,
  type OpenCodeAdvancedFormState,
} from './adapters';

// ── Claude:env 是对象 Record<string, any>(含保留 key ANTHROPIC_*,作真相源) ──

describe('claudeEnvAdapter', () => {
  it('serialize 幂等(同一 state 两次序列化字符串相等)', () => {
    const state: ClaudeEnvFormState = { env: { ANTHROPIC_AUTH_TOKEN: 'sk-1', CUSTOM_FOO: 'bar' } };
    expect(claudeEnvAdapter.serialize(state)).toBe(claudeEnvAdapter.serialize(state));
  });

  it('serialize 输出完整 env(含保留 key,作 JSON 视图真相源)', () => {
    const state: ClaudeEnvFormState = { env: { ANTHROPIC_AUTH_TOKEN: 'sk-1', CUSTOM_FOO: 'bar' } };
    const obj = JSON.parse(claudeEnvAdapter.serialize(state));
    expect(obj.env.ANTHROPIC_AUTH_TOKEN).toBe('sk-1');
    expect(obj.env.CUSTOM_FOO).toBe('bar');
  });

  it('round-trip:serialize→parse 回到等价 state(含非字符串值类型)', () => {
    const state: ClaudeEnvFormState = { env: { ANTHROPIC_MODEL: 'glm-5.2', FOO: 'bar', NUM: 42, FLAG: true } };
    const parsed = claudeEnvAdapter.parse(claudeEnvAdapter.serialize(state));
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.env).toEqual(state.env);
    }
  });

  it('parse 根非对象(数组)→ ok:false', () => {
    expect(claudeEnvAdapter.parse('[1,2,3]').ok).toBe(false);
  });

  it('parse 根非对象(字符串)→ ok:false', () => {
    expect(claudeEnvAdapter.parse('"hello"').ok).toBe(false);
  });

  it('parse env 字段非对象(数组)→ ok:false', () => {
    expect(claudeEnvAdapter.parse(JSON.stringify({ env: [1, 2] })).ok).toBe(false);
  });

  it('parse env 缺失 → state.env = {}', () => {
    const parsed = claudeEnvAdapter.parse('{}');
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.env).toEqual({});
    }
  });

  it('parse 非法 JSON → ok:false', () => {
    expect(claudeEnvAdapter.parse('{not json').ok).toBe(false);
  });
});

// ── Codex:env 是 EnvVarEntry[] ×2(messageEnvVars + mcpEnvVars) ──

describe('codexEnvAdapter', () => {
  it('serialize 幂等', () => {
    const state: CodexEnvFormState = {
      messageEnvVars: [{ key: 'FOO', value: '1' }],
      mcpEnvVars: [{ key: 'BAR', value: '2' }],
    };
    expect(codexEnvAdapter.serialize(state)).toBe(codexEnvAdapter.serialize(state));
  });

  it('serialize 过滤空 key entry(stripEmptyKey,空串与纯空白)', () => {
    const state: CodexEnvFormState = {
      messageEnvVars: [
        { key: 'FOO', value: '1' },
        { key: '', value: 'x' },
        { key: '   ', value: 'y' },
      ],
      mcpEnvVars: [],
    };
    const obj = JSON.parse(codexEnvAdapter.serialize(state));
    expect(obj.messageEnvVars).toEqual([{ key: 'FOO', value: '1' }]);
    expect(obj.mcpEnvVars).toEqual([]);
  });

  it('round-trip(全有效 key 的 state 不受 stripEmptyKey 影响)', () => {
    const state: CodexEnvFormState = {
      messageEnvVars: [{ key: 'FOO', value: '1' }, { key: 'BAR', value: '2' }],
      mcpEnvVars: [{ key: 'BAZ', value: '3' }],
    };
    const parsed = codexEnvAdapter.parse(codexEnvAdapter.serialize(state));
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state).toEqual(state);
    }
  });

  it('parse messageEnvVars/mcpEnvVars 缺失 → 均为 []', () => {
    const parsed = codexEnvAdapter.parse('{}');
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.messageEnvVars).toEqual([]);
      expect(parsed.state.mcpEnvVars).toEqual([]);
    }
  });

  it('parse messageEnvVars 为 null → []', () => {
    const parsed = codexEnvAdapter.parse(JSON.stringify({ messageEnvVars: null }));
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.messageEnvVars).toEqual([]);
    }
  });

  it('parse messageEnvVars 非数组 → ok:false', () => {
    expect(codexEnvAdapter.parse(JSON.stringify({ messageEnvVars: 'foo' })).ok).toBe(false);
  });

  it('parse messageEnvVars 含非对象项 → ok:false', () => {
    expect(codexEnvAdapter.parse(JSON.stringify({ messageEnvVars: ['nope'] })).ok).toBe(false);
  });

  it('parse messageEnvVars 项缺 value → ok:false(严格 string 约定)', () => {
    expect(codexEnvAdapter.parse(JSON.stringify({ messageEnvVars: [{ key: 'FOO' }] })).ok).toBe(false);
  });

  it('parse messageEnvVars 项 value 非 string → ok:false', () => {
    expect(
      codexEnvAdapter.parse(JSON.stringify({ messageEnvVars: [{ key: 'FOO', value: 1 }] })).ok
    ).toBe(false);
  });

  it('parse 根非对象 → ok:false', () => {
    expect(codexEnvAdapter.parse('[]').ok).toBe(false);
  });

  it('validate:重复 key(大小写不敏感)→ 返回错误文案', () => {
    const state: CodexEnvFormState = {
      messageEnvVars: [{ key: 'FOO', value: '1' }, { key: 'foo', value: '2' }],
      mcpEnvVars: [],
    };
    expect(codexEnvAdapter.validate?.(state)).not.toBeNull();
  });

  it('validate:非法格式 key(数字开头)→ 返回错误文案', () => {
    const state: CodexEnvFormState = {
      messageEnvVars: [{ key: '1invalid', value: '1' }],
      mcpEnvVars: [],
    };
    expect(codexEnvAdapter.validate?.(state)).not.toBeNull();
  });

  it('validate:合法 entries → null', () => {
    const state: CodexEnvFormState = {
      messageEnvVars: [{ key: 'FOO', value: '1' }],
      mcpEnvVars: [{ key: 'BAR', value: '2' }],
    };
    expect(codexEnvAdapter.validate?.(state)).toBeNull();
  });
});

// ── OpenCode:半 schema-less,raw 透传段(除 id/name/baseURL/apiKey/models 外) ──

describe('openCodeAdvancedAdapter', () => {
  it('serialize 幂等', () => {
    const state: OpenCodeAdvancedFormState = {
      raw: { options: { model: 'x' }, npm: '@opencode/opencode' },
    };
    expect(openCodeAdvancedAdapter.serialize(state)).toBe(openCodeAdvancedAdapter.serialize(state));
  });

  it('round-trip(含嵌套对象与数组)', () => {
    const state: OpenCodeAdvancedFormState = { raw: { foo: { bar: 1 }, arr: [1, 2, 3] } };
    const parsed = openCodeAdvancedAdapter.parse(openCodeAdvancedAdapter.serialize(state));
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.raw).toEqual(state.raw);
    }
  });

  it('parse 根非对象(数组)→ ok:false', () => {
    expect(openCodeAdvancedAdapter.parse('[1,2,3]').ok).toBe(false);
  });

  it('parse 根非对象(字符串)→ ok:false', () => {
    expect(openCodeAdvancedAdapter.parse('"hello"').ok).toBe(false);
  });

  it('parse 非法 JSON → ok:false', () => {
    expect(openCodeAdvancedAdapter.parse('{not').ok).toBe(false);
  });

  it('parse 空对象 → ok:true, raw = {}', () => {
    const parsed = openCodeAdvancedAdapter.parse('{}');
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.raw).toEqual({});
    }
  });
});

// ── Claude(整体 settingsConfig):JSON 视图=完整 jsonConfig,不丢非 env 字段 ──

describe('claudeConfigAdapter', () => {
  it('serialize 幂等', () => {
    const config = {
      env: { ANTHROPIC_AUTH_TOKEN: 'sk-1', CUSTOM_FOO: 'bar' },
      model: 'sonnet',
      alwaysThinkingEnabled: true,
    };
    expect(claudeConfigAdapter.serialize(config)).toBe(claudeConfigAdapter.serialize(config));
  });

  it('serialize 输出完整 settingsConfig(含 env + model + alwaysThinkingEnabled,JSON 视图真相源)', () => {
    const config = {
      env: { ANTHROPIC_AUTH_TOKEN: 'sk-1' },
      model: 'sonnet',
      alwaysThinkingEnabled: true,
      ccSwitchProviderId: 'default',
    };
    const obj = JSON.parse(claudeConfigAdapter.serialize(config));
    expect(obj.env.ANTHROPIC_AUTH_TOKEN).toBe('sk-1');
    expect(obj.model).toBe('sonnet');
    expect(obj.alwaysThinkingEnabled).toBe(true);
    expect(obj.ccSwitchProviderId).toBe('default');
  });

  it('round-trip(含 env 内保留 key 与自定义 key + 非 env 顶层字段)', () => {
    const config = {
      env: { ANTHROPIC_BASE_URL: 'http://x', CUSTOM: 'c', NUM: 42 },
      model: 'sonnet',
      alwaysThinkingEnabled: false,
    };
    const parsed = claudeConfigAdapter.parse(claudeConfigAdapter.serialize(config));
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state).toEqual(config);
    }
  });

  it('parse 根非对象(数组)→ ok:false', () => {
    expect(claudeConfigAdapter.parse('[1,2,3]').ok).toBe(false);
  });

  it('parse env 非对象(数组)→ ok:false', () => {
    expect(claudeConfigAdapter.parse(JSON.stringify({ env: [1, 2] })).ok).toBe(false);
  });

  it('parse env 缺失 → ok:true(允许 config 不含 env)', () => {
    const parsed = claudeConfigAdapter.parse(JSON.stringify({ model: 'sonnet' }));
    expect(parsed.ok).toBe(true);
    if (parsed.ok) {
      expect(parsed.state.model).toBe('sonnet');
    }
  });

  it('parse 非法 JSON → ok:false', () => {
    expect(claudeConfigAdapter.parse('{not json').ok).toBe(false);
  });
});
