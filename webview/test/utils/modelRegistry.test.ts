import { describe, expect, it } from 'vitest';
import {
  __setModelRegistryForTests,
  createCodexCatalogModels,
  getModelsForProvider,
  getProviderDefaultReasoningLevels,
  resolveReasoningLevels,
  normalizeProvider,
  parseModelRegistryPayload,
  resetModelRegistryForTests,
  resolveClaudeModelId,
} from '../../src/utils/modelRegistry';

describe('modelRegistry', () => {
  beforeEach(() => {
    resetModelRegistryForTests();
  });

  it('parses valid model registry payloads', () => {
    const parsed = parseModelRegistryPayload(JSON.stringify({
      items: [
        {
          id: 'mimo-v2.5-pro',
          identifier: 'reg-mimo-v2.5-pro',
          provider: 'claude',
          label: 'Mimo',
          contextWindow: 1_000_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        },
      ],
    }));

    expect(parsed?.items[0]).toMatchObject({
      id: 'mimo-v2.5-pro',
      identifier: 'reg-mimo-v2.5-pro',
      provider: 'claude',
      contextWindow: 1_000_000,
    });
  });

  it('parses Claude role and actual request model fields', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        {
          id: 'claude-role-sonnet',
          identifier: 'reg-claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'GLM 5.2',
          actualModel: 'glm5.2',
          contextWindow: 1_000_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    expect(parsed?.items[0]).toMatchObject({
      id: 'claude-role-sonnet',
      identifier: 'reg-claude-role-sonnet',
      provider: 'claude',
      role: 'sonnet',
      actualModel: 'glm5.2',
      label: 'GLM 5.2',
    });
  });

  it('defaults contextWindow to 200000 when absent, aligning with backend', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { identifier: 'reg-mimo', id: 'mimo', provider: 'claude', label: 'Mimo' },
      ],
    });
    expect(parsed?.items[0].contextWindow).toBe(200_000);
  });

  it('defaults contextWindow to 200000 when non-positive, aligning with backend', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { identifier: 'reg-mimo', id: 'mimo', provider: 'claude', label: 'Mimo', contextWindow: 0 },
      ],
    });
    expect(parsed?.items[0].contextWindow).toBe(200_000);
  });

  it('rejects empty or malformed payloads', () => {
    expect(parseModelRegistryPayload('{bad')).toBeNull();
    expect(parseModelRegistryPayload({ items: [] })).toBeNull();
    expect(parseModelRegistryPayload({ items: [{ id: '', provider: 'claude' }] })).toBeNull();
  });

  it('reads readOnly flag when true', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        {
          id: 'claude-role-sonnet',
          identifier: 'reg-claude-role-sonnet',
          provider: 'claude',
          label: 'Sonnet',
          contextWindow: 200000,
          readOnly: true,
        },
      ],
    });
    expect(parsed?.items[0].readOnly).toBe(true);
  });

  it('defaults readOnly to false when absent', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { identifier: 'reg-mimo', id: 'mimo', provider: 'claude', label: 'Mimo', contextWindow: 200000 },
      ],
    });
    expect(parsed?.items[0].readOnly).toBe(false);
  });

  it('parsed item covers all backend ModelConfig fields (payload SSOT guard)', () => {
    // 与后端 com.github.claudecodegui.config.ModelConfig record 字段逐一对齐。
    // 后端守门:ModelRegistryServiceSerializeTest.serializeEmitsExactlyTheModelConfigRecordFields
    const BACKEND_MODEL_CONFIG_FIELDS = [
      'id', 'provider', 'role', 'label', 'actualModel',
      'description', 'contextWindow', 'supports1MContext', 'enabled', 'readOnly',
    ] as const;

    const parsed = parseModelRegistryPayload({
      items: [
        {
          id: 'mimo-v2.5', identifier: 'reg-mimo-v2.5', provider: 'claude', role: 'sonnet', label: 'MiMo',
          actualModel: 'mimo-v2.5', description: 'desc', contextWindow: 1_000_000,
          supports1MContext: true, enabled: true, readOnly: false,
        },
      ],
    });

    const parsedKeys = Object.keys(parsed!.items[0]);
    for (const field of BACKEND_MODEL_CONFIG_FIELDS) {
      expect(parsedKeys, `parsed item missing backend field: ${field}`).toContain(field);
    }
  });

  it('defaults do not include hard-coded Codex GPT model catalog', () => {
    const codexModels = getModelsForProvider('codex');
    expect(codexModels).toEqual([]);
  });

  it('creates Codex catalog models from provider catalog when present', () => {
    const models = createCodexCatalogModels({
      customModels: [
        { id: 'mimo-v2.5', label: 'MiMo v2.5', contextWindow: 262_144 },
        { id: 'glm-5.2', label: 'GLM 5.2', contextWindow: 200_000 },
      ],
      configToml: 'model = "fallback-model"',
    });

    expect(models.map((model) => model.id)).toEqual(['mimo-v2.5', 'glm-5.2']);
  });

  it('creates a Codex model option from current config model when catalog is absent', () => {
    const models = createCodexCatalogModels({
      configToml: 'model = "mimo-v2.5"',
    });

    expect(models).toEqual([
      expect.objectContaining({
        id: 'mimo-v2.5',
        label: 'mimo-v2.5',
        provider: 'codex',
      }),
    ]);
  });

  it('uses current registry payload as the provider model source', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          identifier: 'reg-mimo-v2.5',
          provider: 'codex',
          label: 'MiMo v2.5',
          contextWindow: 262_144,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    expect(getModelsForProvider('codex')).toEqual([
      expect.objectContaining({
        id: 'mimo-v2.5',
        identifier: 'reg-mimo-v2.5',
        label: 'MiMo v2.5',
      }),
    ]);
  });

  it('exposes Claude actual model as option metadata', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          identifier: 'reg-claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'GLM 5.2',
          actualModel: 'glm5.2',
          contextWindow: 1_000_000,
          supports1MContext: true,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    expect(getModelsForProvider('claude')).toEqual([
      expect.objectContaining({
        id: 'claude-role-sonnet',
        identifier: 'reg-claude-role-sonnet',
        label: 'GLM 5.2',
        description: 'Sonnet · glm5.2',
      }),
    ]);
  });
});

describe('resolveClaudeModelId', () => {
  beforeEach(() => {
    resetModelRegistryForTests();
  });

  it('preserves custom Claude model IDs present in the registry', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          identifier: 'reg-claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          actualModel: 'glm5.2',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
        {
          id: 'mimo-v2.5',
          identifier: 'reg-mimo-v2.5',
          provider: 'claude',
          role: 'sonnet',
          label: 'mimo-v2.5',
          actualModel: 'mimo-v2.5',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    expect(resolveClaudeModelId('mimo-v2.5')).toBe('mimo-v2.5');
  });

  it('strips the [1m] suffix before resolving against the registry', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          identifier: 'reg-mimo-v2.5',
          provider: 'claude',
          label: 'mimo-v2.5',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    expect(resolveClaudeModelId('mimo-v2.5[1m]')).toBe('mimo-v2.5');
  });

  it('preserves the original ID when the model is absent from the registry', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          identifier: 'reg-claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    // A3:不再归一化;未命中 registry 的 id 原样保留,由后端 session 下发纠正。
    expect(resolveClaudeModelId('claude-opus-4-8')).toBe('claude-opus-4-8');
  });

  it('preserves built-in role IDs using the default registry', () => {
    expect(resolveClaudeModelId('claude-role-opus')).toBe('claude-role-opus');
    expect(resolveClaudeModelId('claude-role-sonnet')).toBe('claude-role-sonnet');
  });

  it('preserves the original ID when the registry entry is disabled', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          identifier: 'reg-mimo-v2.5',
          provider: 'claude',
          label: 'mimo-v2.5',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: false,
        },
      ],
    });

    // A3:disabled 不命中,但也不再归一化——原样保留。
    expect(resolveClaudeModelId('mimo-v2.5')).toBe('mimo-v2.5');
  });

  it('returns empty string for empty input', () => {
    // A3:不再回退 sonnet;空输入返回空字符串(strip 语义)。
    expect(resolveClaudeModelId('')).toBe('');
    expect(resolveClaudeModelId(undefined)).toBe('');
    expect(resolveClaudeModelId(null)).toBe('');
  });
});


describe('resolveReasoningLevels', () => {
  beforeEach(() => {
    resetModelRegistryForTests();
  });

  it('returns item levels when the model hits the registry', () => {
    __setModelRegistryForTests({
      items: [
        {
          identifier: 'reg-mimo-v2.5',
          id: 'mimo-v2.5',
          provider: 'codex',
          label: 'MiMo v2.5',
          contextWindow: 262_144,
          supportedReasoningLevels: ['low', 'medium', 'high'],
        },
      ],
    });

    expect(resolveReasoningLevels('codex', 'mimo-v2.5')).toEqual(['low', 'medium', 'high']);
    // [1m] 容量后缀剥离后命中
    expect(resolveReasoningLevels('codex', 'mimo-v2.5[1m]')).toEqual(['low', 'medium', 'high']);
  });

  it('returns empty array when the item exists without supportedReasoningLevels', () => {
    // 条目在但字段未下发 = 后端判定该模型无 reasoning 能力(如 dsh/minimax)
    __setModelRegistryForTests({
      items: [
        { identifier: 'reg-x', id: 'x', provider: 'dsh', label: 'X', contextWindow: 200_000 },
      ],
    });

    expect(resolveReasoningLevels('dsh', 'x')).toEqual([]);
  });

  it('falls back to providerDefaults when the model misses the registry', () => {
    __setModelRegistryForTests({
      items: [
        { identifier: 'reg-x', id: 'x', provider: 'kimi', label: 'X', contextWindow: 200_000 },
      ],
      providerDefaults: {
        grok: ['low', 'medium', 'high'],
      },
    });

    expect(resolveReasoningLevels('grok', 'grok-unknown')).toEqual(['low', 'medium', 'high']);
    expect(getProviderDefaultReasoningLevels('grok')).toEqual(['low', 'medium', 'high']);
    expect(getProviderDefaultReasoningLevels('claude')).toBeNull();
    expect(getProviderDefaultReasoningLevels(undefined)).toBeNull();
  });

  it('returns null (unknown) when neither registry item nor provider defaults exist', () => {
    expect(resolveReasoningLevels('claude', 'whatever')).toBeNull();
    expect(getProviderDefaultReasoningLevels('claude')).toBeNull();
  });

  it('parses root providerDefaults and filters invalid level values', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { identifier: 'reg-mimo', id: 'mimo', provider: 'claude', label: 'Mimo' },
      ],
      providerDefaults: {
        codex: ['low', 'medium', 'xhigh', 'turbo'],
        grok: [],
      },
    });

    expect(parsed?.providerDefaults).toEqual({ codex: ['low', 'medium', 'xhigh'] });
  });

  it('omits providerDefaults when the root field is absent or empty', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { identifier: 'reg-mimo', id: 'mimo', provider: 'claude', label: 'Mimo' },
      ],
    });
    expect(parsed?.providerDefaults).toBeUndefined();

    const empty = parseModelRegistryPayload({
      items: [
        { identifier: 'reg-mimo', id: 'mimo', provider: 'claude', label: 'Mimo' },
      ],
      providerDefaults: { grok: [] },
    });
    expect(empty?.providerDefaults).toBeUndefined();
  });
});

describe('normalizeProvider', () => {
  // 三 provider(Claude/Codex/OpenCode)归一化 SSOT:未知/缺失值统一回退 claude。
  // 历史不对称 bug:多处 inline `provider === 'codex' ? 'codex' : 'claude'` 把 opencode 误归一为 claude,
  // 导致 session.runtime_state / model.confirmed 下行时 opencode provider 被前端当成 claude 处理。
  it('正常归一已知三 provider', () => {
    expect(normalizeProvider('claude')).toBe('claude');
    expect(normalizeProvider('codex')).toBe('codex');
    expect(normalizeProvider('opencode')).toBe('opencode');
  });

  it('未知 provider 回退 claude', () => {
    expect(normalizeProvider('unknown')).toBe('claude');
    expect(normalizeProvider('claude-opus')).toBe('claude');
  });

  it('空字符串回退 claude', () => {
    expect(normalizeProvider('')).toBe('claude');
    expect(normalizeProvider('   ')).toBe('claude');
  });

  it('null/undefined 回退 claude', () => {
    expect(normalizeProvider(null)).toBe('claude');
    expect(normalizeProvider(undefined)).toBe('claude');
  });
});
