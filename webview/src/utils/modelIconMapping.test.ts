import { describe, expect, it } from 'vitest';
import { resolveIconVendor, resolveModelVendor } from './modelIconMapping';

describe('modelIconMapping', () => {
  it('resolves Codex provider and Codex model IDs to the Codex icon', () => {
    expect(resolveModelVendor('gpt-5.3-codex-spark')).toBe('codex');
    expect(resolveIconVendor('codex', 'gpt-5.3-codex-spark')).toBe('codex');
    expect(resolveIconVendor('codex', 'gpt-5.3')).toBe('codex');
  });

  it('keeps generic OpenAI model IDs on the OpenAI icon outside Codex providers', () => {
    expect(resolveModelVendor('gpt-5.3')).toBe('openai');
    expect(resolveIconVendor(undefined, 'gpt-5.3')).toBe('openai');
    expect(resolveIconVendor('openrouter', 'gpt-5.3')).toBe('openai');
  });

  it('still matches dedicated Spark vendor model ids', () => {
    expect(resolveModelVendor('spark-max')).toBe('spark');
    expect(resolveIconVendor(undefined, 'spark-lite')).toBe('spark');
  });

  it('resolves Xiaomi MiMo models before falling back to Claude provider icons', () => {
    expect(resolveModelVendor('mimo-v2.5-pro')).toBe('xiaomi');
    expect(resolveIconVendor('claude', 'mimo-v2.5-pro')).toBe('xiaomi');
    expect(resolveIconVendor('xiaomi')).toBe('xiaomi');
  });
});
