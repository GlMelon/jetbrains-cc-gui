import { describe, expect, it } from 'vitest';
import { getModelsForProvider, parseModelRegistryPayload } from './modelRegistry';

describe('modelRegistry', () => {
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

  it('rejects empty or malformed payloads', () => {
    expect(parseModelRegistryPayload('{bad')).toBeNull();
    expect(parseModelRegistryPayload({ items: [] })).toBeNull();
    expect(parseModelRegistryPayload({ items: [{ id: '', provider: 'claude' }] })).toBeNull();
  });

  it('defaults include gpt-5.5 as codex 1M model', () => {
    const codexModels = getModelsForProvider('codex');
    expect(codexModels.find((model) => model.id === 'gpt-5.5')?.contextWindow).toBe(1_000_000);
  });
});
