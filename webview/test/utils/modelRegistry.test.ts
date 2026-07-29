import { describe, expect, it } from 'vitest';
import {
  __setModelRegistryForTests,
  createCodexCatalogModels,
  getModelsForProvider,
  normalizeProvider,
  parseModelRegistryPayload,
  resetModelRegistryForTests,
  resolveClaudeModelId,
  resolveClaudeRoleForModel,
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
      provider: 'claude',
      contextWindow: 1_000_000,
    });
  });

  it('parses Claude role and actual request model fields', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        {
          id: 'claude-role-sonnet',
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
      provider: 'claude',
      role: 'sonnet',
      actualModel: 'glm5.2',
      label: 'GLM 5.2',
    });
  });

  it('defaults contextWindow to 200000 when absent, aligning with backend', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { id: 'mimo', provider: 'claude', label: 'Mimo' },
      ],
    });
    expect(parsed?.items[0].contextWindow).toBe(200_000);
  });

  it('defaults contextWindow to 200000 when non-positive, aligning with backend', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { id: 'mimo', provider: 'claude', label: 'Mimo', contextWindow: 0 },
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
        { id: 'mimo', provider: 'claude', label: 'Mimo', contextWindow: 200000 },
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
          id: 'mimo-v2.5', provider: 'claude', role: 'sonnet', label: 'MiMo',
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
        label: 'MiMo v2.5',
      }),
    ]);
  });

  it('exposes Claude actual model as option metadata', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
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

describe('resolveClaudeRoleForModel', () => {
  beforeEach(() => {
    resetModelRegistryForTests();
  });

  it('reads the role field from registry for built-in claude-role-* model IDs', () => {
    // A3:registry 未加载时(空)即使传入 claude-role-* id 也返回 null——不再从 id 离线推导。
    expect(resolveClaudeRoleForModel('claude-role-sonnet')).toBeNull();

    __setModelRegistryForTests({
      items: [
        { id: 'claude-role-sonnet', provider: 'claude', role: 'sonnet', label: 'Sonnet', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true },
        { id: 'claude-role-opus', provider: 'claude', role: 'opus', label: 'Opus', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true },
        { id: 'claude-role-fable', provider: 'claude', role: 'fable', label: 'Fable', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true },
        { id: 'claude-role-haiku', provider: 'claude', role: 'haiku', label: 'Haiku', contextWindow: 200_000, supports1MContext: false, readOnly: false, enabled: true },
      ],
    });

    // registry.role 权威下发后回填。
    expect(resolveClaudeRoleForModel('claude-role-sonnet')).toBe('sonnet');
    expect(resolveClaudeRoleForModel('claude-role-opus')).toBe('opus');
    expect(resolveClaudeRoleForModel('claude-role-fable')).toBe('fable');
    expect(resolveClaudeRoleForModel('claude-role-haiku')).toBe('haiku');
  });

  it('returns the configured role for custom models in the registry', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'opus',
          label: 'MiMo v2.5',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    // 自定义模型走 registry.role —— 修复点:不再因不在 model-id 白名单而被隐藏。
    expect(resolveClaudeRoleForModel('mimo-v2.5')).toBe('opus');
  });

  it('returns null for custom models without a role field', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'legacy-custom',
          provider: 'claude',
          label: 'Legacy',
          contextWindow: 200_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    expect(resolveClaudeRoleForModel('legacy-custom')).toBeNull();
  });

  it('ignores disabled registry entries', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'sonnet',
          label: 'MiMo v2.5',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: false,
        },
      ],
    });

    expect(resolveClaudeRoleForModel('mimo-v2.5')).toBeNull();
  });

  it('returns null for unknown models not in the registry', () => {
    // 既非 claude-role-* 形式、也不在 registry 中 → 无法判定 role。
    expect(resolveClaudeRoleForModel('claude-opus-4-8')).toBeNull();
  });

  it('strips the [1m] suffix before resolving', () => {
    // A3:registry 设了内置 opus,strip [1m] 后按 registry.role 解析。
    __setModelRegistryForTests({
      items: [
        { id: 'claude-role-opus', provider: 'claude', role: 'opus', label: 'Opus', contextWindow: 1_000_000, supports1MContext: false, readOnly: false, enabled: true },
      ],
    });
    expect(resolveClaudeRoleForModel('claude-role-opus[1m]')).toBe('opus');

    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'fable',
          label: 'MiMo v2.5',
          contextWindow: 1_000_000,
          supports1MContext: false,
          readOnly: false,
          enabled: true,
        },
      ],
    });
    expect(resolveClaudeRoleForModel('mimo-v2.5[1m]')).toBe('fable');
  });

  it('returns null for empty input', () => {
    expect(resolveClaudeRoleForModel('')).toBeNull();
    expect(resolveClaudeRoleForModel(undefined)).toBeNull();
    expect(resolveClaudeRoleForModel(null)).toBeNull();
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
