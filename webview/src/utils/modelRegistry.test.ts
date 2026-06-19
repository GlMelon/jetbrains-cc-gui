import { describe, expect, it } from 'vitest';
import {
  __setModelRegistryForTests,
  createCodexCatalogModels,
  getModelsForProvider,
  parseModelRegistryPayload,
  resetModelRegistryForTests,
  resolveClaudeModelId,
  resolveClaudeRoleForModel,
} from './modelRegistry';

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
          enabled: true,
        },
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'sonnet',
          label: 'mimo-v2.5',
          actualModel: 'mimo-v2.5',
          contextWindow: 1_000_000,
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
          enabled: true,
        },
      ],
    });

    expect(resolveClaudeModelId('mimo-v2.5[1m]')).toBe('mimo-v2.5');
  });

  it('falls back to role normalization when the model is absent from the registry', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          contextWindow: 1_000_000,
          enabled: true,
        },
      ],
    });

    // Unknown real model name → falls back to sonnet role, mirroring normalizeClaudeModelId.
    expect(resolveClaudeModelId('claude-opus-4-8')).toBe('claude-role-sonnet');
  });

  it('preserves built-in role IDs using the default registry', () => {
    expect(resolveClaudeModelId('claude-role-opus')).toBe('claude-role-opus');
    expect(resolveClaudeModelId('claude-role-sonnet')).toBe('claude-role-sonnet');
  });

  it('ignores disabled registry entries and falls back to normalization', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          label: 'mimo-v2.5',
          contextWindow: 1_000_000,
          enabled: false,
        },
      ],
    });

    expect(resolveClaudeModelId('mimo-v2.5')).toBe('claude-role-sonnet');
  });

  it('defaults empty input to the sonnet role', () => {
    expect(resolveClaudeModelId('')).toBe('claude-role-sonnet');
    expect(resolveClaudeModelId(undefined)).toBe('claude-role-sonnet');
    expect(resolveClaudeModelId(null)).toBe('claude-role-sonnet');
  });
});

describe('resolveClaudeRoleForModel', () => {
  beforeEach(() => {
    resetModelRegistryForTests();
  });

  it('derives role from built-in claude-role-* model IDs', () => {
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
    expect(resolveClaudeRoleForModel('claude-role-opus[1m]')).toBe('opus');

    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo-v2.5',
          provider: 'claude',
          role: 'fable',
          label: 'MiMo v2.5',
          contextWindow: 1_000_000,
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
